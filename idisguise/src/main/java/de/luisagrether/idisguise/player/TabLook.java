package de.luisagrether.idisguise.player;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * How TAB would show a player with their REAL rank: groups.yml (tabprefix, customtabname, tabsuffix,
 * tagprefix, tagsuffix, _DEFAULT_ and per-world) and the placeholder output replacements from TAB's
 * config.yml. Used for staff with /disguise reveal on (TAB itself only knows the disguise).
 * Placeholders are filled through PlaceholderAPI; call on the main thread.
 */
final class TabLook {

	/** One player as one viewer sees them. */
	static final class Look {
		final String tabLine;
		final String tagPrefix;
		final String tagSuffix;

		Look(String tabLine, String tagPrefix, String tagSuffix) {
			this.tabLine = tabLine;
			this.tagPrefix = tagPrefix;
			this.tagSuffix = tagSuffix;
		}

		boolean sameTag(Look other) {
			return other != null && tagPrefix.equals(other.tagPrefix) && tagSuffix.equals(other.tagSuffix);
		}
	}

	private final YamlConfiguration groups;
	private final Map<String, Map<String, String>> replacements = new HashMap<>();
	// TAB sorting: the GROUPS list (in order) and whether names sort case-sensitively. Empty = can't copy it.
	private final java.util.List<String> sortGroups = new java.util.ArrayList<>();
	private boolean sortCaseSensitive = true;

	TabLook() {
		File groupsFile = new File(Bukkit.getWorldContainer(), "plugins/TAB/groups.yml");
		groups = groupsFile.isFile() ? YamlConfiguration.loadConfiguration(groupsFile) : new YamlConfiguration();
		File configFile = new File(Bukkit.getWorldContainer(), "plugins/TAB/config.yml");
		if(configFile.isFile()) {
			YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
			// Only "GROUPS:... then PLACEHOLDER_A_TO_Z:<name>" is copied (TAB's team name = rank position
			// as one character + the name); anything else keeps TAB's own sorting.
			java.util.List<String> types = config.getStringList("scoreboard-teams.sorting-types");
			if(types.size() == 2 && types.get(0).startsWith("GROUPS:") && types.get(1).startsWith("PLACEHOLDER_A_TO_Z:")) {
				for(String group : types.get(0).substring("GROUPS:".length()).split(",")) {
					if(!group.trim().isEmpty()) sortGroups.add(group.trim().toLowerCase(Locale.ENGLISH));
				}
			}
			sortCaseSensitive = config.getBoolean("scoreboard-teams.case-sensitive-sorting", true);
			ConfigurationSection section = config.getConfigurationSection("placeholder-output-replacements");
			if(section != null) {
				for(String placeholder : section.getKeys(false)) {
					ConfigurationSection values = section.getConfigurationSection(placeholder);
					if(values == null) continue;
					Map<String, String> map = new HashMap<>();
					for(String value : values.getKeys(false)) map.put(value, values.getString(value, ""));
					replacements.put(placeholder, map);
				}
			}
		}
	}

	/**
	 * The team name TAB would give a player of this rank and name with "GROUPS + A to Z" sorting: the
	 * rank's position in the list as a character ('0' - 1 + position, e.g. #8 = '7', #19 = 'B'; a rank
	 * not in the list comes after all of them), the name, and a letter TAB adds to keep names unique
	 * (16 characters at most). null when TAB sorts differently.
	 */
	String sortName(String group, String name) {
		if(sortGroups.isEmpty() || group == null) return null;
		int position = sortGroups.indexOf(group.toLowerCase(Locale.ENGLISH)) + 1;
		if(position == 0) position = sortGroups.size() + 1;
		String part = sortCaseSensitive ? name : name.toLowerCase(Locale.ENGLISH);
		if(part.length() > 14) part = part.substring(0, 14);
		return (char)('0' - 1 + position) + part + "A";
	}

	/**
	 * The rank's tab suffix plus PremiumVanish's [V] placeholder, for the player's own tab line while
	 * disguised; null when the rank's suffix already has it (staff ranks) or PremiumVanish isn't there.
	 */
	String ownTabSuffix(String group, String world, boolean ownPlaceholder) {
		if(group == null || Bukkit.getPluginManager().getPlugin("PremiumVanish") == null) return null;
		String suffix = property(group, world, "tabsuffix");
		if(suffix.contains("premiumvanish")) return null;
		// %idisguise_vanishsuffix%: the [V] without a second space when the suffix already ends with one.
		return suffix + (ownPlaceholder ? "%idisguise_vanishsuffix%" : "%premiumvanish_vanishsuffix%");
	}

	/** %idisguise_vanishsuffix%: PremiumVanish's [V] as TAB shows it, without its space when the rank's suffix ends with one. */
	String ownVanishSuffix(String group, Player player) {
		String vanish = fill("%premiumvanish_vanishsuffix%", player, player);
		if(group == null) return vanish;
		String suffix = fill(property(group, player.getWorld().getName(), "tabsuffix"), player, player);
		return join(suffix, vanish).substring(suffix.length());
	}

	/** text + add, without a double space where they meet (add's leading space is dropped when text ends with one). */
	static String join(String text, String add) {
		if(add.isEmpty() || !ChatColor.stripColor(text).endsWith(" ") || !ChatColor.stripColor(add).startsWith(" ")) return text + add;
		int space = add.indexOf(' ');
		return text + add.substring(0, space) + add.substring(space + 1);
	}

	/** TAB nametag suffix of a group, raw (placeholders not filled). */
	String tagSuffix(String group, String world) {
		return property(group, world, "tagsuffix");
	}

	/** TAB tab prefix of a group as the player's own sidebar shows it: placeholders filled, colours applied. */
	String tabPrefix(String group, Player player) {
		return fill(property(group, player.getWorld().getName(), "tabprefix"), player, player);
	}

	Look look(Player viewer, Player target, String group) {
		return look(viewer, target, group, null, null);
	}

	/** factions + owner: the faction placeholders show that player's faction instead of the target's. */
	Look look(Player viewer, Player target, String group, FactionsHook factions, java.util.UUID owner) {
		String world = target.getWorld().getName();
		String name = property(group, world, "customtabname");
		if(name.isEmpty()) name = target.getName();
		String tabSuffix = property(group, world, "tabsuffix");
		// Vanished (only players who may see vanished ones see this line): the [V] TAB shows for staff
		// ranks, also when the (disguise) rank's suffix has none.
		boolean addVanish = !tabSuffix.contains("premiumvanish") && isVanished(target);
		String tabLine = fill(owner(property(group, world, "tabprefix") + name + tabSuffix, viewer, factions, owner), viewer, target);
		// No double space before the [V] when the suffix ends with a space (e.g. an empty faction name).
		if(addVanish) tabLine = join(tabLine, fill("%premiumvanish_vanishsuffix%", viewer, target));
		return new Look(tabLine, fill(owner(property(group, world, "tagprefix"), viewer, factions, owner), viewer, target),
				fill(owner(property(group, world, "tagsuffix"), viewer, factions, owner), viewer, target));
	}

	/**
	 * The tab line in two steps, so it isn't built from scratch for every viewer: shared() once per player
	 * (everything that is the same for all viewers), then forViewer() per viewer (only the viewer-dependent
	 * %rel_...% placeholders, e.g. the faction relation colour). Same result as look().tabLine.
	 */
	String shared(Player target, String group, FactionsHook factions, java.util.UUID owner) {
		String world = target.getWorld().getName();
		String name = property(group, world, "customtabname");
		if(name.isEmpty()) name = target.getName();
		String tabSuffix = property(group, world, "tabsuffix");
		boolean addVanish = !tabSuffix.contains("premiumvanish") && isVanished(target);
		String text = property(group, world, "tabprefix") + name + tabSuffix;
		if(factions != null && owner != null && text.contains("%factionsuuid_faction_name%")) {
			text = text.replace("%factionsuuid_faction_name%", factions.factionName(owner));
		}
		text = fillTarget(text, target);
		if(addVanish) text = join(text, fillTarget("%premiumvanish_vanishsuffix%", target));
		return text;
	}

	String forViewer(String shared, Player viewer, Player target, FactionsHook factions, java.util.UUID owner) {
		String text = shared;
		if(factions != null && owner != null && text.contains("%rel_factionsuuid_relation_color%")) {
			text = text.replace("%rel_factionsuuid_relation_color%", factions.relationColor(viewer, owner));
		}
		if(text.contains("%rel_") && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
			try {
				text = Placeholders.relational(text, viewer, target);
			} catch(Throwable t) {
				// PlaceholderAPI broken: as it is
			}
		}
		return ChatColor.translateAlternateColorCodes('&', text);
	}

	/** Like fill(), without the viewer-dependent (relational) placeholders and without colours yet. */
	private String fillTarget(String text, Player target) {
		if(text.isEmpty()) return text;
		text = text.replace("%vault-prefix%", "%vault_prefix%").replace("%vault-suffix%", "%vault_suffix%")
				.replace("%player%", target.getName()).replace("%world%", target.getWorld().getName());
		if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
			try {
				text = Placeholders.target(text, target, replacements);
			} catch(Throwable t) {
				// PlaceholderAPI missing or broken: show the text without placeholders
			}
		}
		// Colours now, so join() sees the real text (forViewer translating again changes nothing).
		return ChatColor.translateAlternateColorCodes('&', text);
	}

	private static String owner(String text, Player viewer, FactionsHook factions, java.util.UUID owner) {
		if(factions == null || owner == null || !text.contains("factionsuuid_")) return text;
		if(text.contains("%rel_factionsuuid_relation_color%")) text = text.replace("%rel_factionsuuid_relation_color%", factions.relationColor(viewer, owner));
		if(text.contains("%factionsuuid_faction_name%")) text = text.replace("%factionsuuid_faction_name%", factions.factionName(owner));
		return text;
	}

	private static boolean isVanished(Player player) {
		for(org.bukkit.metadata.MetadataValue value : player.getMetadata("vanished")) {
			if(value.asBoolean()) return true;
		}
		return false;
	}

	/** per-world group, then group, then _DEFAULT_ (TAB's order). */
	private String property(String group, String world, String key) {
		String lower = group == null ? null : group.toLowerCase(Locale.ENGLISH);
		for(String path : new String[] {"per-world." + world + "." + lower, lower, "per-world." + world + "._DEFAULT_", "_DEFAULT_"}) {
			if(path.contains("null")) continue;
			ConfigurationSection section = section(path);
			if(section != null && section.isString(key)) return section.getString(key);
		}
		return "";
	}

	/** Group names in groups.yml may use other capitalisation than LuckPerms. */
	private ConfigurationSection section(String path) {
		ConfigurationSection exact = groups.getConfigurationSection(path);
		if(exact != null) return exact;
		int dot = path.lastIndexOf('.');
		ConfigurationSection parent = dot < 0 ? groups : groups.getConfigurationSection(path.substring(0, dot));
		String last = dot < 0 ? path : path.substring(dot + 1);
		if(parent == null) return null;
		for(String key : parent.getKeys(false)) {
			if(key.equalsIgnoreCase(last)) return parent.getConfigurationSection(key);
		}
		return null;
	}

	private String fill(String text, Player viewer, Player target) {
		if(text.isEmpty()) return text;
		// TAB's own placeholders that have a PlaceholderAPI twin.
		text = text.replace("%vault-prefix%", "%vault_prefix%").replace("%vault-suffix%", "%vault_suffix%")
				.replace("%player%", target.getName()).replace("%world%", target.getWorld().getName());
		if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
			try {
				text = Placeholders.fill(text, viewer, target, replacements);
			} catch(Throwable t) {
				// PlaceholderAPI missing or broken: show the text without placeholders
			}
		}
		return ChatColor.translateAlternateColorCodes('&', text);
	}

	/** Kept apart so servers without PlaceholderAPI never load its classes. */
	private static final class Placeholders {
		static String fill(String text, Player viewer, Player target, Map<String, Map<String, String>> replacements) {
			for(Map.Entry<String, Map<String, String>> entry : replacements.entrySet()) {
				if(!text.contains(entry.getKey())) continue;
				String value = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(target, entry.getKey());
				String replaced = entry.getValue().get(value);
				text = text.replace(entry.getKey(), replaced != null ? replaced : value);
			}
			text = me.clip.placeholderapi.PlaceholderAPI.setRelationalPlaceholders(viewer, target, text);
			return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(target, text);
		}

		/** Everything except relational placeholders (%rel_...% stays for forViewer). */
		static String target(String text, Player target, Map<String, Map<String, String>> replacements) {
			for(Map.Entry<String, Map<String, String>> entry : replacements.entrySet()) {
				if(!text.contains(entry.getKey())) continue;
				String value = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(target, entry.getKey());
				String replaced = entry.getValue().get(value);
				text = text.replace(entry.getKey(), replaced != null ? replaced : value);
			}
			return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(target, text);
		}

		static String relational(String text, Player viewer, Player target) {
			return me.clip.placeholderapi.PlaceholderAPI.setRelationalPlaceholders(viewer, target, text);
		}
	}

}
