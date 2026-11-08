package de.luisagrether.idisguise.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.google.common.collect.Multimap;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;

/**
 * Rewrites what other players' clients are told about a disguised player (Minecraft 1.8 packets):
 *  - tab list entry: fake name + fake skin (the client takes the name above the head and the skin from it)
 *  - scoreboard teams (TAB nametags / sorting): real name -> fake name
 *  - chat: real name -> fake name in every message (death messages, /f who, other plugins)
 *  - tab-complete: fake names instead of real ones
 * The server itself never changes: the player keeps their real name, UUID, health and data.
 * Runs on network threads, so it only reads the manager's thread-safe state.
 */
final class PacketRewriter extends PacketAdapter {

	private final PlayerDisguiseManager manager;

	PacketRewriter(Plugin plugin, PlayerDisguiseManager manager) {
		super(plugin, ListenerPriority.HIGHEST,
				PacketType.Play.Server.PLAYER_INFO,
				PacketType.Play.Server.SCOREBOARD_TEAM,
				PacketType.Play.Server.CHAT,
				PacketType.Play.Server.TAB_COMPLETE,
				PacketType.Play.Server.NAMED_ENTITY_SPAWN,
				PacketType.Play.Server.OPEN_WINDOW,
				PacketType.Play.Server.WINDOW_ITEMS,
				PacketType.Play.Server.SET_SLOT,
				PacketType.Play.Server.TITLE,
				PacketType.Play.Server.ENTITY_METADATA,
				PacketType.Play.Server.SPAWN_ENTITY_LIVING,
				PacketType.Play.Server.UPDATE_SIGN,
				PacketType.Play.Server.SCOREBOARD_OBJECTIVE,
				PacketType.Play.Client.TAB_COMPLETE);
		this.manager = manager;
	}

	void register() {
		ProtocolLibrary.getProtocolManager().addPacketListener(this);
	}

	void unregister() {
		ProtocolLibrary.getProtocolManager().removePacketListener(this);
	}

	@Override
	public void onPacketReceiving(PacketEvent event) {
		if(event.getPacketType() == PacketType.Play.Client.TAB_COMPLETE) {
			String text = event.getPacket().getStrings().readSafely(0);
			if(text != null) manager.setLastTabInput(event.getPlayer().getUniqueId(), text);
		}
	}

	@Override
	public void onPacketSending(PacketEvent event) {
		if(event.isCancelled() || manager.isEmpty()) return;
		if(event.getPacketType() == PacketType.Play.Server.SCOREBOARD_TEAM && event.getPlayer() != null) {
			try {
				manager.trackTeam(event.getPlayer().getUniqueId(), event.getPacket());
			} catch(RuntimeException e) {
				manager.packetError("SCOREBOARD_TEAM (tracking)", e);
			}
		}
		// Staff with "/disguise reveal players" on see everyone as they really are, with the real rank.
		if(event.getPlayer() != null && manager.isRevealingPlayers(event.getPlayer().getUniqueId())) {
			try {
				if(event.getPacketType() == PacketType.Play.Server.PLAYER_INFO) {
					revealPlayerInfo(event);
				} else if(event.getPacketType() == PacketType.Play.Server.SCOREBOARD_TEAM) {
					revealTeam(event);
				}
			} catch(RuntimeException e) {
				manager.packetError(event.getPacketType().name(), e);
			}
			return;
		}
		try {
			PacketType type = event.getPacketType();
			if(type == PacketType.Play.Server.PLAYER_INFO) {
				rewritePlayerInfo(event);
			} else if(type == PacketType.Play.Server.SCOREBOARD_TEAM) {
				rewriteTeam(event);
			} else if(type == PacketType.Play.Server.CHAT) {
				rewriteChat(event);
			} else if(type == PacketType.Play.Server.TAB_COMPLETE) {
				rewriteTabComplete(event);
			} else if(type == PacketType.Play.Server.NAMED_ENTITY_SPAWN) {
				checkSpawn(event);
			} else {
				TextSwap.rewrite(event, manager);
			}
		} catch(RuntimeException e) {
			manager.packetError(event.getPacketType().name(), e);
		}
	}

	// ---------------------------------------------------------------- tab list / skin / nametag

	private void rewritePlayerInfo(PacketEvent event) {
		PacketContainer packet = event.getPacket();
		PlayerInfoAction action = packet.getPlayerInfoAction().readSafely(0);
		List<PlayerInfoData> entries = packet.getPlayerInfoDataLists().readSafely(0);
		if(action == null || entries == null || entries.isEmpty()) return;

		UUID viewer = event.getPlayer().getUniqueId();
		List<PlayerInfoData> result = new ArrayList<>(entries.size());
		boolean changed = false;
		for(PlayerInfoData entry : entries) {
			PlayerDisguise disguise = entry == null || entry.getProfile() == null ? null : manager.get(entry.getProfile().getUUID());
			if(disguise == null) {
				// An NPC with a disguised player's real name and its own ID (e.g. CombatLogX after a
				// combat log): it shows the disguise name and skin too.
				PlayerDisguise copyOf = action == PlayerInfoAction.ADD_PLAYER && entry != null && entry.getProfile() != null
						? manager.disguiseOfCopy(entry.getProfile().getName()) : null;
				if(copyOf != null && !copyOf.getUniqueId().equals(entry.getProfile().getUUID())) {
					result.add(new PlayerInfoData(fakeProfile(copyOf, entry.getProfile().getUUID()), entry.getLatency(), entry.getGameMode(),
							replaceNames(entry.getDisplayName())));
					changed = true;
				} else {
					result.add(entry);
				}
				continue;
			}
			changed = true;
			boolean self = viewer.equals(disguise.getUniqueId());
			// Other players get the disguise under its own ID: BungeeCord puts the real skin back on
			// entries of real players, but leaves this one alone. The player keeps the real ID for themselves.
			UUID shownId = self ? disguise.getUniqueId() : disguise.getFakeUniqueId();
			if(action == PlayerInfoAction.REMOVE_PLAYER) {
				result.add(new PlayerInfoData(new WrappedGameProfile(shownId, disguise.getFakeName()), 0, entry.getGameMode(), null));
			} else if(action == PlayerInfoAction.ADD_PLAYER) {
				WrappedGameProfile fake = fakeProfile(disguise, shownId);
				manager.debugOnce(disguise, "Sending " + disguise.getRealName() + " as " + disguise.getFakeName() + " (id " + shownId + ") to "
						+ event.getPlayer().getName() + (fake.getProperties().containsKey("textures") ? " with the disguise skin" : " WITHOUT a skin (none was found)"));
				result.add(new PlayerInfoData(fake, entry.getLatency(), entry.getGameMode(), replaceNames(entry.getDisplayName())));
				disguise.getRemovedFor().remove(viewer);
				if(disguise.isHiddenFromTab() && !self) {
					manager.scheduleTabRemoval(disguise, event.getPlayer());
				}
			} else if(disguise.getRemovedFor().contains(viewer)) {
				// This viewer's client has no entry for the player right now; drop the update.
			} else {
				WrappedChatComponent display = action == PlayerInfoAction.UPDATE_DISPLAY_NAME ? replaceNames(entry.getDisplayName()) : entry.getDisplayName();
				result.add(new PlayerInfoData(new WrappedGameProfile(shownId, disguise.getFakeName()), entry.getLatency(), entry.getGameMode(), display));
			}
		}
		if(!changed) return;
		if(result.isEmpty()) {
			event.setCancelled(true);
			return;
		}
		// The server sends one packet object to many players: change a copy, never the original.
		PacketContainer copy = packet.shallowClone();
		copy.getPlayerInfoDataLists().write(0, result);
		event.setPacket(copy);
	}

	/** Reveal players: the tab line of a disguised player as TAB would show the real player. */
	private void revealPlayerInfo(PacketEvent event) {
		PacketContainer packet = event.getPacket();
		PlayerInfoAction action = packet.getPlayerInfoAction().readSafely(0);
		if(action != PlayerInfoAction.ADD_PLAYER && action != PlayerInfoAction.UPDATE_DISPLAY_NAME) return;
		List<PlayerInfoData> entries = packet.getPlayerInfoDataLists().readSafely(0);
		if(entries == null) return;
		UUID viewer = event.getPlayer().getUniqueId();
		List<PlayerInfoData> result = new ArrayList<>(entries.size());
		boolean changed = false;
		for(PlayerInfoData entry : entries) {
			UUID id = entry == null || entry.getProfile() == null ? null : entry.getProfile().getUUID();
			TabLook.Look look = id == null || manager.get(id) == null ? null : manager.revealLook(viewer, id);
			if(look == null) {
				result.add(entry);
				continue;
			}
			result.add(new PlayerInfoData(entry.getProfile(), entry.getLatency(), entry.getGameMode(), legacy(look.tabLine)));
			changed = true;
		}
		if(!changed) return;
		PacketContainer copy = packet.shallowClone();
		copy.getPlayerInfoDataLists().write(0, result);
		event.setPacket(copy);
	}

	/** Reveal players: the nametag of a disguised player with the real rank. */
	private void revealTeam(PacketEvent event) {
		PacketContainer packet = event.getPacket();
		String team = packet.getStrings().readSafely(0);
		Integer mode = packet.getIntegers().readSafely(1);
		if(team == null || mode == null || (mode != 0 && mode != 2)) return;
		PlayerDisguiseManager.TeamState state = manager.teamOf(event.getPlayer().getUniqueId(), team);
		TabLook.Look look = state == null ? null : manager.revealLook(event.getPlayer().getUniqueId(), state.player);
		if(look == null) return;
		PacketContainer copy = packet.shallowClone();
		copy.getStrings().write(2, PlayerDisguiseManager.cut(look.tagPrefix, 16));
		copy.getStrings().write(3, PlayerDisguiseManager.cut(look.tagSuffix, 16));
		event.setPacket(copy);
	}

	private static WrappedChatComponent legacy(String text) {
		return WrappedChatComponent.fromJson(ComponentSerializer.toString(net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text)));
	}

	/** An UPDATE_DISPLAY_NAME for one player (sent past the rewriter). */
	static PacketContainer displayName(Player player, String text) {
		PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
		packet.getPlayerInfoAction().write(0, PlayerInfoAction.UPDATE_DISPLAY_NAME);
		packet.getPlayerInfoDataLists().write(0, Collections.singletonList(new PlayerInfoData(
				new WrappedGameProfile(player.getUniqueId(), player.getName()), 0, NativeGameMode.fromBukkit(player.getGameMode()), legacy(text))));
		return packet;
	}

	static WrappedGameProfile fakeProfile(PlayerDisguise disguise) {
		return fakeProfile(disguise, disguise.getUniqueId());
	}

	static WrappedGameProfile fakeProfile(PlayerDisguise disguise, UUID id) {
		WrappedGameProfile profile = new WrappedGameProfile(id, disguise.getFakeName());
		if(disguise.hasSkin()) {
			// Straight into the server's own profile object (not through ProtocolLib's converted view).
			Object property = WrappedSignedProperty.fromValues("textures", disguise.getSkinValue(), disguise.getSkinSignature()).getHandle();
			try {
				Object handle = profile.getHandle();
				@SuppressWarnings("unchecked")
				Multimap<String, Object> properties = (Multimap<String, Object>)handle.getClass().getMethod("getProperties").invoke(handle);
				properties.removeAll("textures");
				properties.put("textures", property);
			} catch(ReflectiveOperationException e) {
				profile.getProperties().put("textures", WrappedSignedProperty.fromHandle(property));
			}
		}
		return profile;
	}

	private WrappedChatComponent replaceNames(WrappedChatComponent component) {
		if(component == null) return null;
		Collection<PlayerDisguise> disguises = manager.allOnline();
		String replaced = NameReplacer.replaceJson(component.getJson(), disguises);
		return replaced == null ? component : WrappedChatComponent.fromJson(replaced);
	}

	private void rewriteTeam(PacketEvent event) {
		// Sidebar lines are teams too: there only the visible text may change, never the entries
		// (they are the lines' IDs; a changed ID breaks the whole sidebar on 1.8).
		if(isSidebarTeam(event.getPacket())) {
			TextSwap.rewriteTeamTexts(event, manager);
			return;
		}
		PacketContainer packet = event.getPacket();
		@SuppressWarnings("rawtypes")
		Collection names = packet.getSpecificModifier(Collection.class).readSafely(0);
		if(names == null || names.isEmpty()) return;
		// Never for a name whose real player is online: the 1.8 client picks the nametag by name,
		// so moving that name would change the real player's nametag too.
		List<PlayerDisguise> disguises = new ArrayList<>();
		for(PlayerDisguise disguise : manager.allOnline()) {
			if(!disguise.isHiddenFromTab()) disguises.add(disguise);
		}
		if(disguises.isEmpty()) return;
		List<String> result = new ArrayList<>(names.size());
		boolean changed = false;
		for(Object name : names) {
			String fake = name instanceof String ? fakeNameOf((String)name, disguises) : null;
			if(fake != null) {
				result.add(fake);
				changed = true;
			} else {
				result.add(String.valueOf(name));
			}
		}
		if(!changed) return;
		PacketContainer copy = packet.shallowClone();
		copy.getSpecificModifier(Collection.class).write(0, result);
		event.setPacket(copy);
	}

	/** A sidebar line team (TAB names them "TAB-Sidebar-..."; their entries start with a colour code). */
	@SuppressWarnings("rawtypes")
	private static boolean isSidebarTeam(PacketContainer packet) {
		String team = packet.getStrings().readSafely(0);
		if(team != null && team.toLowerCase(java.util.Locale.ENGLISH).contains("sidebar")) return true;
		Collection entries = packet.getSpecificModifier(Collection.class).readSafely(0);
		if(entries != null) {
			for(Object entry : entries) {
				String text = String.valueOf(entry);
				if(!text.isEmpty() && text.charAt(0) == org.bukkit.ChatColor.COLOR_CHAR) return true;
			}
		}
		return false;
	}

	private static String fakeNameOf(String realName, Collection<PlayerDisguise> disguises) {
		for(PlayerDisguise disguise : disguises) {
			if(disguise.getRealName().equalsIgnoreCase(realName)) return disguise.getFakeName();
		}
		return null;
	}

	/**
	 * A staff member disguised as someone who is online has no tab entry for other players, but
	 * the client needs one to draw a player. Re-add it just before the player appears; it is
	 * removed again a moment later (the client keeps the skin).
	 */
	private void checkSpawn(PacketEvent event) {
		UUID uuid = event.getPacket().getUUIDs().readSafely(0);
		if(uuid == null) return;
		PlayerDisguise disguise = manager.get(uuid);
		Player viewer = event.getPlayer();
		if(disguise == null || viewer.getUniqueId().equals(uuid)) return;
		// The player appears under the disguise's own ID (matching its tab entry).
		PacketContainer spawn = event.getPacket().shallowClone();
		spawn.getUUIDs().write(0, disguise.getFakeUniqueId());
		if(disguise.getRemovedFor().contains(viewer.getUniqueId())) {
			event.setCancelled(true);
			manager.sendTabAdd(viewer, disguise);
			manager.sendRaw(viewer, spawn);
			return;
		}
		event.setPacket(spawn);
	}

	static PacketContainer playerInfo(PlayerInfoAction action, PlayerDisguise disguise, WrappedGameProfile profile) {
		PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);
		packet.getPlayerInfoAction().write(0, action);
		packet.getPlayerInfoDataLists().write(0, Collections.singletonList(
				new PlayerInfoData(profile, 0, NativeGameMode.SURVIVAL, null)));
		return packet;
	}

	// ---------------------------------------------------------------- chat / tab-complete

	private void rewriteChat(PacketEvent event) {
		Collection<PlayerDisguise> disguises = manager.replacingNames();
		if(disguises.isEmpty()) return;
		PacketContainer packet = event.getPacket();
		WrappedChatComponent component = packet.getChatComponents().readSafely(0);
		if(component != null) {
			String replaced = NameReplacer.replaceJson(component.getJson(), disguises);
			if(replaced == null) return;
			PacketContainer copy = packet.shallowClone();
			copy.getChatComponents().write(0, WrappedChatComponent.fromJson(replaced));
			event.setPacket(copy);
			return;
		}
		// Messages sent with Spigot's chat API (player.spigot().sendMessage) use a second field.
		BaseComponent[] components = packet.getSpecificModifier(BaseComponent[].class).readSafely(0);
		if(components == null) return;
		String replaced = NameReplacer.replaceJson(ComponentSerializer.toString(components), disguises);
		if(replaced == null) return;
		PacketContainer copy = packet.shallowClone();
		copy.getChatComponents().write(0, WrappedChatComponent.fromJson(replaced));
		copy.getSpecificModifier(BaseComponent[].class).write(0, null);
		event.setPacket(copy);
	}

	private void rewriteTabComplete(PacketEvent event) {
		Collection<PlayerDisguise> disguises = manager.replacingNames();
		if(disguises.isEmpty()) return;
		PacketContainer packet = event.getPacket();
		String[] suggestions = packet.getStringArrays().readSafely(0);
		if(suggestions == null) return;

		String input = manager.getLastTabInput(event.getPlayer().getUniqueId());
		String token = "";
		if(input != null) {
			int space = input.lastIndexOf(' ');
			token = (space >= 0 ? input.substring(space + 1) : input).toLowerCase(Locale.ENGLISH);
			if(token.startsWith("/")) token = "";
		}

		List<String> result = new ArrayList<>();
		boolean changed = false;
		for(String suggestion : suggestions) {
			String fake = fakeNameOf(suggestion, disguises);
			String value = fake != null ? fake : suggestion;
			if(fake != null) changed = true;
			if(!containsIgnoreCase(result, value)) {
				result.add(value);
			} else {
				changed = true;
			}
		}
		if(!token.isEmpty()) {
			for(PlayerDisguise disguise : disguises) {
				String fake = disguise.getFakeName();
				if(fake.toLowerCase(Locale.ENGLISH).startsWith(token) && !containsIgnoreCase(result, fake)) {
					result.add(fake);
					changed = true;
				}
			}
		}
		if(!changed) return;
		PacketContainer copy = packet.shallowClone();
		copy.getStringArrays().write(0, result.toArray(new String[0]));
		event.setPacket(copy);
	}

	private static boolean containsIgnoreCase(List<String> list, String value) {
		for(String entry : list) {
			if(entry.equalsIgnoreCase(value)) return true;
		}
		return false;
	}

}
