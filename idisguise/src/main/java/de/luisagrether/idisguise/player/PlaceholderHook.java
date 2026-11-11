package de.luisagrether.idisguise.player;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * PlaceholderAPI (only loaded when it is installed).
 * %idisguise_name% = the name other players see (the fake name while disguised, else the real one).
 * Use it instead of %player_name% where a disguised player's real name must not show,
 * e.g. InteractiveChat's "/msg %player_name%" click action.
 * %idisguise_tabprefix% = the TAB tab prefix of the rank others see (the disguise rank while disguised).
 * %idisguise_swap_<placeholder>% = that placeholder with real names of disguised players swapped (e.g. a combat enemy).
 */
final class PlaceholderHook extends PlaceholderExpansion implements me.clip.placeholderapi.expansion.Relational {

	private final PlayerDisguiseManager manager;

	private PlaceholderHook(PlayerDisguiseManager manager) {
		this.manager = manager;
	}

	private static PlaceholderHook registered;

	static void register(PlayerDisguiseManager manager) {
		// After a reload (PlugMan) the old copy may still be registered: it goes first.
		try {
			java.util.Optional<PlaceholderExpansion> old = me.clip.placeholderapi.PlaceholderAPIPlugin.getInstance()
					.getLocalExpansionManager().findExpansionByIdentifier("idisguise");
			if(old.isPresent()) old.get().unregister();
		} catch(Throwable ignored) {
		}
		registered = new PlaceholderHook(manager);
		registered.register();
	}

	/** The plugin stops: the placeholders go with it (a reload registers them again). */
	static void unregisterNow() {
		if(registered == null) return;
		try {
			registered.unregister();
		} catch(Throwable ignored) {
		}
		registered = null;
	}

	/** The DeluxeTags tag as it appears in chat, or null. */
	/** TAB's own %tab_tabprefix% for the player, or null when TAB's PlaceholderAPI expansion is off. */
	static String tabTabPrefix(Player player) {
		String value = PlaceholderAPI.setPlaceholders(player, "%tab_tabprefix%");
		return value == null || value.contains("%tab_tabprefix%") ? null : value;
	}

	static String chatTag(Player player) {
		String tag = PlaceholderAPI.setPlaceholders(player, "%deluxetags_tag%");
		return tag == null || tag.contains("%deluxetags_tag%") ? null : tag;
	}

	/** The same for a player who is offline (only when DeluxeTags can answer for offline players). */
	static String chatTag(OfflinePlayer player) {
		String tag = PlaceholderAPI.setPlaceholders(player, "%deluxetags_tag%");
		return tag == null || tag.contains("%deluxetags_tag%") ? null : tag;
	}

	@Override
	public String getIdentifier() {
		return "idisguise";
	}

	@Override
	public String getAuthor() {
		return "LuisaGrether";
	}

	@Override
	public String getVersion() {
		return "1";
	}

	@Override
	public boolean persist() {
		return true;
	}

	/**
	 * Never throws: other plugins (TAB, InteractiveChat) ask for these all the time, often from their own
	 * threads. On any error the answer is the plain value a player without a disguise would get.
	 */
	@Override
	public String onPlaceholderRequest(Player player, String params) {
		if(params == null) return null;
		try {
			if("name".equalsIgnoreCase(params)) return manager.visibleName(player);
			if("tabprefix".equalsIgnoreCase(params)) return manager.realTabPrefix(player);
			if("ownerfaction".equalsIgnoreCase(params)) return manager.ownerFaction(player);
			if("vanishsuffix".equalsIgnoreCase(params)) return manager.vanishSuffix(player);
			// %idisguise_swap_<placeholder>%: that placeholder with disguised players' real names swapped for
			// the fake ones, e.g. %idisguise_swap_combatlogx_current_enemy_name%.
			if(params.length() > 5 && params.regionMatches(true, 0, "swap_", 0, 5)) {
				String inner = params.substring(5);
				if(inner.regionMatches(true, 0, "swap_", 0, 5)) return "";
				return manager.swapNames(PlaceholderAPI.setPlaceholders(player, "%" + inner + "%"));
			}
			return null;
		} catch(Throwable t) {
			manager.debug("Placeholder %idisguise_" + params + "% failed", t);
			return fallback(player, params);
		}
	}

	private String fallback(Player player, String params) {
		try {
			if(player == null) return "";
			if("name".equalsIgnoreCase(params)) {
				PlayerDisguise disguise = manager.get(player.getUniqueId());
				return disguise != null ? disguise.getFakeName() : player.getName();
			}
			if("tabprefix".equalsIgnoreCase(params)) {
				String own = tabTabPrefix(player);
				return own != null ? own : "";
			}
			if(params.length() > 5 && params.regionMatches(true, 0, "swap_", 0, 5)) {
				return PlaceholderAPI.setPlaceholders(player, "%" + params.substring(5) + "%");
			}
		} catch(Throwable ignored) {
		}
		return "";
	}

	/** %rel_idisguise_ownercolor%: used by the nametag of a disguise that shows a real player's faction. */
	@Override
	public String onPlaceholderRequest(Player one, Player two, String identifier) {
		try {
			if("ownercolor".equalsIgnoreCase(identifier)) return manager.ownerColor(one, two);
		} catch(Throwable t) {
			manager.debug("Placeholder %rel_idisguise_" + identifier + "% failed", t);
			return "";
		}
		return null;
	}

}
