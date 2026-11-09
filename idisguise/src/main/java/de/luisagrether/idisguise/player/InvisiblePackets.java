package de.luisagrether.idisguise.player;

import java.util.UUID;

import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;

/**
 * Fake invisibility: armor / held item, footsteps and landing particles. Its own listener, registered
 * only while an invisible player is online, so player and mob disguises never have these packet
 * types watched.
 */
final class InvisiblePackets extends PacketAdapter {

	private final PlayerDisguiseManager manager;

	InvisiblePackets(Plugin plugin, PlayerDisguiseManager manager) {
		super(plugin, ListenerPriority.HIGHEST,
				PacketType.Play.Server.ENTITY_EQUIPMENT,
				PacketType.Play.Server.NAMED_SOUND_EFFECT,
				PacketType.Play.Server.WORLD_PARTICLES);
		this.manager = manager;
	}

	void register() {
		ProtocolLibrary.getProtocolManager().addPacketListener(this);
	}

	void unregister() {
		ProtocolLibrary.getProtocolManager().removePacketListener(this);
	}

	@Override
	public void onPacketSending(PacketEvent event) {
		if(event.isCancelled() || event.getPlayer() == null || !manager.invisibility().anyOnline()) return;
		// Staff with "/disguise reveal players" on see invisible players normally.
		if(manager.isRevealingPlayers(event.getPlayer().getUniqueId())) return;
		try {
			PacketType type = event.getPacketType();
			if(type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
				invisibleEquipment(event);
			} else if(type == PacketType.Play.Server.NAMED_SOUND_EFFECT) {
				invisibleSound(event);
			} else if(type == PacketType.Play.Server.WORLD_PARTICLES) {
				invisibleParticles(event);
			}
		} catch(RuntimeException e) {
			manager.packetError(event.getPacketType().name(), e);
		}
	}

	/** Armor and the held item of an invisible player: empty for everyone else. */
	private void invisibleEquipment(PacketEvent event) {
		Integer entityId = event.getPacket().getIntegers().readSafely(0);
		UUID owner = entityId == null ? null : manager.invisibility().ownerOf(entityId);
		if(owner == null || event.getPlayer().getUniqueId().equals(owner)) return;
		PacketContainer copy = event.getPacket().shallowClone();
		copy.getItemModifier().write(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
		event.setPacket(copy);
	}

	/** Footsteps and landing sounds right at an invisible player. Fights sound normal. */
	private void invisibleSound(PacketEvent event) {
		String sound = event.getPacket().getStrings().readSafely(0);
		if(sound == null || !(sound.startsWith("step.") || sound.startsWith("game.player.hurt.fall")
				|| sound.startsWith("game.neutral.hurt.fall") || sound.startsWith("game.hostile.hurt.fall"))) return;
		Integer x = event.getPacket().getIntegers().readSafely(0);
		Integer y = event.getPacket().getIntegers().readSafely(1);
		Integer z = event.getPacket().getIntegers().readSafely(2);
		if(x == null || y == null || z == null) return;
		if(manager.invisibility().near(event.getPlayer(), x / 8.0, y / 8.0, z / 8.0)) event.setCancelled(true);
	}

	/** Landing particles (block dust) right at an invisible player. */
	private void invisibleParticles(PacketEvent event) {
		Object particle;
		try {
			particle = event.getPacket().getParticles().readSafely(0);
		} catch(RuntimeException e) {
			return;
		}
		if(particle == null || !String.valueOf(particle).contains("BLOCK_DUST")) return;
		Float x = event.getPacket().getFloat().readSafely(0);
		Float y = event.getPacket().getFloat().readSafely(1);
		Float z = event.getPacket().getFloat().readSafely(2);
		if(x == null || y == null || z == null) return;
		if(manager.invisibility().near(event.getPlayer(), x, y, z)) event.setCancelled(true);
	}

}
