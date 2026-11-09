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
	private final Method setTabSuffix;
	private final Method hasTemporaryGroup;
	private final Method getGroup;
	private final Method getNameTagManager;
	private final Method pauseTeamHandling;
	private final Method resumeTeamHandling;
	private final Method setSuffix;
	private final Method getSortingManager;
	private final Method forceTeamName;
	private final Method hideNameTag;
	private final Method showNameTag;
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
		Method tabSuffix = null;
		try {
			tabSuffix = formatManager.getMethod("setSuffix", tabPlayer, String.class);
		} catch(NoSuchMethodException e) {
			// older TAB: no [V] on the player's own tab line while disguised
		}
		setTabSuffix = tabSuffix;
		hasTemporaryGroup = tabPlayer.getMethod("hasTemporaryGroup");
		getGroup = tabPlayer.getMethod("getGroup");
		getNameTagManager = tabApi.getMethod("getNameTagManager");
		Class<?> nameTagManager = Class.forName("me.neznamy.tab.api.nametag.NameTagManager");
		pauseTeamHandling = nameTagManager.getMethod("pauseTeamHandling", tabPlayer);
		resumeTeamHandling = nameTagManager.getMethod("resumeTeamHandling", tabPlayer);
		Method suffix = null;
		try {
			suffix = nameTagManager.getMethod("setSuffix", tabPlayer, String.class);
		} catch(NoSuchMethodException e) {
			// older TAB: the nametag keeps the own faction
		}
		setSuffix = suffix;
		Method sortingManager = null;
		Method force = null;
		try {
			sortingManager = tabApi.getMethod("getSortingManager");
			force = Class.forName("me.neznamy.tab.api.tablist.SortingManager").getMethod("forceTeamName", tabPlayer, String.class);
		} catch(ReflectiveOperationException e) {
			plugin.getLogger().warning("This TAB version can't force sorting: disguised players may sit in the wrong place in tab.");
		}
		getSortingManager = sortingManager;
		forceTeamName = force;
		Method hide = null;
		Method show = null;
		try {
			hide = nameTagManager.getMethod("hideNameTag", tabPlayer);
			show = nameTagManager.getMethod("showNameTag", tabPlayer);
		} catch(NoSuchMethodException e) {
			plugin.getLogger().warning("This TAB version can't hide nametags: invisible players' nametags stay visible.");
		}
		hideNameTag = hide;
		showNameTag = show;
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
	/** Puts the disguise rank back if TAB lost it. Returns true when it had to (e.g. after /tab reload). */
	boolean ensure(Player player, String group, String name, String sortName) {
		if(group == null || !player.isOnline()) return false;
		try {
			Object tabPlayer = getPlayer.invoke(getInstance.invoke(null), player.getUniqueId());
			if(tabPlayer == null || !(Boolean)isLoaded.invoke(tabPlayer)) {
				notLoaded(player);
				return false;
			}
			// Missing (e.g. after /tab reload) or a different group: put the disguise rank back.
			if(!(Boolean)hasTemporaryGroup.invoke(tabPlayer) || !group.equalsIgnoreCase(String.valueOf(getGroup.invoke(tabPlayer)))) {
				apply(player, group, name, sortName);
				return true;
			}
		} catch(Throwable t) {
			// TAB is reloading: the next check tries again
		}
		return false;
	}

	/**
	 * Applies (group != null) or resets (group == null) the fake look. TAB loads players a moment
	 * after they join, so this retries for a few seconds.
	 */
	void apply(Player player, String group, String name) {
		apply(player, group, name, null, 0);
	}

	/** sortName: TAB team name to sort under (forced), null = TAB's own sorting. */
	void apply(Player player, String group, String name, String sortName) {
		apply(player, group, name, sortName, 0);
	}

	private void apply(Player player, String group, String name, String sortName, int attempt) {
		if(!player.isOnline()) return;
		try {
			Object api = getInstance.invoke(null);
			Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
			if(tabPlayer == null || !(Boolean)isLoaded.invoke(tabPlayer)) {
				if(attempt < 20) {
					Bukkit.getScheduler().runTaskLater(plugin, () -> apply(player, group, name, sortName, attempt + 1), 10L);
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
			// The sorting name, set by iDisguise: TAB built it from values up to a second old, and parts
			// of TAB kept re-sending an older team, so disguised players sat where their old name / rank was.
			if(getSortingManager != null) {
				Object sorting = getSortingManager.invoke(api);
				if(sorting != null) forceTeamName.invoke(sorting, tabPlayer, sortName);
			}
		} catch(Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			plugin.getLogger().warning("Could not update TAB for " + player.getName() + ": " + cause);
		}
	}

	/** A custom tab-list suffix for the player's own tab line (TAB fills its placeholders); null = TAB's normal one. */
	void setTabSuffix(Player player, String suffix) {
		if(setTabSuffix == null) return;
		try {
			Object api = getInstance.invoke(null);
			Object formatManager = getTabListFormatManager.invoke(api);
			Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
			if(formatManager != null && tabPlayer != null) setTabSuffix.invoke(formatManager, tabPlayer, suffix);
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not set the tab suffix of " + player.getName() + ": " + t);
		}
	}

	/** Hides or shows the player's nametag for everyone (fake invisibility). */
	void setNameTagHidden(Player player, boolean hidden) {
		Method method = hidden ? hideNameTag : showNameTag;
		if(method == null) return;
		try {
			Object api = getInstance.invoke(null);
			Object manager = getNameTagManager.invoke(api);
			Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
			if(manager != null && tabPlayer != null) method.invoke(manager, tabPlayer);
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not " + (hidden ? "hide" : "show") + " the nametag of " + player.getName() + ": " + t);
		}
	}

	/** A custom nametag suffix (TAB fills its placeholders per viewer); null = TAB's normal one. */
	void setTagSuffix(Player player, String suffix) {
		if(setSuffix == null) return;
		try {
			Object api = getInstance.invoke(null);
			Object manager = getNameTagManager.invoke(api);
			Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
			if(manager != null && tabPlayer != null) setSuffix.invoke(manager, tabPlayer, suffix);
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not set the nametag of " + player.getName() + ": " + t);
		}
	}

	/**
	 * Makes TAB remove and re-create the player's nametag team for everyone. After a rank switch TAB
	 * sometimes left the old team (old rank above the head) on other players' screens.
	 */
	void rebuildNameTag(Player player) {
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if(!player.isOnline()) return;
			try {
				Object api = getInstance.invoke(null);
				Object manager = getNameTagManager.invoke(api);
				Object tabPlayer = getPlayer.invoke(api, player.getUniqueId());
				if(manager == null || tabPlayer == null) return;
				pauseTeamHandling.invoke(manager, tabPlayer);
				Bukkit.getScheduler().runTaskLater(plugin, () -> {
					try {
						if(player.isOnline()) resumeTeamHandling.invoke(manager, tabPlayer);
					} catch(Throwable t) {
						plugin.getLogger().warning("Could not rebuild the nametag of " + player.getName() + ": " + t);
					}
				}, 2L);
			} catch(Throwable t) {
				plugin.getLogger().warning("Could not rebuild the nametag of " + player.getName() + ": " + t);
			}
		}, 2L);
	}

	/** TAB hasn't loaded this player: the fake rank can't show in tab / nametag yet (retried every 30 s). */
	private void notLoaded(Player player) {
		if(reported.add(player.getUniqueId())) {
			plugin.getLogger().warning("TAB has not loaded " + player.getName() + " (no tab formatting / sidebar from TAB for them), so the"
					+ " disguise rank can't show in tab and above the head yet. iDisguise retries every 30 seconds; /tab reload loads everyone again.");
		}
	}

}
