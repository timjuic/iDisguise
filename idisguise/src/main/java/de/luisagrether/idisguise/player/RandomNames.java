package de.luisagrether.idisguise.player;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import de.luisagrether.idisguise.iDisguise;

/**
 * Names for /disguise random: names of real Minecraft accounts, so they look like real players and
 * come with that account's own skin. Candidates are built from common name parts and kept only when
 * Mojang confirms the account exists. A stock is kept in random-names.yml so the command is instant.
 */
final class RandomNames {

	private final Plugin plugin;
	private final Logger logger;
	private final File file;
	private final List<String> parts = new ArrayList<>();
	private final List<String> pool = Collections.synchronizedList(new ArrayList<>());
	private final AtomicBoolean refilling = new AtomicBoolean();
	private final Random random = new Random();
	private final int size;
	// true = this name belongs to a player of this server (never used for random disguises)
	private final Predicate<String> knownHere;

	RandomNames(Plugin plugin, int size, Predicate<String> knownHere) {
		this.plugin = plugin;
		this.logger = plugin.getLogger();
		this.file = new File(plugin.getDataFolder(), "random-names.yml");
		this.size = size;
		this.knownHere = knownHere;
		try(InputStream input = plugin.getResource("random-name-parts.txt");
				BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			while((line = reader.readLine()) != null) {
				line = line.trim();
				if(!line.isEmpty() && !line.startsWith("#")) parts.add(line);
			}
		} catch(Exception e) {
			logger.log(Level.WARNING, "Could not read the random name parts", e);
		}
		if(file.isFile()) {
			pool.addAll(YamlConfiguration.loadConfiguration(file).getStringList("names"));
		}
	}

	int available() {
		return pool.size();
	}

	/** A name from the stock that passes the check, or null when none is ready. Call on the main thread. */
	String take(Predicate<String> usable) {
		String chosen = null;
		synchronized(pool) {
			Collections.shuffle(pool, random);
			for(int i = 0; i < pool.size(); i++) {
				String name = pool.get(i);
				if(usable.test(name)) {
					chosen = name;
					pool.remove(i);
					break;
				}
			}
		}
		save();
		refill();
		return chosen;
	}

	/** Tops the stock up in the background (a few Mojang requests, one per second). */
	void refill() {
		if(pool.size() >= size || parts.isEmpty() || !refilling.compareAndSet(false, true)) return;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				int attempts = 0;
				while(pool.size() < size && attempts++ < 30) {
					List<String> candidates = new ArrayList<>();
					while(candidates.size() < 10) {
						String candidate = candidate();
						if(iDisguise.ACCOUNTNAME.matcher(candidate).matches() && !containsIgnoreCase(candidates, candidate)
								&& !containsIgnoreCase(pool, candidate) && !"random".equalsIgnoreCase(candidate)) {
							candidates.add(candidate);
						}
					}
					for(String name : existingAccounts(candidates)) {
						if(pool.size() >= size) break;
						if(!containsIgnoreCase(pool, name) && !knownHere.test(name)) pool.add(name);
					}
					Thread.sleep(1000L);
				}
				Bukkit.getScheduler().runTask(plugin, this::save);
			} catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch(Exception e) {
				logger.log(Level.FINE, "Could not find random names", e);
			} finally {
				refilling.set(false);
			}
		});
	}

	private String candidate() {
		String a = part();
		switch(random.nextInt(7)) {
			case 0: return a;
			case 1: return a + part();
			case 2: return a + (1 + random.nextInt(99));
			case 3: return a + "_" + part();
			case 4: return a + part() + (1 + random.nextInt(9));
			case 5: return a.toLowerCase(Locale.ENGLISH) + (100 + random.nextInt(900));
			default: return a + "_";
		}
	}

	private String part() {
		String part = parts.get(random.nextInt(parts.size()));
		return random.nextInt(4) == 0 ? part.toLowerCase(Locale.ENGLISH) : part;
	}

	private static boolean containsIgnoreCase(List<String> list, String value) {
		synchronized(list) {
			for(String entry : list) {
				if(entry.equalsIgnoreCase(value)) return true;
			}
		}
		return false;
	}

	/** The names (as Mojang writes them) that are real Minecraft accounts. */
	@SuppressWarnings("deprecation")
	private List<String> existingAccounts(List<String> names) throws Exception {
		JsonArray body = new JsonArray();
		for(String name : names) body.add(new com.google.gson.JsonPrimitive(name));
		JsonElement response = null;
		for(String url : new String[] {"https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname",
				"https://api.mojang.com/profiles/minecraft"}) {
			try {
				response = post(url, body.toString());
				if(response != null) break;
			} catch(Exception e) {
				logger.log(Level.FINE, "Name lookup failed at " + url, e);
			}
		}
		List<String> result = new ArrayList<>();
		if(response != null && response.isJsonArray()) {
			for(JsonElement element : response.getAsJsonArray()) {
				if(element.isJsonObject() && element.getAsJsonObject().has("name")) {
					result.add(element.getAsJsonObject().get("name").getAsString());
				}
			}
		}
		return result;
	}

	@SuppressWarnings("deprecation")
	private static JsonElement post(String url, String json) throws Exception {
		HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("User-Agent", "iDisguise");
		connection.setDoOutput(true);
		try(OutputStream output = connection.getOutputStream()) {
			output.write(json.getBytes(StandardCharsets.UTF_8));
		}
		if(connection.getResponseCode() != 200) return null;
		try(InputStream input = connection.getInputStream()) {
			return new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8));
		}
	}

	private void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		synchronized(pool) {
			yaml.set("names", new ArrayList<>(pool));
		}
		try {
			yaml.save(file);
		} catch(Exception e) {
			logger.log(Level.WARNING, "Could not save random-names.yml", e);
		}
	}

}
