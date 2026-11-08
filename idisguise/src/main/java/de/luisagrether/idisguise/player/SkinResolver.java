package de.luisagrether.idisguise.player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Finds the skin for a fake name. Call from a background thread (it may ask Mojang).
 * Order: the skin that player has on this server (SkinsRestorer), the Mojang account with
 * that name, then a random skin from player-disguise.yml.
 */
final class SkinResolver {

	static final class Skin {
		final String value;
		final String signature;

		Skin(String value, String signature) {
			this.value = value;
			this.signature = signature;
		}
	}

	private static final Skin NONE = new Skin(null, null);

	private final Logger logger;
	private final boolean skinsRestorer;
	private final Map<String, Skin> mojangCache = new ConcurrentHashMap<>();

	SkinResolver(Logger logger) {
		this.logger = logger;
		this.skinsRestorer = Bukkit.getPluginManager().getPlugin("SkinsRestorer") != null;
	}

	/** knownUuid: the player's UUID when they have played here before, else null. Never returns null. */
	Skin resolve(String name, UUID knownUuid, List<String> randomSkins) {
		Skin skin = ofName(name, knownUuid);
		if(skin != null) return skin;
		List<String> shuffled = new ArrayList<>(randomSkins);
		Collections.shuffle(shuffled);
		for(String randomName : shuffled) {
			skin = ofName(randomName, null);
			if(skin != null) return skin;
		}
		return NONE;
	}

	private Skin ofName(String name, UUID knownUuid) {
		if(skinsRestorer) {
			try {
				Skin skin = fromSkinsRestorer(name, knownUuid);
				if(skin != null) return skin;
			} catch(Throwable t) {
				logger.log(Level.FINE, "SkinsRestorer lookup failed for " + name, t);
			}
		}
		return fromMojang(name);
	}

	private Skin fromSkinsRestorer(String name, UUID knownUuid) throws Exception {
		UUID uuid = knownUuid != null ? knownUuid : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
		String[] property = SkinsRestorerSkins.find(uuid, name);
		return property == null ? null : new Skin(property[0], property[1]);
	}

	@SuppressWarnings("deprecation")
	private Skin fromMojang(String name) {
		String key = name.toLowerCase(Locale.ENGLISH);
		Skin cached = mojangCache.get(key);
		if(cached != null) return cached == NONE ? null : cached;
		Skin skin = null;
		try {
			JsonElement profile = get("https://api.mojang.com/users/profiles/minecraft/" + name);
			if(profile != null && profile.isJsonObject() && profile.getAsJsonObject().has("id")) {
				String id = profile.getAsJsonObject().get("id").getAsString();
				JsonElement session = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
				if(session != null && session.isJsonObject() && session.getAsJsonObject().has("properties")) {
					JsonArray properties = session.getAsJsonObject().getAsJsonArray("properties");
					for(JsonElement element : properties) {
						JsonObject property = element.getAsJsonObject();
						if("textures".equals(property.get("name").getAsString())) {
							skin = new Skin(property.get("value").getAsString(),
									property.has("signature") ? property.get("signature").getAsString() : null);
						}
					}
				}
			}
		} catch(Exception e) {
			logger.log(Level.FINE, "Mojang lookup failed for " + name, e);
			return null; // not cached: maybe Mojang was just down
		}
		mojangCache.put(key, skin == null ? NONE : skin);
		return skin;
	}

	@SuppressWarnings("deprecation")
	private static JsonElement get(String url) throws Exception {
		HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);
		connection.setRequestProperty("User-Agent", "iDisguise");
		int code = connection.getResponseCode();
		if(code == 204 || code == 404) return null;
		if(code != 200) throw new IllegalStateException("HTTP " + code);
		try(InputStream input = connection.getInputStream()) {
			return new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8));
		}
	}

}
