package de.luisagrether.idisguise.player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Keeps player disguises in player-disguises.yml so they survive quitting and restarts.
 * Writes go to a temp file first and then replace the real one, one after another on a
 * background thread, so a crash mid-write never leaves a half-written file.
 * A file that can't be read is moved aside (never overwritten) and the server owner is told.
 */
final class PlayerDisguiseStore {

	private final File file;
	private final Logger logger;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "iDisguise player-disguise writer");
		thread.setDaemon(true);
		return thread;
	});

	PlayerDisguiseStore(File dataFolder, Logger logger) {
		this.file = new File(dataFolder, "player-disguises.yml");
		this.logger = logger;
	}

	List<PlayerDisguise> load() {
		List<PlayerDisguise> result = new ArrayList<>();
		if(!file.exists()) return result;
		YamlConfiguration yaml = new YamlConfiguration();
		try {
			yaml.loadFromString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
		} catch(Exception e) {
			File broken = new File(file.getParentFile(), "player-disguises.broken-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".yml");
			try {
				Files.move(file.toPath(), broken.toPath());
			} catch(IOException moveError) {
				logger.log(Level.SEVERE, "Could not move the unreadable player-disguises.yml aside", moveError);
			}
			logger.log(Level.SEVERE, "player-disguises.yml could not be read, so nobody is player-disguised. The file was kept as " + broken.getName(), e);
			return result;
		}
		for(String key : yaml.getKeys(false)) {
			ConfigurationSection section = yaml.getConfigurationSection(key);
			if(section == null) continue;
			try {
				UUID uuid = UUID.fromString(key);
				String realName = section.getString("real-name");
				String fakeName = section.getString("name");
				if(realName == null || fakeName == null) continue;
				PlayerDisguise disguise = new PlayerDisguise(uuid, realName, fakeName, section.getString("rank"),
						section.getString("skin-value"), section.getString("skin-signature"));
				disguise.setChatTag(section.getString("chat-tag"));
				result.add(disguise);
			} catch(IllegalArgumentException e) {
				logger.warning("Skipping an invalid entry in player-disguises.yml: " + key);
			}
		}
		return result;
	}

	/** Call on the main thread; the file is written in the background. */
	void save(Collection<PlayerDisguise> disguises) {
		YamlConfiguration yaml = new YamlConfiguration();
		for(PlayerDisguise disguise : disguises) {
			// Disguises given with /odisguise end when the player leaves: never saved.
			if(disguise.isGivenByStaff()) continue;
			String key = disguise.getUniqueId().toString();
			yaml.set(key + ".real-name", disguise.getRealName());
			yaml.set(key + ".name", disguise.getFakeName());
			yaml.set(key + ".rank", disguise.getRank());
			yaml.set(key + ".skin-value", disguise.getSkinValue());
			yaml.set(key + ".skin-signature", disguise.getSkinSignature());
			yaml.set(key + ".chat-tag", disguise.getChatTag());
		}
		String content = "# Active player disguises. Don't edit this file while the server is running.\n" + yaml.saveToString();
		writer.execute(() -> write(content));
	}

	private void write(String content) {
		File temp = new File(file.getParentFile(), "player-disguises.yml.tmp");
		try {
			Files.write(temp.toPath(), content.getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch(IOException atomicNotSupported) {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch(IOException e) {
			logger.log(Level.SEVERE, "Could not save player-disguises.yml", e);
		}
	}

	/** Finishes pending writes (plugin disable). */
	void close() {
		writer.shutdown();
		try {
			writer.awaitTermination(5, TimeUnit.SECONDS);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
