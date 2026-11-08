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
    @LastUpdated(60001)
	public String CURRENTLY_DISGUISED_AS_PLAYER = ChatColor.GOLD + "You are currently disguised as player: %targetSkin%";
	@LastUpdated(60001)
	public String ODISGUISE_CURRENTLY_DISGUISED_AS_PLAYER = ChatColor.GOLD + "%player% is currently disguised as player: %targetSkin%";
    @LastUpdated(60001)
	public String CURRENTLY_DISGUISED_AS_ENTITY = ChatColor.GOLD + "You are currently disguised as: %entityType%";
	@LastUpdated(60001)
	public String ODISGUISE_CURRENTLY_DISGUISED_AS_ENTITY = ChatColor.GOLD + "%player% is currently disguised as: %entityType%";
    @LastUpdated(60001)
	public String CURRENTLY_NOT_DISGUISED = ChatColor.GOLD + "You are not disguised.";
	@LastUpdated(60001)
	public String ODISGUISE_CURRENTLY_NOT_DISGUISED = ChatColor.GOLD + "%player% is not disguised.";
    @LastUpdated(60001)
	public String DISGUISE_TYPE_NOT_SUPPORTED = ChatColor.RED + "Currently not supported!";
    @LastUpdated(60102)
	public String DISGUISE_TYPE_NOT_FOUND = ChatColor.RED + "Disguise type not found: " + ChatColor.WHITE + "%entityType%";
	@LastUpdated(60001)
	public String DISGUISE_NO_PERMISSION = ChatColor.RED + "You don't have permission.";
	@LastUpdated(60001)
	public String DISGUISE_EVENT_CANCELLED = ChatColor.RED + "Another plugin prevents you from disguising.";
	@LastUpdated(60001)
	public String ODISGUISE_EVENT_CANCELLED = ChatColor.RED + "Another plugin prevents %player% from disguising.";
    @LastUpdated(60001)
	public String PLAYER_DISGUISE_ACCOUNT_NAME_MISSING = ChatColor.RED + "Please feed me an account name as well.";
    @LastUpdated(60001)
	public String PLAYER_DISGUISE_ACCOUNT_NAME_INVALID = ChatColor.RED + "This account name is invalid.";
	@LastUpdated(60002)
	public String PLAYER_DISGUISE_ACCOUNT_NAME_BLACKLISTED = ChatColor.RED + "This account name is blacklisted.";
    @LastUpdated(60001)
	public String DISGUISED_SUCCESSFULLY = ChatColor.GOLD + "Disguised successfully!";
    @LastUpdated(60001)
	public String DISGUISE_ERROR = ChatColor.RED + "Something went wrong with the disguise.";
    @LastUpdated(60001)
	public String DISGUISE_STATEMENT_ERROR = ChatColor.RED + "Something went wrong with the additional statement: %statement%";
    @LastUpdated(60001)
	public String DISGUISE_STATEMENT_FORBIDDEN = ChatColor.RED + "This statement is forbidden: %statement%";
	@LastUpdated(60001)
	public String DISGUISE_STATEMENT_UNKNOWN = ChatColor.RED + "This statement is unknown or uses invalid syntax: %statement%";
    @LastUpdated(60001)
	public String UNDISGUISED_SUCCESSFULLY = ChatColor.GOLD + "Undisguised successfully!";
    @LastUpdated(60001)
	public String UNDISGUISE_NOT_DISGUISED = ChatColor.RED + "You are not disguised.";
	@LastUpdated(60001)
	public String UNDISGUISE_OTHER_NOT_DISGUISED = ChatColor.RED + "%player% is not disguised.";
	@LastUpdated(60001)
	public String UNDISGUISE_EVENT_CANCELLED = ChatColor.RED + "Another plugin prevents you from undisguising.";
	@LastUpdated(60001)
	public String UNDISGUISE_OTHER_EVENT_CANCELLED = ChatColor.RED + "Another plugin prevents %player% from undisguising.";
	@LastUpdated(60001)
	public String ODISGUISE_NO_PLAYERNAME_GIVEN = ChatColor.RED + "Please feed me the name of an online player.";
	@LastUpdated(60001)
	public String ODISGUISE_PLAYER_NOT_FOUND = ChatColor.RED + "Can't find player: %player%";
	@LastUpdated(60001)
	public String ODISGUISE_NOTIFICATION = ChatColor.GOLD + "You were disguised by %sender%.";
	@LastUpdated(60001)
	public String UNDISGUISE_NOTIFICATION = ChatColor.GOLD + "You were undisguised by %sender%.";

	@LastUpdated(60100)
	public String HELP_PAGE_TITLE = ChatColor.DARK_GREEN + "===== " + ChatColor.BOLD + "%title%" + ChatColor.RESET + ChatColor.DARK_GREEN + " Help Page %number% =====";
	@LastUpdated(60001)
	public String HELP_DISGUISE_MOB = ChatColor.GOLD.toString() + ChatColor.BOLD + "Disguise as a mob: " + ChatColor.ITALIC + "/disguise <entity-type>\\\\"
							        + ChatColor.GOLD + "Example: " + ChatColor.ITALIC + "/disguise zombie\\\\"
							        + ChatColor.GOLD + "Use [TAB] to get a list of all entity types.\\\\"
							        + ChatColor.GOLD + "After disguising, you will be able to see your own disguise\\\\"
									+ ChatColor.GOLD + "for five seconds.";
	@LastUpdated(60100)
	public String HELP_DISGUISE_PLAYER = ChatColor.GOLD.toString() + ChatColor.BOLD + "Disguise as a player: " + ChatColor.ITALIC + "/disguise <name> [rank]\\\\"
							           + ChatColor.GOLD + "Example: " + ChatColor.ITALIC + "/disguise Marko gold\\\\"
							           + ChatColor.GOLD + "Other players see that name, skin and rank everywhere.";
    @LastUpdated(60001)
	public String HELP_DISGUISE_CHECK = ChatColor.GOLD.toString() + ChatColor.BOLD + "Check your disguise: " + ChatColor.ITALIC + "/disguise\\\\"
									  + ChatColor.GOLD + "You will be able to see your own disguise for another five\\\\"
									  + ChatColor.GOLD + "seconds.";
	@LastUpdated(60001)
	public String HELP_UNDISGUISE = ChatColor.GOLD.toString() + ChatColor.BOLD + "Undisguise: " + ChatColor.ITALIC + "/undisguise";
	@LastUpdated(60001)
	public String HELP_DISGUISE_ALTER = ChatColor.GOLD.toString() + ChatColor.BOLD + "Alter your disguise: " + ChatColor.ITALIC + "/disguise <type> <statement> ...\\\\"
									  + ChatColor.GOLD + "Example: " + ChatColor.ITALIC + "/disguise horse setColor(WHITE) setBaby()\\\\"
									  + ChatColor.GOLD + "Use [TAB] after entering the entity type to get a list of all\\\\"
									  + ChatColor.GOLD + "possible statements.";
	@LastUpdated(60001)
	public String HELP_INGAME_HELP = ChatColor.GOLD.toString() + ChatColor.BOLD + "In-game help: " + ChatColor.ITALIC + "/disguise ? [page]";
	public String HELP_DISGUISE_LIST = ChatColor.GOLD.toString() + ChatColor.BOLD + "List available disguises: " + ChatColor.ITALIC + "/disguise list";
	@LastUpdated(60001)
	public String HELP_DISGUISE_PERMISSION = ChatColor.GOLD + "By default, only server operators can use the commands.\\\\"
										   + ChatColor.GOLD + "If you enable " + ChatColor.ITALIC + "commands.use-permission-nodes" + ChatColor.RESET + ChatColor.GOLD + " in the\\\\"
										   + ChatColor.GOLD + ChatColor.ITALIC + "config.yml" + ChatColor.RESET + ChatColor.GOLD + ", disguising requires the permission node\\\\"
										   + ChatColor.GOLD + ChatColor.BOLD + "iDisguise.disguise.<entity-type>\\\\"
										   + ChatColor.GOLD + "(e.g., " + ChatColor.ITALIC + "iDisguise.disguise.SNOW_GOLEM" + ChatColor.RESET + ChatColor.GOLD + " for the snow golem)\\\\"
										   + ChatColor.GOLD + "or the wildcard " + ChatColor.BOLD + "iDisguise.disguise.*";
	@LastUpdated(60001)
	public String HELP_ODISGUISE = ChatColor.GOLD.toString() + ChatColor.BOLD + "Disguise another player: " + ChatColor.ITALIC + "/odisguise <player>\\\\"
	                             + ChatColor.GOLD + ChatColor.BOLD + ChatColor.ITALIC + "<entity-type>";
	@LastUpdated(60001)
	public String HELP_UNDISGUISE_OTHER = ChatColor.GOLD.toString() + ChatColor.BOLD + "Undisguise another player: " + ChatColor.ITALIC + "/undisguise <player>";
	@LastUpdated(60001)
	public String HELP_ODISGUISE_INFO = ChatColor.GOLD + "Player disguise and statements work as well.";
	@LastUpdated(60001)
	public String HELP_INGAME_HELP_2 = ChatColor.GOLD.toString() + ChatColor.BOLD + "In-game help: " + ChatColor.ITALIC + "/odisguise ? [page]";
	@LastUpdated(60001)
	public String HELP_ODISGUISE_PERMISSION = ChatColor.GOLD + "By default, only server operators can use these commands.\\\\"
											+ ChatColor.GOLD + "If you enable " + ChatColor.ITALIC + "commands.use-permission-nodes" + ChatColor.RESET + ChatColor.GOLD + " in the\\\\"
											+ ChatColor.GOLD + ChatColor.ITALIC + "config.yml" + ChatColor.RESET + ChatColor.GOLD + ", these commands require the permission node\\\\"
											+ ChatColor.GOLD + ChatColor.BOLD + "iDisguise.others" + ChatColor.RESET + ChatColor.GOLD + " AND the respective disguise-specific\\\\"
											+ ChatColor.GOLD + "permission node.";

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
	public String MOB_SOUND_PLAYED = ChatColor.GOLD + "♪ Played %entityType% sound";
	public String DISGUISE_ON_COOLDOWN = ChatColor.RED + "Please wait %seconds%s before disguising again.";
	public String ENDERPEARL_BLOCKED_WHILE_DISGUISED = ChatColor.RED + "You can't throw ender pearls while disguised.";
	public String BLOCK_PLACE_BLOCKED_WHILE_DISGUISED = ChatColor.RED + "You can't place blocks while disguised.";
	public String BLOCK_BREAK_BLOCKED_WHILE_DISGUISED = ChatColor.RED + "You can't break blocks while disguised.";
	public String DISGUISE_BLOCKED_IN_REGION = ChatColor.RED + "You can't disguise in this area.";
	public String ODISGUISE_BLOCKED_IN_REGION = ChatColor.RED + "%player% is in an area where disguises aren't allowed.";
	public String DISGUISE_REVEAL = ChatColor.GRAY + "This " + ChatColor.GOLD + "%entityType%" + ChatColor.GRAY + " is " + ChatColor.GOLD + "%player%" + ChatColor.GRAY + ".";
	public String UNDISGUISED_IN_BLOCKED_REGION = ChatColor.RED + "You were undisguised because disguises aren't allowed in this area.";

	@LastUpdated(60100)
	public String TITLE_PLAYERS = "Disguises";
	@LastUpdated(60100)
	public String TITLE_ADMIN = "iDisguise %version%";
	@LastUpdated(60100)
	public String LIST_TITLE = ChatColor.DARK_GREEN + "===== " + ChatColor.BOLD + "%title%" + ChatColor.RESET + ChatColor.DARK_GREEN + " =====";
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
	@LastUpdated(60100)
	public String LIST_PLAYER_TYPE = ChatColor.LIGHT_PURPLE.toString() + ChatColor.BOLD + "PLAYER";
	@LastUpdated(60100)
	public String LIST_LEGEND = ChatColor.WHITE + "white = available, " + ChatColor.GRAY + ChatColor.STRIKETHROUGH + "grey" + ChatColor.RESET + ChatColor.WHITE + " = no permission.";
	@LastUpdated(60100)
	public String PLAYER_DISGUISED = ChatColor.GOLD + "You are now disguised as " + ChatColor.YELLOW + "%name%" + ChatColor.GOLD + " (rank: " + ChatColor.YELLOW + "%rank%" + ChatColor.GOLD + ").";
	@LastUpdated(60102)
	public String PLAYER_DISGUISE_RANK_UNKNOWN = ChatColor.RED + "Unknown rank: " + ChatColor.WHITE + "%rank%" + ChatColor.RED + ". Ranks you can use: " + ChatColor.WHITE + "%ranks%";
	@LastUpdated(60102)
	public String PLAYER_DISGUISE_RANK_NO_PERMISSION = ChatColor.RED + "You can't use the rank " + ChatColor.WHITE + "%rank%" + ChatColor.RED + ".";
	@LastUpdated(60102)
	public String PLAYER_DISGUISE_NAME_ONLINE = ChatColor.WHITE + "%name%" + ChatColor.RED + " is online, you can't disguise as them.";
	@LastUpdated(60102)
	public String PLAYER_DISGUISE_NAME_PLAYED_BEFORE = ChatColor.WHITE + "%name%" + ChatColor.RED + " has played on this server, you can't disguise as them.";
	@LastUpdated(60100)
	public String HELP_DISGUISE_RANDOM = ChatColor.GOLD.toString() + ChatColor.BOLD + "Random player disguise: " + ChatColor.ITALIC + "/disguise random\\\\"
	                                   + ChatColor.GOLD + "A real player's name and skin with a random rank.";
	@LastUpdated(60100)
	public String RANDOM_NOT_READY = ChatColor.RED + "No random names are ready yet. Try again in a few seconds.";
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
	public String HELP_CMD_CHECK = "Show your current disguise";
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
	@LastUpdated(60101)
	public String HELP_CMD_REVEAL_PLAYERS = "See disguised players as who they really are (only you)";
	@LastUpdated(60101)
	public String HELP_CMD_REVEAL_MOBS = "See who is inside disguise mobs (only you)";
	@LastUpdated(60101)
	public String HELP_CMD_WHOIS = "Who is or was behind a disguise name";
	@LastUpdated(60101)
	public String HELP_CMD_HISTORY = "Every disguise a player has used";
	@LastUpdated(60101)
	public String PLAYER_DISGUISE_NAME_UNAVAILABLE = ChatColor.RED + "You can't use that name right now.";
	@LastUpdated(60101)
	public String UNDISGUISED_NO_ACCESS = ChatColor.RED + "Your player disguise was removed because you no longer have access to it.";
	@LastUpdated(60101)
	public String REVEAL_PLAYERS_ON = ChatColor.GREEN + "You now see disguised players as who they really are (only on your screen).";
	@LastUpdated(60101)
	public String REVEAL_PLAYERS_OFF = ChatColor.GOLD + "You see player disguises again.";
	@LastUpdated(60101)
	public String REVEAL_MOBS_ON = ChatColor.GREEN + "You now see the real player's name above every disguise mob (only on your screen).";
	@LastUpdated(60101)
	public String REVEAL_MOBS_OFF = ChatColor.GOLD + "Disguise mobs no longer show the real player's name.";
	@LastUpdated(60101)
	public String REVEAL_USAGE = ChatColor.RED + "Use /disguise reveal players or /disguise reveal mobs";
	@LastUpdated(60101)
	public String WHOIS_USAGE = ChatColor.RED + "Use /disguise whois <name>";
	@LastUpdated(60101)
	public String HISTORY_USAGE = ChatColor.RED + "Use /disguise history <player>";
	@LastUpdated(60101)
	public String WHOIS_HEADER = ChatColor.GOLD.toString() + ChatColor.BOLD + "Disguise name " + ChatColor.YELLOW + "%name%" + ChatColor.GOLD + ":";
	@LastUpdated(60101)
	public String WHOIS_NOW = ChatColor.GRAY + " Now: " + ChatColor.WHITE + "%player% " + ChatColor.GRAY + "(%state%)";
	@LastUpdated(60101)
	public String WHOIS_ONLINE = "online";
	@LastUpdated(60101)
	public String WHOIS_OFFLINE = "offline, still disguised";
	@LastUpdated(60101)
	public String WHOIS_NONE = ChatColor.GRAY + " Nobody has used this name.";
	@LastUpdated(60101)
	public String HISTORY_HEADER = ChatColor.GOLD.toString() + ChatColor.BOLD + "Disguises of " + ChatColor.YELLOW + "%player%" + ChatColor.GOLD + ":";
	@LastUpdated(60101)
	public String HISTORY_NONE = ChatColor.GRAY + " No disguises found.";
	@LastUpdated(60101)
	public String LOG_ENTRY = ChatColor.GRAY + " %start% " + ChatColor.DARK_GRAY + "- " + ChatColor.GRAY + "%end%" + ChatColor.DARK_GRAY + ": "
			+ ChatColor.WHITE + "%player% " + ChatColor.GRAY + "as " + ChatColor.YELLOW + "%disguise%";
	@LastUpdated(60101)
	public String LOG_STILL_ON = "still on";
	@LastUpdated(60100)
	public String HELP_CLICK = ChatColor.GRAY + "Click to type this command";
	@LastUpdated(60102)
	public String PLAYER_DISGUISE_RANK_OWNER_ONLINE = ChatColor.WHITE + "%name%" + ChatColor.RED + " is online, so you can only use their real rank: " + ChatColor.WHITE + "/disguise %name%";
	@LastUpdated(60102)
	public String PLAYER_DISGUISE_NAME_TAKEN = ChatColor.RED + "Someone is already disguised as " + ChatColor.WHITE + "%name%" + ChatColor.RED + ".";
	@LastUpdated(60102)
	public String UNDISGUISED_REAL_PLAYER_JOINED = ChatColor.RED + "You were undisguised because " + ChatColor.WHITE + "%name%" + ChatColor.RED + " joined the server.";
	@LastUpdated(60100)
	public String UNDISGUISED_IN_COMBAT = ChatColor.RED + "You were undisguised because you entered combat.";
	@LastUpdated(60100)
	public String SKIN_BLOCKED_WHILE_DISGUISED = ChatColor.RED + "You can't change your skin while disguised.";

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
