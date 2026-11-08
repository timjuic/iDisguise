package de.luisagrether.idisguise.player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;

/**
 * For a few seconds after disguising, the player sees themselves with the disguise skin (their own
 * game can't show it otherwise behind BungeeCord): they are made invisible on their own screen and a
 * copy with the disguise skin follows them. Only packets to that one player; nothing on the server
 * changes, so ending early (quit, world change, undisguise) never leaves anything behind.
 * Minecraft 1.8 packets.
 */
final class SelfPreview {

	private static final AtomicInteger IDS = new AtomicInteger(1_900_000_000);
	private static final byte INVISIBILITY = 14;

	private final class Running {
		final int entityId;
		final UUID entityUuid;
		final BukkitTask task;
		boolean tabEntry = true;

		Running(int entityId, UUID entityUuid, BukkitTask task) {
			this.entityId = entityId;
			this.entityUuid = entityUuid;
			this.task = task;
		}
	}

	private final Plugin plugin;
	private final PlayerDisguiseManager manager;
	private final Map<UUID, Running> running = new ConcurrentHashMap<>();

	SelfPreview(Plugin plugin, PlayerDisguiseManager manager) {
		this.plugin = plugin;
		this.manager = manager;
	}

	/** Shows the disguise to the player themselves for the given number of ticks. */
	void show(Player player, PlayerDisguise disguise, int ticks) {
		stop(player);
		if(!player.isOnline()) return;
		int id = IDS.incrementAndGet();
		UUID copyUuid = UUID.randomUUID();
		Location at = player.getLocation();

		// The copy needs a tab entry for its skin; it is removed again once the copy is drawn.
		WrappedGameProfile profile = PacketRewriter.fakeProfile(disguise, copyUuid);
		manager.sendRaw(player, PacketRewriter.playerInfo(PlayerInfoAction.ADD_PLAYER, disguise, profile));
		manager.sendRaw(player, spawn(id, copyUuid, at, player));
		sendEquipment(player, id);
		manager.sendRaw(player, headRotation(id, at.getYaw()));
		manager.sendRaw(player, invisibility(player.getEntityId(), ticks + 40));

		int[] age = {0};
		BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			Running current = running.get(player.getUniqueId());
			if(current == null || current.entityId != id) return;
			if(!player.isOnline() || !player.getWorld().equals(at.getWorld()) || manager.get(player.getUniqueId()) != disguise) {
				stop(player);
				return;
			}
			age[0]++;
			Location now = player.getLocation();
			manager.sendRaw(player, teleport(id, now, player.isOnGround()));
			manager.sendRaw(player, headRotation(id, now.getYaw()));
			if(age[0] == 40 && current.tabEntry) {
				current.tabEntry = false;
				manager.sendRaw(player, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, disguise,
						new WrappedGameProfile(copyUuid, disguise.getFakeName())));
			}
			if(age[0] >= ticks) stop(player);
		}, 1L, 1L);
		running.put(player.getUniqueId(), new Running(id, copyUuid, task));
	}

	/** Ends the preview now (if one is running) and makes the player visible to themselves again. */
	void stop(Player player) {
		Running current = running.remove(player.getUniqueId());
		if(current == null) return;
		current.task.cancel();
		if(!player.isOnline()) return;
		PacketContainer destroy = new PacketContainer(PacketType.Play.Server.ENTITY_DESTROY);
		destroy.getIntegerArrays().write(0, new int[] {current.entityId});
		manager.sendRaw(player, destroy);
		if(current.tabEntry) {
			manager.sendRaw(player, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, null,
					new WrappedGameProfile(current.entityUuid, "preview")));
		}
		// Keep a real invisibility effect (potion) the player may have.
		if(!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
			PacketContainer remove = new PacketContainer(PacketType.Play.Server.REMOVE_ENTITY_EFFECT);
			remove.getIntegers().write(0, player.getEntityId()).write(1, (int)INVISIBILITY);
			manager.sendRaw(player, remove);
		}
	}

	void forget(UUID uuid) {
		Running current = running.remove(uuid);
		if(current != null) current.task.cancel();
	}

	void stopAll() {
		for(Player player : Bukkit.getOnlinePlayers()) stop(player);
	}

	// ---------------------------------------------------------------- packets

	private static PacketContainer spawn(int id, UUID uuid, Location at, Player player) {
		PacketContainer packet = new PacketContainer(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
		packet.getIntegers()
				.write(0, id)
				.write(1, fixed(at.getX()))
				.write(2, fixed(at.getY()))
				.write(3, fixed(at.getZ()))
				.write(4, 0);
		packet.getUUIDs().write(0, uuid);
		packet.getBytes().write(0, angle(at.getYaw())).write(1, angle(at.getPitch()));
		WrappedDataWatcher watcher = new WrappedDataWatcher();
		watcher.setObject(0, (byte)0);
		watcher.setObject(1, (short)300);
		watcher.setObject(6, 20f);
		// all skin layers (hat, jacket, sleeves ...)
		watcher.setObject(10, (byte)127);
		packet.getDataWatcherModifier().write(0, watcher);
		return packet;
	}

	private void sendEquipment(Player player, int id) {
		ItemStack[] armor = player.getInventory().getArmorContents(); // boots, leggings, chestplate, helmet
		@SuppressWarnings("deprecation")
		ItemStack hand = player.getItemInHand();
		sendItem(player, id, 0, hand);
		for(int i = 0; i < armor.length && i < 4; i++) {
			sendItem(player, id, i + 1, armor[i]);
		}
	}

	private void sendItem(Player player, int id, int slot, ItemStack item) {
		if(item == null || item.getType() == Material.AIR) return;
		PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT);
		packet.getIntegers().write(0, id).write(1, slot);
		packet.getItemModifier().write(0, item);
		manager.sendRaw(player, packet);
	}

	private static PacketContainer teleport(int id, Location at, boolean onGround) {
		PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_TELEPORT);
		packet.getIntegers()
				.write(0, id)
				.write(1, fixed(at.getX()))
				.write(2, fixed(at.getY()))
				.write(3, fixed(at.getZ()));
		packet.getBytes().write(0, angle(at.getYaw())).write(1, angle(at.getPitch()));
		packet.getBooleans().write(0, onGround);
		return packet;
	}

	private static PacketContainer headRotation(int id, float yaw) {
		PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
		packet.getIntegers().write(0, id);
		packet.getBytes().write(0, angle(yaw));
		return packet;
	}

	private static PacketContainer invisibility(int entityId, int ticks) {
		PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_EFFECT);
		packet.getIntegers().write(0, entityId).write(1, ticks);
		packet.getBytes().write(0, INVISIBILITY).write(1, (byte)0).write(2, (byte)1);
		return packet;
	}

	private static int fixed(double coordinate) {
		return (int)Math.floor(coordinate * 32.0D);
	}

	private static byte angle(float degrees) {
		return (byte)(int)(degrees * 256.0F / 360.0F);
	}

}
