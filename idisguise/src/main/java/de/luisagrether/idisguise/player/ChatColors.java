package de.luisagrether.idisguise.player;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Message colour of each rank, read from EssentialsX chat.format / chat.group-formats
 * (the colour codes right before {MESSAGE}). A mod disguised as Gold must not chat in mod colours.
 */
final class ChatColors {

	private static final Pattern BEFORE_MESSAGE = Pattern.compile("((?:[&§][0-9a-fk-orA-FK-OR])*)\\{MESSAGE\\}");

	private final String defaultColor;
	private final Map<String, String> groupColors = new HashMap<>();

	ChatColors(File essentialsConfig) {
		String fallback = null;
		if(essentialsConfig.isFile()) {
			YamlConfiguration yaml = YamlConfiguration.loadConfiguration(essentialsConfig);
			fallback = colorOf(yaml.getString("chat.format"));
			ConfigurationSection groups = yaml.getConfigurationSection("chat.group-formats");
			if(groups != null) {
				for(String group : groups.getKeys(false)) {
					String color = colorOf(groups.getString(group));
					if(color != null) groupColors.put(group.toLowerCase(Locale.ENGLISH), color);
				}
			}
		}
		defaultColor = fallback;
	}

	private static String colorOf(String format) {
		if(format == null) return null;
		Matcher matcher = BEFORE_MESSAGE.matcher(format);
		return matcher.find() ? ChatColor.translateAlternateColorCodes('&', matcher.group(1)) : null;
	}

	/** Translated colour codes in front of the message for this group, or null when unknown. */
	String forGroup(String group) {
		if(group != null) {
			String color = groupColors.get(group.toLowerCase(Locale.ENGLISH));
			if(color != null) return color;
		}
		return defaultColor;
	}

}
