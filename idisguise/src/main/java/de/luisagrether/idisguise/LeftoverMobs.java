package de.luisagrether.idisguise;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Mob disguises are real mobs, saved with their chunk. After a crash nothing removes them, and they would
 * stay as normal mobs with drops. Their IDs are kept in mob-disguises.txt; any of them found when a chunk
 * loads (or at start) is removed. Main thread only.
 */
final class LeftoverMobs {

	private final File file;
	private final Logger logger;
	private final Set<UUID> ids = new LinkedHashSet<>();

	LeftoverMobs(File folder, Logger logger) {
		this.file = new File(folder, "mob-disguises.txt");
		this.logger = logger;
		try {
			if(file.isFile()) {
				for(String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
					try {
						ids.add(UUID.fromString(line.trim()));
					} catch(IllegalArgumentException ignored) {
					}
				}
			}
		} catch(IOException e) {
			logger.warning("Could not read " + file.getName() + ": " + e);
		}
	}

	void add(Entity entity) {
		if(ids.add(entity.getUniqueId())) save();
	}

	void remove(Entity entity) {
		if(entity != null && ids.remove(entity.getUniqueId())) save();
	}

	/** Removes leftovers in these chunks, except the mobs still in use. */
	void clean(Chunk chunk, Set<UUID> inUse) {
		if(ids.isEmpty()) return;
		boolean changed = false;
		for(Entity entity : chunk.getEntities()) {
			UUID id = entity.getUniqueId();
			if(!ids.contains(id) || inUse.contains(id)) continue;
			entity.remove();
			ids.remove(id);
			changed = true;
		}
		if(changed) save();
	}

	void cleanLoaded(List<World> worlds, Set<UUID> inUse) {
		if(ids.isEmpty()) return;
		for(World world : worlds) {
			for(Chunk chunk : world.getLoadedChunks()) clean(chunk, inUse);
		}
	}

	private void save() {
		List<String> lines = new ArrayList<>();
		for(UUID id : ids) lines.add(id.toString());
		try {
			Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
		} catch(IOException e) {
			logger.warning("Could not write " + file.getName() + ": " + e);
		}
	}

}
