package de.luisagrether.idisguise.player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * FactionsUUID through reflection (iDisguise runs without it). Used when staff is disguised as a real
 * player: chat, nametag and tab show THAT player's faction, also while they are offline (Factions'
 * own placeholders only work for online players).
 */
final class FactionsHook {

	private final Method getInstance;
	private final Method getByPlayer;
	private final Method getByOfflinePlayer;
	private final Method hasFaction;
	private final Method getFaction;
	private final Method getTag;
	private final Method getChatTag;
	private final Method getChatTagTo;
	private final Method getColorTo;
	private final Method getTitle;
	private final String noFactionText;

	private FactionsHook() throws ReflectiveOperationException {
		Class<?> fplayers = Class.forName("com.massivecraft.factions.FPlayers");
		Class<?> fplayer = Class.forName("com.massivecraft.factions.FPlayer");
		Class<?> faction = Class.forName("com.massivecraft.factions.Faction");
		Class<?> participator = Class.forName("com.massivecraft.factions.iface.RelationParticipator");
		getInstance = fplayers.getMethod("getInstance");
		getByPlayer = fplayers.getMethod("getByPlayer", Player.class);
		getByOfflinePlayer = fplayers.getMethod("getByOfflinePlayer", OfflinePlayer.class);
		hasFaction = fplayer.getMethod("hasFaction");
		getFaction = fplayer.getMethod("getFaction");
		getTag = faction.getMethod("getTag");
		getChatTag = fplayer.getMethod("getChatTag");
		getChatTagTo = fplayer.getMethod("getChatTag", fplayer);
		getColorTo = fplayer.getMethod("getColorTo", participator);
		getTitle = fplayer.getMethod("getTitle");
		String noFaction = "";
		try {
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object tl = Enum.valueOf((Class<Enum>)Class.forName("com.massivecraft.factions.util.TL"), "NOFACTION_PREFIX");
			noFaction = String.valueOf(tl);
		} catch(Throwable t) {
			// older Factions: no text for "no faction"
		}
		noFactionText = noFaction;
	}

	static FactionsHook create(Logger logger) {
		if(!Bukkit.getPluginManager().isPluginEnabled("Factions")) return null;
		try {
			return new FactionsHook();
		} catch(Throwable t) {
			logger.warning("Factions found, but its API could not be read (" + t + "). Disguises show your own faction.");
			return null;
		}
	}

	private Object fplayer(Player player) throws ReflectiveOperationException {
		return getByPlayer.invoke(getInstance.invoke(null), player);
	}

	private Object fplayer(UUID uuid) throws ReflectiveOperationException {
		return getByOfflinePlayer.invoke(getInstance.invoke(null), Bukkit.getOfflinePlayer(uuid));
	}

	/** %factionsuuid_faction_name% of that player, online or not. */
	String factionName(UUID owner) {
		try {
			Object player = fplayer(owner);
			if(player == null) return noFactionText;
			return (Boolean)hasFaction.invoke(player) ? String.valueOf(getTag.invoke(getFaction.invoke(player))) : noFactionText;
		} catch(Throwable t) {
			return "";
		}
	}

	/** %rel_factionsuuid_relation_color% between the viewer and that player. */
	String relationColor(Player viewer, UUID owner) {
		try {
			Object you = fplayer(viewer);
			Object them = fplayer(owner);
			if(you == null || them == null) return "";
			Object color = getColorTo.invoke(you, them);
			return color == null ? "" : color.toString();
		} catch(Throwable t) {
			return "";
		}
	}

	/**
	 * Chat with the faction tag of that player, built and sent the way Factions does it (per reader, in
	 * their relation colour). Returns false when Factions doesn't handle chat tags here or something fails;
	 * then the chat goes on as normal.
	 */
	boolean sendChat(AsyncPlayerChatEvent event, String eventFormat, UUID owner) {
		try {
			Class<?> pluginClass = Class.forName("com.massivecraft.factions.FactionsPlugin");
			Object plugin = pluginClass.getMethod("getInstance").invoke(null);
			Object chat = call(call(call(plugin, "conf"), "factions"), "chat");
			if(!(Boolean)call(chat, "isControlChat")) return false;
			Object worldUtil = call(plugin, "worldUtil");
			if(!(Boolean)worldUtil.getClass().getMethod("isEnabled", org.bukkit.World.class).invoke(worldUtil, event.getPlayer().getWorld())) return false;
			if((Boolean)call(plugin, "isAnotherPluginHandlingChat")) return false;

			Object me = fplayer(owner);
			if(me == null) return false;
			int insert = (Integer)call(chat, "getTagInsertIndex");
			boolean padBefore = (Boolean)call(chat, "isTagPadBefore");
			boolean padAfter = (Boolean)call(chat, "isTagPadAfter");
			String replace = (String)call(chat, "getTagReplaceString");
			String after = (String)call(chat, "getTagInsertAfterString");
			String before = (String)call(chat, "getTagInsertBeforeString");
			String format = eventFormat;
			if(!replace.isEmpty() && format.contains(replace)) {
				if(format.contains("[FACTION_TITLE]")) format = format.replace("[FACTION_TITLE]", String.valueOf(getTitle.invoke(me)));
				insert = format.indexOf(replace);
				format = format.replace(replace, "");
				padBefore = false;
				padAfter = false;
			} else if(!after.isEmpty() && format.contains(after)) {
				insert = format.indexOf(after) + after.length();
			} else if(!before.isEmpty() && format.contains(before)) {
				insert = format.indexOf(before);
			} else if(!(Boolean)call(chat, "isAlwaysShowChatTag")) {
				return false;
			}
			insert = Math.max(0, Math.min(insert, format.length()));
			String plainTag = String.valueOf(getChatTag.invoke(me));
			String start = format.substring(0, insert) + (padBefore && !plainTag.isEmpty() ? " " : "");
			String end = (padAfter && !plainTag.isEmpty() ? " " : "") + format.substring(insert);
			String plainFormat = start + escape(plainTag.trim()) + end;
			if((Boolean)call(chat, "isTagRelationColored")) {
				Player talking = event.getPlayer();
				String message = event.getMessage();
				for(Player reader : new ArrayList<>(event.getRecipients())) {
					Object you = fplayer(reader);
					String tag = you == null ? plainTag : String.valueOf(getChatTagTo.invoke(me, you));
					reader.sendMessage(String.format(start + escape(tag.trim()) + end, talking.getDisplayName(), message));
				}
				// Factions does the same: everyone already has their copy, the event only logs it.
				event.getRecipients().clear();
			}
			event.setFormat(plainFormat);
			return true;
		} catch(Throwable t) {
			return false;
		}
	}

	/** A faction tag with % in it would break String.format. */
	private static String escape(String text) {
		return text.replace("%", "%%");
	}

	private static Object call(Object target, String method) throws ReflectiveOperationException {
		return target.getClass().getMethod(method).invoke(target);
	}

}
