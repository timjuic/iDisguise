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
import org.bukkit.potion.PotionEffect;

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
	// Answers still expected: an answer is only used when it was asked for.
	private final Map<UUID, Integer> awaiting = new ConcurrentHashMap<>();
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
		} catch(RuntimeException e) {
			plugin.getLogger().warning("Players can't see their own disguise skin (plugin channel): " + e);
			return;
		}
		dimensionListener = new PacketAdapter(plugin, ListenerPriority.MONITOR, PacketType.Play.Server.LOGIN, PacketType.Play.Server.RESPAWN) {
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
		ProtocolLibrary.getProtocolManager().addPacketListener(dimensionListener);
	}

	void stop() {
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
		awaiting.remove(player.getUniqueId());
		dimensions.remove(player.getUniqueId());
	}

	/** Asks the proxy for the skin the player should see on themselves now (after disguise, undisguise, join, reload). */
	void update(Player player) {
		if(!player.isOnline()) return;
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
				send(player, bytes.toByteArray());
				requested.put(player.getUniqueId(), disguise.getSkinValue());
			} else if(!want && now != null) {
				send(player, new byte[] {VERSION, RESET});
				requested.remove(player.getUniqueId());
			}
		} catch(Exception e) {
			manager.debug("Could not ask the proxy for the own skin of " + player.getName(), e);
		}
	}

	private void send(Player player, byte[] data) {
		// Bukkit only sends it when the proxy registered the channel, i.e. when the helper is there.
		if(!player.getListeningPluginChannels().contains(CHANNEL)) return;
		player.sendPluginMessage(plugin, CHANNEL, data);
		awaiting.merge(player.getUniqueId(), 1, Integer::sum);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if(!CHANNEL.equals(channel) || player == null) return;
		Integer left = awaiting.get(player.getUniqueId());
		if(left == null || left <= 0) return; // not asked for
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
			if(in.readByte() != VERSION) return;
			in.readByte();
		} catch(Exception e) {
			return;
		}
		if(left == 1) awaiting.remove(player.getUniqueId());
		else awaiting.put(player.getUniqueId(), left - 1);
		// Only after the last answer: one refresh, never two in a row.
		if(left == 1) Bukkit.getScheduler().runTask(plugin, () -> refreshWhenFree(player, 0));
	}

	/** Not while a menu or chest is open, not while riding, sleeping or dead: waits (at most 15 seconds). */
	private void refreshWhenFree(Player player, int tries) {
		if(!player.isOnline()) return;
		boolean busy = player.isDead() || player.isSleeping() || player.isInsideVehicle()
				|| player.getOpenInventory().getType() != InventoryType.CRAFTING;
		if(busy) {
			if(tries < 30) Bukkit.getScheduler().runTaskLater(plugin, () -> refreshWhenFree(player, tries + 1), 10L);
			return;
		}
		refresh(player);
	}

	/**
	 * The player's own game takes the new skin: the own tab entry again, then a respawn into the same
	 * world (same dimension = no loading screen, blocks and players stay), then everything a respawn
	 * clears on the player's side: position, inventory, held slot, flight, health, XP, potion effects.
	 */
	private void refresh(Player player) {
		Integer dimension = dimensions.get(player.getUniqueId());
		if(refreshBroken || dimension == null) {
			manager.resendOwnEntry(player);
			return;
		}
		// Everything is prepared first: if anything is missing, the respawn is never sent.
		PacketContainer respawn;
		Object connection;
		Method teleport;
		try {
			respawn = new PacketContainer(PacketType.Play.Server.RESPAWN);
			respawn.getIntegers().write(0, dimension);
			respawn.getDifficulties().write(0, EnumWrappers.Difficulty.valueOf(player.getWorld().getDifficulty().name()));
			respawn.getGameModes().write(0, NativeGameMode.fromBukkit(player.getGameMode()));
			respawn.getWorldTypeModifier().write(0, player.getWorld().getWorldType());
			Object handle = player.getClass().getMethod("getHandle").invoke(player);
			connection = handle.getClass().getField("playerConnection").get(handle);
			teleport = connection.getClass().getMethod("teleport", Location.class);
		} catch(Throwable t) {
			refreshBroken = true;
			plugin.getLogger().warning("Players can't see their own disguise skin on this server version (nothing else changes): " + t);
			manager.resendOwnEntry(player);
			return;
		}
		manager.resendOwnEntry(player);
		try {
			ProtocolLibrary.getProtocolManager().sendServerPacket(player, respawn, false);
			teleport.invoke(connection, player.getLocation());
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not refresh " + player.getName() + "'s own view (a relog fixes it): " + t);
		}
		restoreAfterRespawn(player);
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
		try {
			for(PotionEffect effect : player.getActivePotionEffects()) player.addPotionEffect(effect, true);
		} catch(Throwable t) {
			manager.debug("Own view refresh: potion effects", t);
		}
		try {
			if(manager.isInvisible(player)) manager.sendOwnInvisibility(player);
		} catch(Throwable t) {
			manager.debug("Own view refresh: invisibility", t);
		}
	}
}
