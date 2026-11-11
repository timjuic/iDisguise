package de.luisagrether.idisguise.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;

import de.luisagrether.idisguise.iDisguise;

/**
 * see-own-disguise: the disguised player sees the disguise skin on themselves too (F5, inventory).
 *
 * BungeeCord puts the player's real skin into their own tab entry, so this needs the iDisguiseProxy
 * helper on the proxy. iDisguise asks it to use the disguise skin; only when it answers, the player's own
 * game is refreshed (a respawn in the same world, no loading screen). Without the helper there is no
 * answer, so nothing happens at all. Only the disguised player's own screen is ever refreshed.
 */
final class OwnSkin implements PluginMessageListener {

	static final String CHANNEL = "idisguise:skin";
	private static final byte VERSION = 1;
	private static final byte SET = 1;
	private static final byte RESET = 2;

	private final iDisguise plugin;
	private final PlayerDisguiseManager manager;
	// The dimension each player's game has now (from the login and respawn packets).
	private final Map<UUID, Integer> dimensions = new ConcurrentHashMap<>();
	// The skin the proxy was asked to use (null: real skin).
	private final Map<UUID, String> requested = new ConcurrentHashMap<>();
	// When the proxy was last asked, and how many answers are still open: one answer per question, and only
	// shortly after asking (a modded game can't trigger refreshes by sending answers itself).
	private final Map<UUID, Long> lastAsked = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> openAnswers = new ConcurrentHashMap<>();
	// Undisguise: others see the player again only once the proxy gave the real skin back.
	private final Map<UUID, Runnable> afterReset = new ConcurrentHashMap<>();
	// A refresh is already planned (several answers in a row give one refresh).
	private final java.util.Set<UUID> refreshPlanned = ConcurrentHashMap.newKeySet();
	private PacketAdapter dimensionListener;
	private boolean refreshBroken;

	OwnSkin(iDisguise plugin, PlayerDisguiseManager manager) {
		this.plugin = plugin;
		this.manager = manager;
	}

	void start() {
		try {
			Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
			Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
		} catch(Throwable e) {
			plugin.getLogger().warning("Players can't see their own disguise skin (plugin channel): " + e);
			return;
		}
		dimensionListener = new PacketAdapter(PacketAdapter.params(plugin, PacketType.Play.Server.LOGIN, PacketType.Play.Server.RESPAWN)
				.listenerPriority(ListenerPriority.MONITOR).optionAsync()) {
			@Override
			public void onPacketSending(PacketEvent event) {
				try {
					int index = event.getPacketType() == PacketType.Play.Server.LOGIN ? 1 : 0;
					Integer dimension = event.getPacket().getIntegers().readSafely(index);
					if(dimension != null && event.getPlayer() != null) dimensions.put(event.getPlayer().getUniqueId(), dimension);
				} catch(RuntimeException ignored) {
				}
			}
		};
		try {
			ProtocolLibrary.getProtocolManager().addPacketListener(dimensionListener);
		} catch(Throwable t) {
			// Never stops the rest of the plugin: without it, players just see their real skin on themselves.
			dimensionListener = null;
			plugin.getLogger().warning("Players can't see their own disguise skin (packet listener): " + t);
		}
	}

	void stop() {
		// Undisguised in the last second: others get to see the player before everything stops.
		for(Runnable waiting : new java.util.ArrayList<>(afterReset.values())) {
			try {
				waiting.run();
			} catch(Throwable ignored) {
			}
		}
		afterReset.clear();
		try {
			Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
			Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
		} catch(RuntimeException ignored) {
		}
		if(dimensionListener != null) ProtocolLibrary.getProtocolManager().removePacketListener(dimensionListener);
		dimensionListener = null;
	}

	/** The plugin stops: the proxy gives the real skin data back (no refresh, nobody listens any more). */
	void resetNow(Player player) {
		if(requested.remove(player.getUniqueId()) == null) return;
		try {
			if(player.getListeningPluginChannels().contains(CHANNEL)) player.sendPluginMessage(plugin, CHANNEL, new byte[] {VERSION, RESET});
		} catch(RuntimeException ignored) {
		}
	}

	void quit(Player player) {
		requested.remove(player.getUniqueId());
		lastAsked.remove(player.getUniqueId());
		openAnswers.remove(player.getUniqueId());
		afterReset.remove(player.getUniqueId());
		refreshPlanned.remove(player.getUniqueId());
		dimensions.remove(player.getUniqueId());
	}

	/** Asks the proxy for the skin the player should see on themselves now (after disguise, undisguise, join, reload). */
	void update(Player player) {
		if(!isCurrent(player)) return;
		PlayerDisguise disguise = manager.get(player.getUniqueId());
		boolean want = manager.settings().seeOwnDisguise && disguise != null && disguise.hasSkin();
		String now = requested.get(player.getUniqueId());
		try {
			if(want && !disguise.getSkinValue().equals(now)) {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				DataOutputStream out = new DataOutputStream(bytes);
				out.writeByte(VERSION);
				out.writeByte(SET);
				out.writeUTF(disguise.getSkinValue());
				out.writeUTF(disguise.getSkinSignature() == null ? "" : disguise.getSkinSignature());
				// Only noted when it really went out (the proxy may not have announced the helper yet).
				if(send(player, bytes.toByteArray())) requested.put(player.getUniqueId(), disguise.getSkinValue());
			} else if(!want && now != null) {
				requested.remove(player.getUniqueId());
				send(player, new byte[] {VERSION, RESET});
			}
		} catch(Throwable e) {
			manager.debug("Could not ask the proxy for the own skin of " + player.getName(), e);
		}
	}

	/**
	 * Undisguise while the proxy holds the disguise skin: asks for the real skin back and runs the given
	 * task (others see the player again) once the proxy answered, at the latest after a second. False when
	 * the proxy doesn't hold the disguise skin (the caller runs the task right away).
	 */
	boolean resetThen(Player player, Runnable task) {
		UUID id = player.getUniqueId();
		if(!requested.containsKey(id)) return false;
		requested.remove(id);
		if(!send(player, new byte[] {VERSION, RESET})) return false;
		afterReset.put(id, task);
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			Runnable left = afterReset.remove(id);
			if(left != null) left.run();
		}, 20L);
		return true;
	}

	/** False when it could not go out (no helper on the proxy, or not announced yet). */
	private boolean send(Player player, byte[] data) {
		// Bukkit only sends it when the proxy registered the channel, i.e. when the helper is there.
		if(!player.getListeningPluginChannels().contains(CHANNEL)) return false;
		player.sendPluginMessage(plugin, CHANNEL, data);
		UUID id = player.getUniqueId();
		Long before = lastAsked.put(id, System.currentTimeMillis());
		// Answers from long ago never came: start counting again.
		if(before == null || System.currentTimeMillis() - before > 30000L) openAnswers.remove(id);
		openAnswers.merge(id, 1, Integer::sum);
		return true;
	}

	/** The player's live object (a quick relog makes a new one; tasks of the old one stop). */
	private static boolean isCurrent(Player player) {
		return player != null && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if(!CHANNEL.equals(channel) || player == null) return;
		Long asked = lastAsked.get(player.getUniqueId());
		if(asked == null || System.currentTimeMillis() - asked > 30000L) return; // not asked for
		Integer open = openAnswers.get(player.getUniqueId());
		if(open == null || open <= 0) return; // more answers than questions
		if(open == 1) openAnswers.remove(player.getUniqueId());
		else openAnswers.put(player.getUniqueId(), open - 1);
		byte action;
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
			if(in.readByte() != VERSION) return;
			action = in.readByte();
		} catch(Exception e) {
			return;
		}
		// Only an answer to what is wanted now counts (an older answer after a quick /d + /ud is skipped).
		boolean disguisedSkin = requested.containsKey(player.getUniqueId());
		if(action == SET ? !disguisedSkin : action == RESET ? disguisedSkin : true) return;
		if(action == RESET) {
			// The real skin is back on the proxy: now others may see the player (again, if the second passed).
			Runnable waiting = afterReset.remove(player.getUniqueId());
			if(waiting != null) waiting.run();
			else manager.refreshViewers(player);
		}
		// A tick later: several answers in a row give one refresh.
		if(refreshPlanned.add(player.getUniqueId())) {
			Bukkit.getScheduler().runTaskLater(plugin, () -> refreshWhenFree(player, 0), 2L);
		}
	}

	private void refreshWhenFree(Player player, int tries) {
		try {
			if(!tryRefresh(player, tries)) return;
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not refresh " + player.getName() + "'s own view (a relog fixes it): " + t);
		}
		refreshPlanned.remove(player.getUniqueId());
	}

	/** Not while a menu or chest is open, not while riding, sleeping or dead: waits (at most 15 seconds). */
	/** False while it waits for another try (the refresh stays planned). */
	private boolean tryRefresh(Player player, int tries) {
		if(!isCurrent(player)) return true;
		boolean busy = player.isDead() || player.isSleeping() || player.isInsideVehicle()
				|| player.getOpenInventory().getType() != InventoryType.CRAFTING;
		if(busy) {
			if(tries >= 30) return true;
			Bukkit.getScheduler().runTaskLater(plugin, () -> refreshWhenFree(player, tries + 1), 10L);
			return false;
		}
		refresh(player);
		return true;
	}

	/**
	 * The player's own game takes the new skin: the own tab entry again, then a respawn into the same
	 * world (same dimension = no loading screen, blocks and players stay), then everything a respawn
	 * clears on the player's side: position, inventory, held slot, flight, health, XP, potion effects.
	 */
	private void refresh(Player player) {
		Integer dimension = dimensions.get(player.getUniqueId());
		// A respawn into another dimension than the game has would leave it in an empty world: only when the
		// tracked one is the world's own, and only for 1.8 games (newer games rebuild their world on a respawn).
		if(refreshBroken || dimension == null || dimension != player.getWorld().getEnvironment().getId() || !isOneEightGame(player)) {
			manager.resendOwnEntry(player);
			return;
		}
		// Everything is prepared first: if anything is missing, the respawn is never sent.
		// The server's own respawn packet with the server's own values (difficulty, world type, game mode),
		// exactly what the server sends on a real respawn. An empty value would break the packet on its way
		// out and disconnect the player, so then nothing is sent.
		Object respawn;
		Object connection;
		Method sendPacket;
		Method teleport;
		try {
			Object handle = player.getClass().getMethod("getHandle").invoke(player);
			Object world = handle.getClass().getField("world").get(handle);
			Object difficulty = world.getClass().getMethod("getDifficulty").invoke(world);
			Object worldData = world.getClass().getMethod("getWorldData").invoke(world);
			Object worldType = worldData == null ? null : worldData.getClass().getMethod("getType").invoke(worldData);
			Object interact = handle.getClass().getField("playerInteractManager").get(handle);
			Object gameMode = interact.getClass().getMethod("getGameMode").invoke(interact);
			if(difficulty == null || worldType == null || gameMode == null) {
				throw new IllegalStateException("empty value: difficulty " + difficulty + ", world type " + worldType + ", game mode " + gameMode);
			}
			String nms = handle.getClass().getPackage().getName();
			Class<?> packetClass = Class.forName(nms + ".PacketPlayOutRespawn");
			java.lang.reflect.Constructor<?> create = null;
			for(java.lang.reflect.Constructor<?> candidate : packetClass.getConstructors()) {
				if(candidate.getParameterCount() == 4) create = candidate;
			}
			if(create == null) throw new NoSuchMethodException("PacketPlayOutRespawn(int, difficulty, world type, game mode)");
			respawn = create.newInstance(dimension, difficulty, worldType, gameMode);
			connection = handle.getClass().getField("playerConnection").get(handle);
			sendPacket = connection.getClass().getMethod("sendPacket", Class.forName(nms + ".Packet"));
			teleport = connection.getClass().getMethod("teleport", Location.class);
		} catch(Throwable t) {
			refreshBroken = true;
			plugin.getLogger().warning("Players can't see their own disguise skin on this server version (nothing else changes): " + t);
			manager.resendOwnEntry(player);
			return;
		}
		manager.resendOwnEntry(player);
		try {
			sendPacket.invoke(connection, respawn);
			teleport.invoke(connection, player.getLocation());
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not refresh " + player.getName() + "'s own view (a relog fixes it): " + t);
		}
		restoreAfterRespawn(player);
	}

	/** True for a 1.8 game. With ViaVersion, its version; without it, every game on this 1.8 server is 1.8. */
	private static boolean isOneEightGame(Player player) {
		Class<?> via;
		try {
			via = Class.forName("com.viaversion.viaversion.api.Via");
		} catch(Throwable notThere) {
			return true;
		}
		try {
			Object api = via.getMethod("getAPI").invoke(null);
			Object version = api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
			return version instanceof Integer && (Integer)version == 47;
		} catch(Throwable unsure) {
			return false;
		}
	}

	private void restoreAfterRespawn(Player player) {
		try {
			player.updateInventory();
			player.getInventory().setHeldItemSlot(player.getInventory().getHeldItemSlot());
		} catch(Throwable t) {
			manager.debug("Own view refresh: inventory", t);
		}
		try {
			boolean flying = player.isFlying();
			player.setAllowFlight(player.getAllowFlight());
			if(flying && player.getAllowFlight()) player.setFlying(true);
		} catch(Throwable t) {
			manager.debug("Own view refresh: flight", t);
		}
		try {
			player.getClass().getMethod("updateScaledHealth").invoke(player);
		} catch(Throwable t) {
			manager.debug("Own view refresh: health", t);
		}
		try {
			Object handle = player.getClass().getMethod("getHandle").invoke(player);
			Field exp = handle.getClass().getField("lastSentExp");
			exp.setInt(handle, -1);
		} catch(Throwable t) {
			manager.debug("Own view refresh: XP", t);
		}
		// Only shown again on the player's own screen, exactly like the server does after a world change: the
		// real effects are never touched (removing and adding absorption would refill its hearts).
		try {
			Object handle = player.getClass().getMethod("getHandle").invoke(player);
			Object connection = handle.getClass().getField("playerConnection").get(handle);
			String nms = handle.getClass().getPackage().getName();
			Class<?> packetType = Class.forName(nms + ".Packet");
			Method sendPacket = connection.getClass().getMethod("sendPacket", packetType);
			int id = (Integer)handle.getClass().getMethod("getId").invoke(handle);
			Class<?> effectPacket = Class.forName(nms + ".PacketPlayOutEntityEffect");
			Class<?> mobEffect = Class.forName(nms + ".MobEffect");
			java.lang.reflect.Constructor<?> newEffect = effectPacket.getConstructor(int.class, mobEffect);
			Object effects = handle.getClass().getMethod("getEffects").invoke(handle);
			if(effects instanceof java.util.Collection) {
				for(Object effect : new java.util.ArrayList<>((java.util.Collection<?>)effects)) {
					if(effect != null) sendPacket.invoke(connection, newEffect.newInstance(id, effect));
				}
			}
			// The player's own entity data (skin layers, absorption hearts, ...), all of it.
			Object watcher = handle.getClass().getMethod("getDataWatcher").invoke(handle);
			Class<?> metadataPacket = Class.forName(nms + ".PacketPlayOutEntityMetadata");
			if(watcher != null) {
				sendPacket.invoke(connection, metadataPacket.getConstructor(int.class, Class.forName(nms + ".DataWatcher"), boolean.class).newInstance(id, watcher, true));
			}
		} catch(Throwable t) {
			manager.debug("Own view refresh: potion effects / entity data", t);
		}
		try {
			if(manager.isInvisible(player)) manager.sendOwnInvisibility(player);
		} catch(Throwable t) {
			manager.debug("Own view refresh: invisibility", t);
		}
	}
}
