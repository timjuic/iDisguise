package de.luisagrether.idisguise.player;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

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

}
