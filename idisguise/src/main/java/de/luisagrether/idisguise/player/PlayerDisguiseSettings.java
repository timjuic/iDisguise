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
	final boolean realPlayerFaction;
	final int randomNamePool;
	final boolean visibleToOtherPlugins;
	final boolean seeOwnDisguise;
	final java.util.Set<String> commandsNotTranslated = new java.util.HashSet<>();
	final java.util.Set<String> realPlayerCommands = new java.util.HashSet<>();
	// Commands whose next word is a player ("msg", "f who"): split into words, lower case.
	final List<String[]> fakeNameCommands = new ArrayList<>();
	final java.util.Set<String> repairAfterCommands = new java.util.HashSet<>();
	final java.util.Set<String> blockedCommands = new java.util.HashSet<>();
	final boolean swapInMenus;
	final boolean swapInTitles;
	final boolean swapInHolograms;
	final boolean swapInSigns;
	private final java.util.Map<String, String> menuItems = new java.util.HashMap<>();
	final String defaultRank;
	final boolean copyRealRank;
	final List<String> randomSkins;
	final List<String> playerPermissions;
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
		realPlayerFaction = config.getBoolean("real-player-faction", true);
		randomNamePool = Math.max(5, config.getInt("random-name-pool", 40));
		visibleToOtherPlugins = config.getBoolean("visible-to-other-plugins", false);
		seeOwnDisguise = config.getBoolean("see-own-disguise", true);
		swapInMenus = config.getBoolean("name-swap.menus", true);
		swapInTitles = config.getBoolean("name-swap.titles", true);
		swapInHolograms = config.getBoolean("name-swap.holograms", true);
		swapInSigns = config.getBoolean("name-swap.signs", false);
		for(String command : config.getStringList("blocked-while-disguised")) {
			blockedCommands.add(command.toLowerCase(Locale.ENGLISH).replace("/", ""));
		}
		for(String command : config.getStringList("vanish-commands")) {
			repairAfterCommands.add(command.toLowerCase(Locale.ENGLISH).replace("/", ""));
		}
		for(String command : config.getStringList("commands-not-translated")) {
			commandsNotTranslated.add(command.toLowerCase(Locale.ENGLISH).replace("/", ""));
		}
		for(String command : config.getStringList("real-player-commands")) {
			realPlayerCommands.add(command.toLowerCase(Locale.ENGLISH).replace("/", ""));
		}
		List<String> commands = new ArrayList<>(config.getStringList("fake-name-commands"));
		// The first list (6.1.71) missed many Factions aliases. A list that still has every entry of it and none
		// of the newer ones (nobody touched it since) gets the newer defaults. Once the server's list has any of
		// them, it is left as it is (an entry the server removed stays removed).
		java.util.Set<String> have = new java.util.HashSet<>();
		for(String command : commands) if(command != null) have.add(command.toLowerCase(Locale.ENGLISH).trim());
		if(have.containsAll(FIRST_FAKE_NAME_COMMANDS) && config.getDefaults() != null) {
			List<String> newer = new ArrayList<>();
			for(String command : config.getDefaults().getStringList("fake-name-commands")) {
				String clean = command.toLowerCase(Locale.ENGLISH).trim();
				if(!FIRST_FAKE_NAME_COMMANDS.contains(clean)) newer.add(clean);
			}
			boolean anyNewer = false;
			for(String command : newer) if(have.contains(command)) anyNewer = true;
			if(!anyNewer) {
				for(String command : newer) if(have.add(command)) commands.add(command);
			}
		}
		for(String command : commands) {
			if(command == null) continue;
			String clean = command.toLowerCase(Locale.ENGLISH).replace("/", "").trim();
			if(!clean.isEmpty()) fakeNameCommands.add(clean.split(" +"));
		}
		// The longest entry wins ("mail send" before "mail").
		fakeNameCommands.sort((a, b) -> b.length - a.length);
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
		List<String> denied = new ArrayList<>();
		for(String node : config.getStringList("player-permissions")) {
			if(node != null && !node.trim().isEmpty()) denied.add(node.trim().toLowerCase(Locale.ENGLISH));
		}
		playerPermissions = Collections.unmodifiableList(denied);
		essentialsConfig = config.getString("essentials-config", "plugins/Essentials/config.yml");
		staff = new Profile(section(config, "profiles.staff"));
		players = new Profile(section(config, "profiles.players"));
	}

	private static final List<String> FIRST_FAKE_NAME_COMMANDS = java.util.Arrays.asList("msg", "tpa", "tpahere", "tpaccept",
			"tpdeny", "pay", "balance", "seen", "mail send", "ignore", "f who", "f show", "f invite", "f inv", "f deinvite", "f deinv",
			"f kick", "f mod", "f coleader", "f leader", "f promote", "f demote", "f ban", "f unban", "stats");

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
		if(item == null) item = NEW_MENU_ITEMS.get(key.toLowerCase(Locale.ENGLISH));
		// The header was a name tag before 6.1.40: still the old default = the new default.
		if("header".equalsIgnoreCase(key) && "NAME_TAG".equalsIgnoreCase(item)) item = "SKULL_ITEM:1";
		// Changed in 6.1.60 so every button looks different: the old defaults = the new defaults.
		if("back".equalsIgnoreCase(key) && "BARRIER".equalsIgnoreCase(item)) item = "ARROW";
		if("page".equalsIgnoreCase(key) && "ARROW".equalsIgnoreCase(item)) item = "PAPER";
		if("no-tag".equalsIgnoreCase(key) && "PAPER".equalsIgnoreCase(item)) item = "EMPTY_MAP";
		return item != null ? item : "STAINED_CLAY:0";
	}

	// Menu items added after the first version: servers whose file doesn't list them yet get these.
	private static final java.util.Map<String, String> NEW_MENU_ITEMS = new java.util.HashMap<>();
	static {
		NEW_MENU_ITEMS.put("tag", "NAME_TAG");
		NEW_MENU_ITEMS.put("tag-entry", "NAME_TAG");
		NEW_MENU_ITEMS.put("no-tag", "EMPTY_MAP");
		NEW_MENU_ITEMS.put("back", "ARROW");
		NEW_MENU_ITEMS.put("page", "PAPER");
		NEW_MENU_ITEMS.put("random", "NETHER_STAR");
		NEW_MENU_ITEMS.put("invis", "EYE_OF_ENDER");
		NEW_MENU_ITEMS.put("player-permissions", "IRON_CHESTPLATE");
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
