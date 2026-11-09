package de.luisagrether.idisguise.io;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.logging.Level;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import de.luisagrether.idisguise.iDisguise;

public class Language {

    @LastUpdated(60001)
	public String CONSOLE_USE_COMMAND = ChatColor.RED + "This command is for players only.";
	@LastUpdated(60106)
	public String ODISGUISE_CURRENTLY_DISGUISED_AS_PLAYER = c("%prefix%&e%player% &7is disguised as &e%targetSkin%&7.");
	@LastUpdated(60106)
	public String ODISGUISE_CURRENTLY_DISGUISED_AS_ENTITY = c("%prefix%&e%player% &7is disguised as &e%entityType%&7.");
	@LastUpdated(60106)
	public String ODISGUISE_CURRENTLY_NOT_DISGUISED = c("%prefix%&e%player% &7is not disguised.");
	@LastUpdated(60106)
	public String DISGUISE_TYPE_NOT_SUPPORTED = c("%prefix%&cThis disguise isn't available.");
	@LastUpdated(60106)
	public String DISGUISE_TYPE_NOT_FOUND = c("%prefix%&cDisguise type not found: &e%entityType%");
	@LastUpdated(60106)
	public String DISGUISE_NO_PERMISSION = c("%prefix%&cYou don't have permission.");
	@LastUpdated(60106)
	public String DISGUISE_EVENT_CANCELLED = c("%prefix%&cAnother plugin prevents you from disguising.");
	@LastUpdated(60106)
	public String ODISGUISE_EVENT_CANCELLED = c("%prefix%&cAnother plugin prevents &e%player% &cfrom disguising.");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_ACCOUNT_NAME_MISSING = c("%prefix%&cPlease give a name: &e/disguise <name>");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_ACCOUNT_NAME_INVALID = c("%prefix%&cThat isn't a valid Minecraft name.");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_ACCOUNT_NAME_BLACKLISTED = c("%prefix%&cThat name can't be used.");
	@LastUpdated(60106)
	public String DISGUISED_SUCCESSFULLY = c("%prefix%&7You are now disguised.");
	@LastUpdated(60106)
	public String DISGUISE_ERROR = c("%prefix%&cSomething went wrong with the disguise.");
	@LastUpdated(60106)
	public String DISGUISE_STATEMENT_ERROR = c("%prefix%&cSomething went wrong with &e%statement%&c.");
	@LastUpdated(60106)
	public String DISGUISE_STATEMENT_FORBIDDEN = c("%prefix%&cThis option isn't allowed: &e%statement%");
	@LastUpdated(60106)
	public String DISGUISE_STATEMENT_UNKNOWN = c("%prefix%&cUnknown option: &e%statement%");
	@LastUpdated(60106)
	public String UNDISGUISED_SUCCESSFULLY = c("%prefix%&7You are no longer disguised.");
	@LastUpdated(60106)
	public String UNDISGUISE_NOT_DISGUISED = c("%prefix%&cYou are not disguised.");
	@LastUpdated(60106)
	public String UNDISGUISE_OTHER_NOT_DISGUISED = c("%prefix%&e%player% &cis not disguised.");
	@LastUpdated(60106)
	public String UNDISGUISE_EVENT_CANCELLED = c("%prefix%&cAnother plugin prevents you from undisguising.");
	@LastUpdated(60106)
	public String UNDISGUISE_OTHER_EVENT_CANCELLED = c("%prefix%&cAnother plugin prevents &e%player% &cfrom undisguising.");
	@LastUpdated(60106)
	public String ODISGUISE_NO_PLAYERNAME_GIVEN = c("%prefix%&cPlease give the name of an online player.");
	@LastUpdated(60106)
	public String ODISGUISE_PLAYER_NOT_FOUND = c("%prefix%&cPlayer not found: &e%player%");
	@LastUpdated(60106)
	public String ODISGUISE_NOTIFICATION = c("%prefix%&7You were disguised by &e%sender%&7.");
	@LastUpdated(60106)
	public String UNDISGUISE_NOTIFICATION = c("%prefix%&7You were undisguised by &e%sender%&7.");

    @LastUpdated(60001)
	public String UPDATE_AVAILABLE = ChatColor.GOLD + "[iDisguise] An update is available: %version%";
	@LastUpdated(60001)
	public String UPDATE_ALREADY_DOWNLOADED = ChatColor.GOLD + "[iDisguise] Update already downloaded. Restart the server to apply.";
	@LastUpdated(60001)
	public String UPDATE_DOWNLOADING = ChatColor.GOLD + "[iDisguise] Downloading update...";
	@LastUpdated(60001)
	public String UPDATE_DOWNLOAD_SUCCEEDED = ChatColor.GOLD + "[iDisguise] Download succeeded. Restart the server to apply.";
	@LastUpdated(60001)
	public String UPDATE_DOWNLOAD_FAILED = ChatColor.RED + "[iDisguise] Download failed.";
	@LastUpdated(60001)
	public String UPDATE_OPTION = ChatColor.GOLD + "[iDisguise] You can enable automatic updates in the config file.";
	@LastUpdated(60106)
	public String MOB_SOUND_PLAYED = c("%prefix%&7Played the &e%entityType% &7sound.");
	@LastUpdated(60106)
	public String DISGUISE_ON_COOLDOWN = c("%prefix%&cPlease wait &e%seconds%s &cbefore disguising again.");
	@LastUpdated(60106)
	public String ENDERPEARL_BLOCKED_WHILE_DISGUISED = c("%prefix%&cYou can't throw ender pearls while disguised.");
	@LastUpdated(60106)
	public String BLOCK_PLACE_BLOCKED_WHILE_DISGUISED = c("%prefix%&cYou can't place blocks while disguised.");
	@LastUpdated(60106)
	public String BLOCK_BREAK_BLOCKED_WHILE_DISGUISED = c("%prefix%&cYou can't break blocks while disguised.");
	@LastUpdated(60106)
	public String DISGUISE_BLOCKED_IN_REGION = c("%prefix%&cYou can't disguise in this area.");
	@LastUpdated(60106)
	public String ODISGUISE_BLOCKED_IN_REGION = c("%prefix%&e%player% &cis in an area where disguises aren't allowed.");
	@LastUpdated(60106)
	public String DISGUISE_REVEAL = c("%prefix%&7This &e%entityType% &7is &e%player%&7.");
	@LastUpdated(60106)
	public String UNDISGUISED_IN_BLOCKED_REGION = c("%prefix%&cYou were undisguised because disguises aren't allowed in this area.");

	@LastUpdated(60100)
	public String TITLE_PLAYERS = "Disguises";
	@LastUpdated(60100)
	public String TITLE_ADMIN = "iDisguise %version%";
	@LastUpdated(60100)
	public String LIST_DISGUISED_PLAYERS = ChatColor.GOLD.toString() + ChatColor.BOLD + "Disguised players:";
	@LastUpdated(60100)
	public String LIST_DISGUISED_NOBODY = ChatColor.GRAY + " Nobody is disguised right now.";
	@LastUpdated(60100)
	public String LIST_DISGUISED_ENTRY = ChatColor.GRAY + " %player% " + ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + "%name%";
	@LastUpdated(60100)
	public String LIST_DISGUISED_STAFF = ChatColor.RED + " [Staff]";
	@LastUpdated(60100)
	public String LIST_YOUR_DISGUISES = ChatColor.GOLD.toString() + ChatColor.BOLD + "Your disguises:";
	@LastUpdated(60112)
	public String LIST_PLAYER_TYPE = c("&a&lPLAYER");
	@LastUpdated(60114)
	public String LIST_LEGEND = c("&agreen &7= available, &cred &7= no permission.");
	@LastUpdated(60126)
	public String PLAYER_DISGUISE_LOADING = c("%prefix%&7Loading disguise as &e%name%&7...");
	@LastUpdated(60106)
	public String PLAYER_DISGUISED =c("%prefix%&7You are now disguised as &e%name% &7(rank: &e%rank%&7).");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_RANK_UNKNOWN = c("%prefix%&cUnknown rank: &e%rank%&c. Ranks you can use: &e%ranks%");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_RANK_NO_PERMISSION = c("%prefix%&cYou can't use the rank &e%rank%&c.");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_NAME_ONLINE = c("%prefix%&e%name% &cis online, you can't disguise as them.");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_NAME_PLAYED_BEFORE = c("%prefix%&e%name% &chas played on this server, you can't disguise as them.");
	@LastUpdated(60106)
	public String RANDOM_NOT_READY = c("%prefix%&cNo random names are ready yet. Try again in a few seconds.");
	@LastUpdated(60100)
	public String RANK_MENU_TITLE = ChatColor.DARK_GRAY + "Rank for " + ChatColor.GOLD + "%name%";
	@LastUpdated(60101)
	public String RANK_MENU_HEADER = ChatColor.GOLD.toString() + ChatColor.BOLD + "Disguising as " + ChatColor.YELLOW + ChatColor.BOLD + "%name%";
	@LastUpdated(60101)
	public String RANK_MENU_HEADER_LORE = ChatColor.GRAY + "Pick the rank other players will see.";
	@LastUpdated(60101)
	public String RANK_MENU_RANK_LORE = ChatColor.GRAY + "Others will see you like this in chat, tab and above your head.\n\n" + ChatColor.YELLOW + "\u00bb Click to disguise";
	@LastUpdated(60101)
	public String RANK_MENU_REAL_RANK = ChatColor.YELLOW + "Real rank of %name%";
	@LastUpdated(60100)
	public String RANK_MENU_NO_RANK = ChatColor.WHITE + "No rank";
	@LastUpdated(60100)
	public String RANK_MENU_NO_RANK_LORE = ChatColor.GRAY + "Look like a player without a rank:\n " + ChatColor.GRAY + "%name%\n\n"
			+ ChatColor.YELLOW + "\u00bb Click to disguise";
	@LastUpdated(60100)
	public String RANK_MENU_CANCEL = ChatColor.RED + "Cancel";
	@LastUpdated(60100)
	public String RANK_MENU_CANCEL_LORE = ChatColor.GRAY + "Close without disguising.";
	@LastUpdated(60100)
	public String HELP_TITLE = ChatColor.GOLD.toString() + ChatColor.BOLD + "%title% " + ChatColor.DARK_GRAY + "\u00bb " + ChatColor.GRAY + "Commands";
	@LastUpdated(60100)
	public String HELP_LINE = ChatColor.GOLD + "%command% " + ChatColor.DARK_GRAY + "- " + ChatColor.GRAY + "%description%";
	@LastUpdated(60100)
	public String HELP_CMD_MOB = "Disguise as a mob";
	@LastUpdated(60100)
	public String HELP_CMD_PLAYER = "Disguise as a player";
	@LastUpdated(60100)
	public String HELP_CMD_RANDOM = "Random player disguise";
	@LastUpdated(60100)
	public String HELP_CMD_UNDISGUISE = "Remove your disguise";
	@LastUpdated(60101)
	public String HELP_CMD_LIST = "Your available disguises, and disguised players";
	@LastUpdated(60101)
	public String HELP_CMD_LIST_OWN = "Your available disguises";
	@LastUpdated(60100)
	public String HELP_CMD_ALTER = "Change your mob, e.g. setBaby()";
	@LastUpdated(60100)
	public String HELP_CMD_ODISGUISE = "Disguise another player";
	@LastUpdated(60100)
	public String HELP_CMD_UNDISGUISE_OTHER = "Undisguise another player";
	@LastUpdated(60101)
	public String HELP_ADMIN_TITLE = ChatColor.RED.toString() + ChatColor.BOLD + "Admin " + ChatColor.DARK_GRAY + "\u00bb " + ChatColor.GRAY + "Commands";
	@LastUpdated(60110)
	public String HELP_CMD_REVEAL_PLAYERS = "See disguised players as who they really are (only you)";
	@LastUpdated(60101)
	public String HELP_CMD_WHOIS = "Who is or was behind a disguise name";
	@LastUpdated(60101)
	public String HELP_CMD_HISTORY = "Every disguise a player has used";
	@LastUpdated(60125)
	public String HELP_CMD_PERMISSIONS = "Every permission of this plugin";
	@LastUpdated(60125)
	public String PERMISSIONS_TITLE = ChatColor.RED.toString() + ChatColor.BOLD + "Permissions " + ChatColor.DARK_GRAY + "\u00bb " + ChatColor.GRAY + "%title%";
	@LastUpdated(60125)
	public String PERMISSIONS_SECTION = ChatColor.YELLOW + "%section%";
	@LastUpdated(60125)
	public String PERMISSIONS_LINE = ChatColor.GOLD + "%node% " + ChatColor.DARK_GRAY + "- " + ChatColor.GRAY + "%description%";
	@LastUpdated(60128)
	public String HELP_CMD_RELOAD = "Reload config.yml, the language file and player-disguise.yml";
	@LastUpdated(60128)
	public String RELOADED = ChatColor.GREEN + "Reloaded config.yml, the language file and player-disguise.yml.";
	@LastUpdated(60128)
	public String RELOADED_RESTART = ChatColor.YELLOW + "Reloaded. Turning player disguises on or off needs a restart.";
	@LastUpdated(60125)
	public String PERMISSIONS_NODES_OFF = ChatColor.RED + "use-permission-nodes is false in config.yml: permissions are ignored, only operators can disguise.";
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_NAME_UNAVAILABLE = c("%prefix%&cYou can't use that name right now.");
	@LastUpdated(60106)
	public String UNDISGUISED_NO_ACCESS = c("%prefix%&cYour player disguise was removed because you no longer have access to it.");
	@LastUpdated(60106)
	public String REVEAL_PLAYERS_ON = c("%prefix%&aYou now see disguised players as who they really are &7(only on your screen)&a.");
	@LastUpdated(60106)
	public String REVEAL_PLAYERS_OFF = c("%prefix%&7You see player disguises again.");
	@LastUpdated(60106)
	public String WHOIS_USAGE = c("%prefix%&cUse &e/disguise whois <name>");
	@LastUpdated(60106)
	public String HISTORY_USAGE = c("%prefix%&cUse &e/disguise history <player>");
	@LastUpdated(60106)
	public String WHOIS_HEADER = c("%prefix%&7Disguise name &e%name%&7:");
	@LastUpdated(60101)
	public String WHOIS_NOW = ChatColor.GRAY + " Now: " + ChatColor.WHITE + "%player% " + ChatColor.GRAY + "(%state%)";
	@LastUpdated(60101)
	public String WHOIS_ONLINE = "online";
	@LastUpdated(60101)
	public String WHOIS_OFFLINE = "offline, still disguised";
	@LastUpdated(60101)
	public String WHOIS_NONE = ChatColor.GRAY + " Nobody has used this name.";
	@LastUpdated(60106)
	public String HISTORY_HEADER = c("%prefix%&7Disguises of &e%player%&7:");
	@LastUpdated(60101)
	public String HISTORY_NONE = ChatColor.GRAY + " No disguises found.";
	@LastUpdated(60101)
	public String LOG_ENTRY = ChatColor.GRAY + " %start% " + ChatColor.DARK_GRAY + "- " + ChatColor.GRAY + "%end%" + ChatColor.DARK_GRAY + ": "
			+ ChatColor.WHITE + "%player% " + ChatColor.GRAY + "as " + ChatColor.YELLOW + "%disguise%";
	@LastUpdated(60101)
	public String LOG_STILL_ON = "still on";
	@LastUpdated(60100)
	public String HELP_CLICK = ChatColor.GRAY + "Click to type this command";
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_RANK_OWNER_ONLINE = c("%prefix%&e%name% &cis online, so you can only use their real rank: &e/disguise %name%");
	@LastUpdated(60106)
	public String PLAYER_DISGUISE_NAME_TAKEN = c("%prefix%&cSomeone is already disguised as &e%name%&c.");
	@LastUpdated(60106)
	public String UNDISGUISED_REAL_PLAYER_JOINED = c("%prefix%&cYou were undisguised because &e%name% &cjoined the server.");
	@LastUpdated(60106)
	public String UNDISGUISED_IN_COMBAT = c("%prefix%&cYou were undisguised because you entered combat.");
	@LastUpdated(60106)
	public String COMMAND_BLOCKED_WHILE_DISGUISED = c("%prefix%&cYou can't use &e%command% &cwhile disguised as a player.");

	@LastUpdated(60112)
	public String STATUS_ACTIVE = c("&7Active disguise: %disguise%");
	@LastUpdated(60112)
	public String STATUS_NONE = c("&c&lNOT DISGUISED");
	@LastUpdated(60112)
	public String STATUS_MOB = c("&a&l%type%");
	@LastUpdated(60112)
	public String STATUS_PLAYER = c("&a&l%name% &7(%rank%)");

	/** Put in front of chat messages as %prefix%. */
	@LastUpdated(60106)
	public String PREFIX = c("&6&lDisguises &8\u00bb ");

	private static String c(String text) {
		return ChatColor.translateAlternateColorCodes('&', text);
	}

	/** Fills in %prefix% (after saveData, so language.yml keeps the placeholder). */
	public void applyPrefix() {
		try {
			for(Field field : getClass().getDeclaredFields()) {
				if(field.getType().equals(String.class) && !java.lang.reflect.Modifier.isStatic(field.getModifiers())
						&& !field.getName().equals("PREFIX")) {
					String value = (String)field.get(this);
					if(value != null && value.contains("%prefix%")) field.set(this, value.replace("%prefix%", PREFIX));
				}
			}
		} catch(IllegalAccessException e) {
			plugin.getLogger().log(Level.WARNING, "Could not apply the message prefix", e);
		}
	}

	private iDisguise plugin;
	
	public Language(iDisguise plugin) {
		this.plugin = plugin;
	}
	
	public void loadData() {
		File languageFile = new File(plugin.getDataFolder(), "language.yml");
		FileConfiguration fileConfiguration = YamlConfiguration.loadConfiguration(languageFile);
		try {
			int fileVersion = UpdateCheck.extractVersionNumber(fileConfiguration.getString("version", plugin.getNameAndVersion()));
			for(Field field : getClass().getDeclaredFields()) {
				if(field.getType().equals(String.class)) {
					if(field.isAnnotationPresent(LastUpdated.class) && field.getAnnotation(LastUpdated.class).value() > fileVersion) continue;
					
					if(fileConfiguration.isString(field.getName().toLowerCase(Locale.ENGLISH).replace('_', '-'))) {
						field.set(this, fileConfiguration.getString(field.getName().toLowerCase(Locale.ENGLISH).replace('_', '-')));
					}
				}
			}
		} catch(Exception e) {
			plugin.getLogger().log(Level.SEVERE, "An error occured while loading the language file.", e);
		}
	}
	
	public void saveData() {
		File languageFile = new File(plugin.getDataFolder(), "language.yml");
		FileConfiguration fileConfiguration = new YamlConfiguration();
		try {
			for(Field field : getClass().getDeclaredFields()) {
				if(field.getType().equals(String.class)) {
					fileConfiguration.set(field.getName().toLowerCase(Locale.ENGLISH).replace('_', '-'), field.get(this));
				}
			}
			fileConfiguration.set("version", plugin.getNameAndVersion());
			fileConfiguration.save(languageFile);
		} catch(Exception e) {
			plugin.getLogger().log(Level.SEVERE, "An error occured while saving the language file.", e);
		}
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	private @interface LastUpdated {
		int value();
	}
	
}
