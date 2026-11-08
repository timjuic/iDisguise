package de.luisagrether.idisguise.player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * disguise-log.txt: every disguise start and end (player and mob disguises), so staff can find out
 * afterwards who was behind a name (reports, recordings, screenshots). One line per event:
 *   2026-11-08 18:32:05 | START | Dann1e | <uuid> | player | Marko | gold
 *   2026-11-08 18:50:12 | END   | Dann1e | <uuid> | player | Marko | -
 * Written in order on a background thread; never edited, only appended.
 */
public final class DisguiseLog {

	/** One disguise: who, as what, from when to when (end == null: still on). */
	public static final class Session {
		public final String start;
		public String end;
		public final String realName;
		public final String kind;
		public final String disguise;
		public final String rank;

		Session(String start, String realName, String kind, String disguise, String rank) {
			this.start = start;
			this.realName = realName;
			this.kind = kind;
			this.disguise = disguise;
			this.rank = rank;
		}
	}

	private final File file;
	private final Logger logger;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "iDisguise disguise-log writer");
		thread.setDaemon(true);
		return thread;
	});

	public DisguiseLog(File dataFolder, Logger logger) {
		this.file = new File(dataFolder, "disguise-log.txt");
		this.logger = logger;
	}

	public void start(String realName, UUID uuid, String kind, String disguise, String rank) {
		write("START", realName, uuid, kind, disguise, rank);
	}

	public void end(String realName, UUID uuid, String kind, String disguise) {
		write("END  ", realName, uuid, kind, disguise, null);
	}

	private void write(String event, String realName, UUID uuid, String kind, String disguise, String rank) {
		String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " | " + event + " | " + realName + " | " + uuid
				+ " | " + kind + " | " + disguise + " | " + (rank == null || rank.isEmpty() ? "-" : rank) + System.lineSeparator();
		writer.execute(() -> {
			try {
				Files.write(file.toPath(), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch(IOException e) {
				logger.log(Level.WARNING, "Could not write disguise-log.txt", e);
			}
		});
	}

	/**
	 * Sessions, oldest first. Read from a background thread (the file can get long).
	 * fakeName != null: only player disguises with that name. realName != null: only that player.
	 */
	public List<Session> sessions(String fakeName, String realName) {
		List<Session> result = new ArrayList<>();
		if(!file.isFile()) return result;
		List<String> lines;
		try {
			lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		} catch(IOException e) {
			logger.log(Level.WARNING, "Could not read disguise-log.txt", e);
			return result;
		}
		List<Session> open = new ArrayList<>();
		for(String line : lines) {
			String[] parts = line.split(" \\| ");
			if(parts.length < 7) continue;
			String time = parts[0].trim();
			String event = parts[1].trim();
			String real = parts[2].trim();
			String kind = parts[4].trim();
			String disguise = parts[5].trim();
			if(fakeName != null && !(kind.equals("player") && disguise.equalsIgnoreCase(fakeName))) continue;
			if(realName != null && !real.equalsIgnoreCase(realName)) continue;
			if(event.equals("START")) {
				Session session = new Session(time, real, kind, disguise, parts[6].trim());
				result.add(session);
				open.add(session);
			} else if(event.equals("END")) {
				for(int i = open.size() - 1; i >= 0; i--) {
					Session session = open.get(i);
					if(session.realName.equalsIgnoreCase(real) && session.kind.equals(kind) && session.disguise.equalsIgnoreCase(disguise)) {
						session.end = time;
						open.remove(i);
						break;
					}
				}
			}
		}
		return result;
	}

	/** The last <limit> sessions, newest first. */
	public static List<Session> newest(List<Session> sessions, int limit) {
		List<Session> copy = new ArrayList<>(sessions);
		Collections.reverse(copy);
		return copy.subList(0, Math.min(limit, copy.size()));
	}

	public void close() {
		writer.shutdown();
		try {
			writer.awaitTermination(5, TimeUnit.SECONDS);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
