package de.luisagrether.idisguise.player;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

/**
 * SkinsRestorer 15: the skin a player has on this server.
 * Called by reflection (the API jar targets a newer Java than iDisguise is built with).
 */
final class SkinsRestorerSkins {

	private SkinsRestorerSkins() {}

	/** {value, signature} of the skin SkinsRestorer gives this player, or null. */
	static String[] find(UUID uuid, String name) throws Exception {
		Class<?> provider = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider");
		Object api = provider.getMethod("get").invoke(null);
		// Methods are looked up on the API interfaces: the implementation classes aren't public.
		Object storage = Class.forName("net.skinsrestorer.api.SkinsRestorer").getMethod("getPlayerStorage").invoke(api);
		Method getSkin = Class.forName("net.skinsrestorer.api.storage.PlayerStorage").getMethod("getSkinForPlayer", UUID.class, String.class);
		Optional<?> property = (Optional<?>)getSkin.invoke(storage, uuid, name);
		if(property == null || !property.isPresent()) return null;
		Object skin = property.get();
		Class<?> skinProperty = Class.forName("net.skinsrestorer.api.property.SkinProperty");
		String value = (String)skinProperty.getMethod("getValue").invoke(skin);
		String signature = (String)skinProperty.getMethod("getSignature").invoke(skin);
		return value == null ? null : new String[] {value, signature};
	}

	private static Object applier() throws Exception {
		Class<?> api = Class.forName("net.skinsrestorer.api.SkinsRestorer");
		Object instance = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider").getMethod("get").invoke(null);
		return api.getMethod("getSkinApplier", Class.class).invoke(instance, Player.class);
	}

	/**
	 * Shows this skin on the player the way /skin does (everyone sees it, also the player in F5),
	 * without saving it as the player's skin.
	 */
	static void show(Player player, String value, String signature) throws Exception {
		Class<?> skinProperty = Class.forName("net.skinsrestorer.api.property.SkinProperty");
		Object property = skinProperty.getMethod("of", String.class, String.class).invoke(null, value, signature);
		Class.forName("net.skinsrestorer.api.property.SkinApplier").getMethod("applySkin", Object.class, skinProperty)
				.invoke(applier(), player, property);
	}

	/** Puts the player's own (saved) skin back. May ask Mojang: call off the main thread. */
	static void restore(Player player) throws Exception {
		Class.forName("net.skinsrestorer.api.property.SkinApplier").getMethod("applySkin", Object.class)
				.invoke(applier(), player);
	}

}
