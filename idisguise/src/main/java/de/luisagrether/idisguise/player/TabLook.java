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

	TabLook() {
		File groupsFile = new File(Bukkit.getWorldContainer(), "plugins/TAB/groups.yml");
		groups = groupsFile.isFile() ? YamlConfiguration.loadConfiguration(groupsFile) : new YamlConfiguration();
		File configFile = new File(Bukkit.getWorldContainer(), "plugins/TAB/config.yml");
		if(configFile.isFile()) {
			ConfigurationSection section = YamlConfiguration.loadConfiguration(configFile).getConfigurationSection("placeholder-output-replacements");
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

	/** TAB tab prefix of a group (for %idisguise_tabprefix%), raw with & codes. */
	String tabPrefix(String group, String world) {
		return property(group, world, "tabprefix");
	}

	Look look(Player viewer, Player target, String group) {
		return look(viewer, target, group, null, null);
	}

	/** factions + owner: the faction placeholders show that player's faction instead of the target's. */
	Look look(Player viewer, Player target, String group, FactionsHook factions, java.util.UUID owner) {
		String world = target.getWorld().getName();
		String name = property(group, world, "customtabname");
		if(name.isEmpty()) name = target.getName();
		String tabLine = fill(owner(property(group, world, "tabprefix") + name + property(group, world, "tabsuffix"), viewer, factions, owner), viewer, target);
		return new Look(tabLine, fill(owner(property(group, world, "tagprefix"), viewer, factions, owner), viewer, target),
				fill(owner(property(group, world, "tagsuffix"), viewer, factions, owner), viewer, target));
	}

	private static String owner(String text, Player viewer, FactionsHook factions, java.util.UUID owner) {
		if(factions == null || owner == null || !text.contains("factionsuuid_")) return text;
		if(text.contains("%rel_factionsuuid_relation_color%")) text = text.replace("%rel_factionsuuid_relation_color%", factions.relationColor(viewer, owner));
		if(text.contains("%factionsuuid_faction_name%")) text = text.replace("%factionsuuid_faction_name%", factions.factionName(owner));
		return text;
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
	}

}
