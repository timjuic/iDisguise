package de.luisagrether.idisguise.player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** player-disguise.yml. Missing keys fall back to the defaults inside the jar. */
final class PlayerDisguiseSettings {

	static final class Profile {
		final boolean replaceNameInMessages;
		final boolean commandsUseFakeName;
		final boolean fakeRankInChat;
		final boolean hideChatTag;
		final boolean showInList;
		final boolean removeInCombat;

		Profile(ConfigurationSection section) {
			replaceNameInMessages = section.getBoolean("replace-name-in-messages", true);
			commandsUseFakeName = section.getBoolean("commands-use-fake-name", true);
			fakeRankInChat = section.getBoolean("fake-rank-in-chat", true);
			hideChatTag = section.getBoolean("hide-chat-tag", true);
			showInList = section.getBoolean("show-in-list", true);
			removeInCombat = section.getBoolean("remove-in-combat", false);
		}
	}

	final boolean enabled;
	final List<String> ranks;
	final String defaultRank;
	final boolean copyRealRank;
	final List<String> randomSkins;
	final String essentialsConfig;
	final Profile staff;
	final Profile players;

	PlayerDisguiseSettings(JavaPlugin plugin) {
		File file = new File(plugin.getDataFolder(), "player-disguise.yml");
		if(!file.exists()) {
			plugin.saveResource("player-disguise.yml", false);
		}
		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		try(InputStream defaults = plugin.getResource("player-disguise.yml")) {
			if(defaults != null) {
				config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
			}
		} catch(Exception e) {
			plugin.getLogger().log(Level.WARNING, "Could not read the default player-disguise.yml", e);
		}

		enabled = config.getBoolean("enabled", true);
		List<String> rankList = new ArrayList<>();
		for(String rank : config.getStringList("ranks")) {
			rankList.add(rank.toLowerCase(Locale.ENGLISH));
		}
		ranks = Collections.unmodifiableList(rankList);
		String configuredDefault = config.getString("default-rank", "default");
		defaultRank = configuredDefault == null || configuredDefault.isEmpty() ? null : configuredDefault.toLowerCase(Locale.ENGLISH);
		copyRealRank = config.getBoolean("copy-real-rank", true);
		randomSkins = Collections.unmodifiableList(new ArrayList<>(config.getStringList("random-skins")));
		essentialsConfig = config.getString("essentials-config", "plugins/Essentials/config.yml");
		staff = new Profile(section(config, "profiles.staff"));
		players = new Profile(section(config, "profiles.players"));
	}

	private static ConfigurationSection section(YamlConfiguration config, String path) {
		ConfigurationSection section = config.getConfigurationSection(path);
		return section != null ? section : new YamlConfiguration();
	}

	/** The rank exactly as written in the list, or null when it isn't listed. */
	String findRank(String input) {
		if(input == null) return null;
		String lower = input.toLowerCase(Locale.ENGLISH);
		return ranks.contains(lower) ? lower : null;
	}

	/** A real group that may be copied: listed itself, or a "_temp" variant of a listed rank. */
	boolean isCopyableGroup(String group) {
		if(group == null) return false;
		String lower = group.toLowerCase(Locale.ENGLISH);
		return ranks.contains(lower) || (lower.endsWith("_temp") && ranks.contains(lower.substring(0, lower.length() - 5)));
	}

}
