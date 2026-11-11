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
		// Where the skin came from, for the debug line in the console.
		String source = "none";

		Skin(String value, String signature) {
			this.value = value;
			this.signature = signature;
		}
	}

	private static final Skin NONE = new Skin(null, null);

	// Skins players of this server joined with (their SkinsRestorer skin, forwarded by the proxy).
	private static final java.util.Map<UUID, Skin> JOINED = new java.util.concurrent.ConcurrentHashMap<>();

	/** A player joined with this skin: a disguise as them later shows exactly it. */
	static void remember(UUID uuid, String value, String signature) {
		if(uuid != null && value != null && !value.isEmpty()) JOINED.put(uuid, new Skin(value, signature));
	}

	/** The skin of a player of this server: their current one when online, else the one they last joined with. */
	private static Skin serverSkin(UUID uuid) {
		try {
			org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
			if(online != null) {
				for(com.comphenix.protocol.wrappers.WrappedSignedProperty property
						: com.comphenix.protocol.wrappers.WrappedGameProfile.fromPlayer(online).getProperties().get("textures")) {
					if(property.getValue() != null && !property.getValue().isEmpty()) return new Skin(property.getValue(), property.getSignature());
				}
			}
		} catch(Throwable ignored) {
		}
		return JOINED.get(uuid);
	}

	private final Logger logger;
	private final boolean skinsRestorer;
	private final Map<String, Skin> mojangCache = new ConcurrentHashMap<>();

	SkinResolver(Logger logger) {
		this.logger = logger;
		this.skinsRestorer = Bukkit.getPluginManager().getPlugin("SkinsRestorer") != null;
	}

	/** knownUuid: the player's UUID when they have played here before, else null. Never returns null. */
	Skin resolve(String name, UUID knownUuid, List<String> randomSkins) {
		StringBuilder notes = new StringBuilder();
		Skin skin = ofName(name, knownUuid, notes);
		if(skin != null) return skin;
		List<String> shuffled = new ArrayList<>(randomSkins);
		Collections.shuffle(shuffled);
		for(String randomName : shuffled) {
			skin = ofName(randomName, null, notes);
			if(skin != null) {
				Skin random = new Skin(skin.value, skin.signature);
				random.source = "random skin " + randomName + " (" + skin.source + ")";
				return random;
			}
		}
		Skin none = new Skin(null, null);
		none.source = "none found" + notes;
		return none;
	}

	private Skin ofName(String name, UUID knownUuid, StringBuilder notes) {
		// A player of this server: the skin they really use here (as they joined), not their Mojang one.
		if(knownUuid != null) {
			Skin own = serverSkin(knownUuid);
			if(own != null) {
				Skin copy = new Skin(own.value, own.signature);
				copy.source = "this server's player" + describe(copy);
				return copy;
			}
		}
		// SkinsRestorer only for players who have played here (the skin they use on this server).
		// Any other name goes straight to Mojang, which returns that account's own, signed skin.
		if(skinsRestorer && knownUuid != null) {
			try {
				Skin skin = fromSkinsRestorer(name, knownUuid);
				if(skin != null) {
					skin.source = "SkinsRestorer" + describe(skin);
					return skin;
				}
			} catch(Throwable t) {
				Throwable cause = t.getCause() != null ? t.getCause() : t;
				notes.append("; SkinsRestorer failed for ").append(name).append(": ").append(cause);
				logger.log(Level.FINE, "SkinsRestorer lookup failed for " + name, t);
			}
		}
		Skin skin = fromMojang(name);
		if(skin != null) {
			Skin mojang = new Skin(skin.value, skin.signature);
			mojang.source = "Mojang" + describe(skin);
			return mojang;
		}
		return null;
	}

	private Skin fromSkinsRestorer(String name, UUID knownUuid) throws Exception {
		UUID uuid = knownUuid != null ? knownUuid : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
		String[] property = SkinsRestorerSkins.find(uuid, name);
		return property == null ? null : new Skin(property[0], property[1]);
	}

	/** " (skin of <account>, signed)" - read from the skin data itself, for the debug line. */
	@SuppressWarnings("deprecation")
	static String describe(Skin skin) {
		String owner = "?";
		try {
			String json = new String(java.util.Base64.getDecoder().decode(skin.value), StandardCharsets.UTF_8);
			JsonElement data = new JsonParser().parse(json);
			if(data.isJsonObject() && data.getAsJsonObject().has("profileName")) {
				owner = data.getAsJsonObject().get("profileName").getAsString();
			}
		} catch(RuntimeException e) {
			owner = "unreadable";
		}
		boolean signed = skin.signature != null && !skin.signature.isEmpty();
		return " (skin of " + owner + ", " + (signed ? "signed" : "NOT signed") + ")";
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
