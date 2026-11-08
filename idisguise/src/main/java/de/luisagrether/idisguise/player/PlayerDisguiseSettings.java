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
	// All fake ranks: player ranks first, then staff ranks.
	final List<String> ranks;
	final List<String> playerRanks;
	final List<String> staffRanks;
	final boolean blockKnownNamesForPlayers;
	final int cooldownSeconds;
	final int randomNamePool;
	final boolean visibleToOtherPlugins;
	final java.util.Set<String> commandsNotTranslated = new java.util.HashSet<>();
	final java.util.Set<String> repairAfterCommands = new java.util.HashSet<>();
	final java.util.Set<String> blockedCommands = new java.util.HashSet<>();
	final boolean swapInMenus;
	final boolean swapInTitles;
	final boolean swapInHolograms;
	final boolean swapInSigns;
	final boolean swapInScoreboard;
	private final java.util.Map<String, String> menuItems = new java.util.HashMap<>();
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
		addMissingKeys(plugin, file);
		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		try(InputStream defaults = plugin.getResource("player-disguise.yml")) {
			if(defaults != null) {
				config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
			}
		} catch(Exception e) {
			plugin.getLogger().log(Level.WARNING, "Could not read the default player-disguise.yml", e);
		}

		enabled = config.getBoolean("enabled", true);
		playerRanks = lowerCase(config.getStringList("player-ranks"));
		staffRanks = lowerCase(config.getStringList("staff-ranks"));
		List<String> all = new ArrayList<>(playerRanks);
		for(String rank : staffRanks) {
			if(!all.contains(rank)) all.add(rank);
		}
		ranks = Collections.unmodifiableList(all);
		blockKnownNamesForPlayers = config.getBoolean("block-known-names-for-players", true);
		cooldownSeconds = Math.max(0, config.getInt("cooldown-seconds", 5));
		randomNamePool = Math.max(5, config.getInt("random-name-pool", 40));
		visibleToOtherPlugins = config.getBoolean("visible-to-other-plugins", false);
		swapInMenus = config.getBoolean("name-swap.menus", true);
		swapInTitles = config.getBoolean("name-swap.titles", true);
		swapInHolograms = config.getBoolean("name-swap.holograms", true);
		swapInSigns = config.getBoolean("name-swap.signs", false);
		swapInScoreboard = config.getBoolean("name-swap.scoreboard", false);
		for(String command : config.getStringList("blocked-while-disguised")) {
			blockedCommands.add(command.toLowerCase(Locale.ENGLISH).replace("/", ""));
		}
		for(String command : config.getStringList("vanish-commands")) {
			repairAfterCommands.add(command.toLowerCase(Locale.ENGLISH).replace("/", ""));
		}
		for(String command : config.getStringList("commands-not-translated")) {
			commandsNotTranslated.add(command.toLowerCase(Locale.ENGLISH).replace("/", ""));
		}
		ConfigurationSection items = config.getConfigurationSection("menu-items");
		if(items != null) {
			for(String key : items.getKeys(false)) {
				menuItems.put(key.toLowerCase(Locale.ENGLISH), items.getString(key));
			}
		}
		String configuredDefault = config.getString("default-rank", "default");
		defaultRank = configuredDefault == null || configuredDefault.isEmpty() ? null : configuredDefault.toLowerCase(Locale.ENGLISH);
		copyRealRank = config.getBoolean("copy-real-rank", true);
		randomSkins = Collections.unmodifiableList(new ArrayList<>(config.getStringList("random-skins")));
		essentialsConfig = config.getString("essentials-config", "plugins/Essentials/config.yml");
		staff = new Profile(section(config, "profiles.staff"));
		players = new Profile(section(config, "profiles.players"));
	}

	private static List<String> lowerCase(List<String> list) {
		List<String> result = new ArrayList<>();
		for(String entry : list) {
			String lower = entry.toLowerCase(Locale.ENGLISH);
			if(!result.contains(lower)) result.add(lower);
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * New options of a newer version are appended to the server's file with their comments,
	 * so the file never has to be deleted to get them. The server's own values stay untouched.
	 */
	private static void addMissingKeys(JavaPlugin plugin, File file) {
		try(InputStream input = plugin.getResource("player-disguise.yml")) {
			if(input == null) return;
			String defaults = new String(readAll(input), StandardCharsets.UTF_8).replace("\r\n", "\n");
			YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
			StringBuilder missing = new StringBuilder();
			List<String> comments = new ArrayList<>();
			String[] lines = defaults.split("\n", -1);
			for(int i = 0; i < lines.length; i++) {
				String line = lines[i];
				if(line.startsWith("#")) {
					comments.add(line);
					continue;
				}
				if(line.trim().isEmpty() || line.startsWith(" ") || line.startsWith("-")) {
					comments.clear();
					continue;
				}
				String key = line.contains(":") ? line.substring(0, line.indexOf(':')).trim() : line.trim();
				if(!current.contains(key)) {
					missing.append("\n");
					for(String comment : comments) missing.append(comment).append("\n");
					missing.append(line).append("\n");
					while(i + 1 < lines.length && (lines[i + 1].startsWith(" ") || lines[i + 1].startsWith("-"))) {
						missing.append(lines[++i]).append("\n");
					}
				}
				comments.clear();
			}
			if(missing.length() > 0) {
				java.nio.file.Files.write(file.toPath(), missing.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
			}
		} catch(Exception e) {
			plugin.getLogger().log(Level.WARNING, "Could not add new options to player-disguise.yml", e);
		}
	}

	private static byte[] readAll(InputStream input) throws java.io.IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int read;
		while((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
		return out.toByteArray();
	}

	/** Item of a rank (or "no-rank", "cancel", "header", "frame") in the rank menu, e.g. "GOLD_NUGGET" or "STAINED_CLAY:4". */
	String menuItem(String key) {
		String item = menuItems.get(key.toLowerCase(Locale.ENGLISH));
		if(item == null && key.toLowerCase(Locale.ENGLISH).endsWith("_temp")) {
			item = menuItems.get(key.toLowerCase(Locale.ENGLISH).substring(0, key.length() - 5));
		}
		return item != null ? item : "STAINED_CLAY:0";
	}

	boolean isStaffRank(String rank) {
		return rank != null && staffRanks.contains(rank.toLowerCase(Locale.ENGLISH));
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
