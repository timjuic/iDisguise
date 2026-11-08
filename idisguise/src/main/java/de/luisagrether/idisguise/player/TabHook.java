package de.luisagrether.idisguise.player;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * TAB (5.x): shows the fake rank through TAB's temporary group (tab prefix, nametag prefix,
 * sorting - exactly like a real member of that group) and the fake name in TAB's tab format.
 * Uses reflection, so iDisguise doesn't need TAB to start.
 */
final class TabHook {

	private final Plugin plugin;
	private final Method getInstance;
	private final Method getPlayer;
	private final Method isLoaded;
	private final Method setTemporaryGroup;
	private final Method getTabListFormatManager;
	private final Method setName;
	private final Method hasTemporaryGroup;
	private final Method getGroup;
	// Players TAB hasn't loaded, already reported once (no console spam).
	private final java.util.Set<UUID> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private TabHook(Plugin plugin) throws ReflectiveOperationException {
		this.plugin = plugin;
		Class<?> tabApi = Class.forName("me.neznamy.tab.api.TabAPI");
		Class<?> tabPlayer = Class.forName("me.neznamy.tab.api.TabPlayer");
		Class<?> formatManager = Class.forName("me.neznamy.tab.api.tablist.TabListFormatManager");
		getInstance = tabApi.getMethod("getInstance");
		getPlayer = tabApi.getMethod("getPlayer", UUID.class);
		isLoaded = tabPlayer.getMethod("isLoaded");
		setTemporaryGroup = tabPlayer.getMethod("setTemporaryGroup", String.class);
		getTabListFormatManager = tabApi.getMethod("getTabListFormatManager");
		setName = formatManager.getMethod("setName", tabPlayer, String.class);
		hasTemporaryGroup = tabPlayer.getMethod("hasTemporaryGroup");
		getGroup = tabPlayer.getMethod("getGroup");
	}

	static TabHook create(Plugin plugin) {
		if(Bukkit.getPluginManager().getPlugin("TAB") == null) return null;
		try {
			return new TabHook(plugin);
		} catch(Throwable t) {
			plugin.getLogger().warning("TAB was found, but its API doesn't match TAB 5. Fake ranks won't show in the tab list / nametags.");
			return null;
		}
	}

	/** Re-applies the disguise rank when TAB lost it (e.g. after /tab reload). */
	void ensure(Player player, String group, String name) {
		if(group == null || !player.isOnline()) return;
		try {
			Object tabPlayer = getPlayer.invoke(getInstance.invoke(null), player.getUniqueId());
			if(tabPlayer == null || !(Boolean)isLoaded.invoke(tabPlayer)) {
				notLoaded(player);
				return;
			}
			// Missing (e.g. after /tab reload) or a different group: put the disguise rank back.
			if(!(Boolean)hasTemporaryGroup.invoke(tabPlayer) || !group.equalsIgnoreCase(String.valueOf(getGroup.invoke(tabPlayer)))) {
				apply(player, group, name);
			}
		} catch(Throwable t) {
			// TAB is reloading: the next check tries again
		}
	}

	/**
	 * Applies (group != null) or resets (group == null) the fake look. TAB loads players a moment
	 * after they join, so this retries for a few seconds.
	 */
	void apply(Player player, String group, String name) {
		apply(player, group, name, 0);
	}

	private void apply(Player player, String group, String name, int attempt) {
		if(!player.isOnline()) return;
		try {
			Object api = getInstance.invoke(null);
			Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
			if(tabPlayer == null || !(Boolean)isLoaded.invoke(tabPlayer)) {
				if(attempt < 20) {
					Bukkit.getScheduler().runTaskLater(plugin, () -> apply(player, group, name, attempt + 1), 10L);
				} else {
					notLoaded(player);
				}
				return;
			}
			reported.remove(player.getUniqueId());
			setTemporaryGroup.invoke(tabPlayer, group);
			Object formatManager = getTabListFormatManager.invoke(api);
			if(formatManager != null) {
				setName.invoke(formatManager, tabPlayer, name);
			}
		} catch(Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			plugin.getLogger().warning("Could not update TAB for " + player.getName() + ": " + cause);
		}
	}

	/** TAB hasn't loaded this player: the fake rank can't show in tab / nametag yet (retried every 30 s). */
	private void notLoaded(Player player) {
		if(reported.add(player.getUniqueId())) {
			plugin.getLogger().warning("TAB has not loaded " + player.getName() + " (no tab formatting / sidebar from TAB for them), so the"
					+ " disguise rank can't show in tab and above the head yet. iDisguise retries every 30 seconds; /tab reload loads everyone again.");
		}
	}

}
