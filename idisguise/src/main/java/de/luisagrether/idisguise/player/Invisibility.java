package de.luisagrether.idisguise.player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Fake invisibility (staff, to catch ESP / tracer cheats): who is invisible (saved in invisible.yml,
 * so it survives logout and restarts), their entity IDs and positions while online. The packets are
 * changed in PacketRewriter; the server itself never makes anyone invisible.
 */
final class Invisibility {

	/** Sounds and particles this close to an invisible player are theirs (footsteps, landing). */
	static final double NEAR = 2.0;

	private final File file;
	private final Logger logger;
	private final Set<UUID> saved = ConcurrentHashMap.newKeySet();
	// online invisible players: entity ID -> player, player -> {world id hash, x, y, z}
	private final Map<Integer, UUID> byEntityId = new ConcurrentHashMap<>();
	private final Map<UUID, double[]> positions = new ConcurrentHashMap<>();

	Invisibility(File dataFolder, Logger logger) {
		this.file = new File(dataFolder, "invisible.yml");
		this.logger = logger;
		if(file.exists()) {
			YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
			for(String id : yaml.getStringList("players")) {
				try {
					saved.add(UUID.fromString(id));
				} catch(IllegalArgumentException e) {
					logger.warning("Skipping an invalid entry in invisible.yml: " + id);
				}
			}
		}
	}

	boolean isInvisible(UUID player) {
		return saved.contains(player);
	}

	/** Turns it on or off (saved right away). */
	void set(UUID player, boolean invisible) {
		if(invisible) {
			saved.add(player);
		} else {
			saved.remove(player);
		}
		save();
	}

	/** At least one invisible player is online: the packet listener has to run. */
	boolean anyOnline() {
		return !byEntityId.isEmpty();
	}

	/** The invisible player with this entity ID, or null. */
	UUID ownerOf(int entityId) {
		return byEntityId.get(entityId);
	}

	/** An invisible player is online (join, respawn, turned on). */
	void online(Player player) {
		offline(player.getUniqueId());
		if(!saved.contains(player.getUniqueId())) return;
		byEntityId.put(player.getEntityId(), player.getUniqueId());
		move(player, player.getLocation());
	}

	void offline(UUID player) {
		byEntityId.values().removeIf(player::equals);
		positions.remove(player);
	}

	void move(Player player, Location to) {
		if(to == null || to.getWorld() == null || !positions.containsKey(player.getUniqueId()) && !byEntityId.containsValue(player.getUniqueId())) return;
		positions.put(player.getUniqueId(), new double[] {to.getWorld().getUID().hashCode(), to.getX(), to.getY(), to.getZ()});
	}

	/** An invisible player (other than the viewer) is within NEAR blocks of this spot. */
	boolean near(Player viewer, double x, double y, double z) {
		if(positions.isEmpty()) return false;
		double world = viewer.getWorld().getUID().hashCode();
		for(Map.Entry<UUID, double[]> entry : positions.entrySet()) {
			if(entry.getKey().equals(viewer.getUniqueId())) continue;
			double[] at = entry.getValue();
			if(at[0] != world) continue;
			double dx = at[1] - x, dy = at[2] - y, dz = at[3] - z;
			if(dx * dx + dy * dy + dz * dz <= NEAR * NEAR) return true;
		}
		return false;
	}

	private void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		List<String> ids = new ArrayList<>();
		for(UUID id : saved) ids.add(id.toString());
		yaml.set("players", ids);
		String content = "# Players with iDisguise's fake invisibility on. Don't edit while the server is running.\n" + yaml.saveToString();
		File temp = new File(file.getParentFile(), "invisible.yml.tmp");
		try {
			Files.write(temp.toPath(), content.getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch(IOException atomicNotSupported) {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch(IOException e) {
			logger.log(Level.SEVERE, "Could not save invisible.yml", e);
		}
	}

}
