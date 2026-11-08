package de.luisagrether.idisguise.player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;

import de.luisagrether.idisguise.iDisguise;
import de.luisagrether.idisguise.api.PlayerDisguiseAsPlayerEvent;
import de.luisagrether.idisguise.io.Language;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;

/**
 * Player disguises: /disguise <name> [rank].
 * Other players see the fake name, skin and rank everywhere; on the server the player stays who they are.
 * Only on Minecraft 1.8.8 with ProtocolLib.
 */
public final class PlayerDisguiseManager implements Listener {

	public static final String PERM_STAFF = "iDisguise.player.staff";
	public static final String PERM_ANY_NAME = "iDisguise.player.anyname";
	public static final String PERM_RANK = "iDisguise.rank.";
	public static final String PERM_ADMIN = "iDisguise.admin";

	private static final Set<String> OWN_COMMANDS = new HashSet<>(java.util.Arrays.asList(
			"disguise", "d", "dis", "odisguise", "od", "undisguise", "ud", "undis", "idisguise"));
	private static final Set<String> SKIN_COMMANDS = new HashSet<>(java.util.Arrays.asList("skin", "skins"));

	private final iDisguise plugin;
	private final Map<UUID, PlayerDisguise> disguises = new ConcurrentHashMap<>();
	private final Map<UUID, String> lastTabInput = new ConcurrentHashMap<>();
	// Snapshots for the packet threads, rebuilt whenever something changes.
	private volatile List<PlayerDisguise> online = Collections.emptyList();
	private volatile List<PlayerDisguise> replacing = Collections.emptyList();

	private boolean available;
	private String unavailableReason;
	private PlayerDisguiseSettings settings;
	private PlayerDisguiseStore store;
	private PacketRewriter rewriter;
	private LuckPermsHook luckPerms;
	private TabHook tab;
	private SkinResolver skins;
	private ChatColors chatColors;
	private boolean placeholderApi;

	public PlayerDisguiseManager(iDisguise plugin) {
		this.plugin = plugin;
	}

	// ---------------------------------------------------------------- start / stop

	public void enable() {
		settings = new PlayerDisguiseSettings(plugin);
		if(!settings.enabled) {
			unavailableReason = "turned off in player-disguise.yml";
			return;
		}
		String serverPackage = Bukkit.getServer().getClass().getPackage().getName();
		if(!serverPackage.endsWith("v1_8_R3")) {
			unavailableReason = "only works on Minecraft 1.8.8";
			plugin.getLogger().info("Player disguises are off: they " + unavailableReason + ".");
			return;
		}
		if(Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
			unavailableReason = "need ProtocolLib";
			plugin.getLogger().warning("Player disguises are off: they need ProtocolLib.");
			return;
		}

		store = new PlayerDisguiseStore(plugin.getDataFolder(), plugin.getLogger());
		luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null ? LuckPermsHook.create() : null;
		tab = TabHook.create(plugin);
		skins = new SkinResolver(plugin.getLogger());
		File essentials = new File(settings.essentialsConfig);
		if(!essentials.isAbsolute()) essentials = new File(Bukkit.getWorldContainer(), settings.essentialsConfig);
		chatColors = new ChatColors(essentials);
		placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

		for(PlayerDisguise disguise : store.load()) {
			disguises.put(disguise.getUniqueId(), disguise);
		}
		rewriter = new PacketRewriter(plugin, this);
		rewriter.register();
		Bukkit.getPluginManager().registerEvents(this, plugin);
		if(placeholderApi) {
			try {
				PlaceholderHook.register(this);
			} catch(Throwable t) {
				plugin.getLogger().warning("Could not register the %idisguise_name% placeholder: " + t);
			}
		}
		available = true;

		// Players already online (plugin reload): show their disguises again.
		for(Player player : Bukkit.getOnlinePlayers()) {
			PlayerDisguise disguise = disguises.get(player.getUniqueId());
			if(disguise != null) {
				markOnline(player, disguise);
				applyLook(player, disguise);
				refresh(player);
			}
		}
		rebuildSnapshots();
	}

	public void disable() {
		if(!available) return;
		for(Player player : Bukkit.getOnlinePlayers()) {
			PlayerDisguise disguise = disguises.get(player.getUniqueId());
			if(disguise != null) resetLook(player, disguise);
		}
		rewriter.unregister();
		store.close();
		available = false;
	}

	public boolean isAvailable() {
		return available;
	}

	// ---------------------------------------------------------------- queries

	public boolean isDisguised(Player player) {
		return available && disguises.containsKey(player.getUniqueId());
	}

	public PlayerDisguise get(UUID uuid) {
		return disguises.get(uuid);
	}

	public int onlineCount() {
		return online.size();
	}

	boolean isEmpty() {
		return disguises.isEmpty();
	}

	Collection<PlayerDisguise> allOnline() {
		return online;
	}

	Collection<PlayerDisguise> replacingNames() {
		return replacing;
	}

	void setLastTabInput(UUID uuid, String text) {
		lastTabInput.put(uuid, text);
	}

	String getLastTabInput(UUID uuid) {
		return lastTabInput.get(uuid);
	}

	void debug(String message, Throwable error) {
		if(plugin.debugMode()) plugin.getLogger().log(Level.WARNING, message, error);
	}

	private void debug(String message) {
		if(plugin.debugMode()) plugin.getLogger().info("[player disguise] " + message);
	}

	private final Set<PlayerDisguise> debuggedSends = Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private final Set<String> reportedPacketErrors = ConcurrentHashMap.newKeySet();

	/** One debug line per disguise (the first time it is sent to someone). */
	void debugOnce(PlayerDisguise disguise, String message) {
		if(!plugin.debugMode()) return;
		synchronized(debuggedSends) {
			if(!debuggedSends.add(disguise)) return;
		}
		debug(message);
	}

	/** A packet couldn't be rewritten: always told once per packet type, details with debug-mode. */
	void packetError(String packet, RuntimeException error) {
		if(reportedPacketErrors.add(packet)) {
			plugin.getLogger().warning("Player disguise: could not change a " + packet + " packet (" + error
					+ "). Turn on debug-mode in config.yml for details.");
		}
		debug("Packet rewrite failed for " + packet, error);
	}

	private Language language() {
		return plugin.getLanguage();
	}

	private boolean has(CommandSender sender, String node) {
		return sender == null || plugin.hasNode(sender, node);
	}

	private boolean isStaff(Player player) {
		return has(player, PERM_STAFF);
	}

	/** Fake ranks this sender may use (tab completion). */
	public List<String> usableRanks(CommandSender sender) {
		List<String> result = new ArrayList<>();
		if(settings == null) return result;
		for(String rank : settings.ranks) {
			if(has(sender, PERM_RANK + rank)) result.add(rank);
		}
		return result;
	}

	// ---------------------------------------------------------------- disguise

	/**
	 * /disguise <name> [rank]. Checks everything, looks up rank and skin in the background and
	 * then disguises. sender == null: no messages and no permission checks (developer API).
	 */
	public void disguise(CommandSender sender, Player target, String name, String rankInput, Consumer<Boolean> done) {
		Language language = language();
		if(!available) {
			send(sender, language.DISGUISE_TYPE_NOT_SUPPORTED);
			finish(done, false);
			return;
		}
		if(!iDisguise.ACCOUNTNAME.matcher(name).matches()) {
			send(sender, language.PLAYER_DISGUISE_ACCOUNT_NAME_INVALID);
			finish(done, false);
			return;
		}
		if(plugin.isPlayerNameBlacklisted(name)) {
			send(sender, language.PLAYER_DISGUISE_ACCOUNT_NAME_BLACKLISTED);
			finish(done, false);
			return;
		}
		String chosenRank = null;
		if(rankInput != null) {
			chosenRank = settings.findRank(rankInput);
			if(chosenRank == null) {
				send(sender, language.PLAYER_DISGUISE_RANK_UNKNOWN.replace("%rank%", rankInput)
						.replace("%ranks%", String.join(", ", usableRanks(sender))));
				finish(done, false);
				return;
			}
			if(!has(sender, PERM_RANK + chosenRank)) {
				send(sender, language.PLAYER_DISGUISE_RANK_NO_PERMISSION.replace("%rank%", chosenRank));
				finish(done, false);
				return;
			}
		}
		String problem = nameProblem(target, name);
		if(problem != null) {
			send(sender, problem.replace("%name%", name));
			finish(done, false);
			return;
		}
		boolean anyName = has(sender, PERM_ANY_NAME);
		Player owner = Bukkit.getPlayerExact(name);
		if(!anyName && owner != null && owner != target) {
			send(sender, language.PLAYER_DISGUISE_NAME_ONLINE.replace("%name%", name));
			finish(done, false);
			return;
		}
		// Both would carry the same name, and 1.8 clients pick the nametag rank by name: only the real rank works.
		if(owner != null && owner != target && rankInput != null) {
			send(sender, language.PLAYER_DISGUISE_RANK_OWNER_ONLINE.replace("%name%", owner.getName()));
			finish(done, false);
			return;
		}
		// Without LuckPerms, check the server's own player list (on this thread: it isn't thread-safe).
		boolean joinedWithoutLuckPerms = luckPerms == null && !anyName && hasPlayedBefore(name, target);

		String rankArgument = chosenRank;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			UUID knownUuid = null;
			String rank = rankArgument;
			try {
				if(luckPerms != null) {
					knownUuid = luckPerms.lookupUniqueId(name).get(10, TimeUnit.SECONDS);
					if(rank == null && settings.copyRealRank && knownUuid != null) {
						String group = luckPerms.lookupPrimaryGroup(name).get(10, TimeUnit.SECONDS);
						if(settings.isCopyableGroup(group)) rank = group.toLowerCase(Locale.ENGLISH);
					}
				}
			} catch(Exception e) {
				debug("LuckPerms lookup failed for " + name, e);
			}
			boolean joined = joinedWithoutLuckPerms || (knownUuid != null && !knownUuid.equals(target.getUniqueId()));
			if(joined && !anyName) {
				Bukkit.getScheduler().runTask(plugin, () -> {
					send(sender, language.PLAYER_DISGUISE_NAME_PLAYED_BEFORE.replace("%name%", name));
					finish(done, false);
				});
				return;
			}
			SkinResolver.Skin skin = skins.resolve(name, knownUuid, settings.randomSkins);
			debug("Skin for " + name + ": " + skin.source);
			String finalRank = rank;
			Bukkit.getScheduler().runTask(plugin, () -> apply(sender, target, name, finalRank, skin, done));
		});
	}

	/** Why this name can't be used right now, or null. */
	private String nameProblem(Player target, String name) {
		for(PlayerDisguise other : disguises.values()) {
			if(other.getUniqueId().equals(target.getUniqueId())) continue;
			if(other.getFakeName().equalsIgnoreCase(name)) return language().PLAYER_DISGUISE_NAME_TAKEN;
			// The real name of someone who is disguised right now: names would get mixed up.
			if(other.getProfile() != null && other.getRealName().equalsIgnoreCase(name)) return language().PLAYER_DISGUISE_NAME_TAKEN;
		}
		return null;
	}

	private static boolean hasPlayedBefore(String name, Player target) {
		for(OfflinePlayer player : Bukkit.getOfflinePlayers()) {
			if(name.equalsIgnoreCase(player.getName()) && !player.getUniqueId().equals(target.getUniqueId())) return true;
		}
		return false;
	}

	private void apply(CommandSender sender, Player target, String name, String rank, SkinResolver.Skin skin, Consumer<Boolean> done) {
		Language language = language();
		if(!target.isOnline()) {
			finish(done, false);
			return;
		}
		String problem = nameProblem(target, name);
		if(problem != null) {
			send(sender, problem.replace("%name%", name));
			finish(done, false);
			return;
		}
		PlayerDisguiseAsPlayerEvent event = new PlayerDisguiseAsPlayerEvent(target, name);
		Bukkit.getPluginManager().callEvent(event);
		if(event.isCancelled()) {
			send(sender, language.DISGUISE_EVENT_CANCELLED);
			finish(done, false);
			return;
		}

		// Drop a mob disguise or an older player disguise first.
		PlayerDisguise previous = disguises.get(target.getUniqueId());
		if(previous != null) {
			disguises.remove(target.getUniqueId());
			resetLook(target, previous);
			rebuildSnapshots();
		}
		plugin.removeMobDisguise(target);

		PlayerDisguise disguise = new PlayerDisguise(target.getUniqueId(), target.getName(), name, rank, skin.value, skin.signature);
		if(previous != null && previous.getPreviousDisplayName() != null) {
			disguise.setPreviousDisplayName(previous.getPreviousDisplayName());
		}
		markOnline(target, disguise);
		Player owner = Bukkit.getPlayerExact(name);
		if(owner != null && owner != target) {
			disguise.setHiddenFromTab(true);
			disguise.setOwnerRank(realRankOf(owner));
		}
		disguises.put(target.getUniqueId(), disguise);
		rebuildSnapshots();
		save();

		applyLook(target, disguise);
		refresh(target);

		String shownRank = effectiveRank(disguise);
		send(sender, language.PLAYER_DISGUISED.replace("%name%", name).replace("%rank%", shownRank == null ? "-" : shownRank));
		finish(done, true);
	}

	/** Removes the player disguise. Returns false when the player had none. */
	public boolean undisguise(Player player) {
		PlayerDisguise disguise = disguises.remove(player.getUniqueId());
		if(disguise == null) return false;
		rebuildSnapshots();
		save();
		if(player.isOnline()) {
			resetLook(player, disguise);
			refresh(player);
		}
		return true;
	}

	private void markOnline(Player player, PlayerDisguise disguise) {
		boolean staff = isStaff(player);
		disguise.setOnline(staff ? settings.staff : settings.players, staff);
	}

	private void save() {
		store.save(new ArrayList<>(disguises.values()));
	}

	private void rebuildSnapshots() {
		List<PlayerDisguise> newOnline = new ArrayList<>();
		List<PlayerDisguise> newReplacing = new ArrayList<>();
		for(PlayerDisguise disguise : disguises.values()) {
			PlayerDisguiseSettings.Profile profile = disguise.getProfile();
			if(profile == null) continue;
			newOnline.add(disguise);
			if(profile.replaceNameInMessages) newReplacing.add(disguise);
		}
		online = Collections.unmodifiableList(newOnline);
		replacing = Collections.unmodifiableList(newReplacing);
	}

	/** The rank others see: the real player's rank while they are online, else the chosen or default rank. */
	private String effectiveRank(PlayerDisguise disguise) {
		if(disguise.isHiddenFromTab() && disguise.getOwnerRank() != null) return disguise.getOwnerRank();
		return disguise.getRank() != null ? disguise.getRank() : settings.defaultRank;
	}

	private String realRankOf(Player player) {
		String group = luckPerms != null ? luckPerms.primaryGroup(player) : null;
		return group != null ? group.toLowerCase(Locale.ENGLISH) : settings.defaultRank;
	}

	/** Display name (EssentialsX chat) and TAB rank. */
	private void applyLook(Player player, PlayerDisguise disguise) {
		if(disguise.getPreviousDisplayName() == null) {
			disguise.setPreviousDisplayName(player.getDisplayName());
		}
		player.setDisplayName(disguise.getFakeName());
		String rank = effectiveRank(disguise);
		if(tab != null) tab.apply(player, rank, disguise.getFakeName());
		PlayerDisguiseSettings.Profile profile = disguise.getProfile();
		if(luckPerms != null && profile != null && profile.fakeRankInChat) {
			String prefix = rank == null ? null : luckPerms.groupPrefix(rank);
			luckPerms.setFakePrefix(player, prefix == null ? "" : prefix);
		}
	}

	private void resetLook(Player player, PlayerDisguise disguise) {
		String previous = disguise.getPreviousDisplayName();
		player.setDisplayName(previous != null ? previous : player.getName());
		if(tab != null) tab.apply(player, null, null);
		if(luckPerms != null) luckPerms.setFakePrefix(player, null);
	}

	/**
	 * Makes every client fetch the player again (tab entry, name above the head, skin).
	 * Players who can't see this player (vanish) are skipped, so a vanished player stays vanished.
	 */
	private void refresh(Player player) {
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == player || !viewer.canSee(player)) continue;
			viewer.hidePlayer(player);
			viewer.showPlayer(player);
		}
		// The player's own tab entry.
		WrappedGameProfile own = WrappedGameProfile.fromPlayer(player);
		sendRaw(player, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, null, own));
		PacketContainer add = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.PLAYER_INFO);
		add.getPlayerInfoAction().write(0, PlayerInfoAction.ADD_PLAYER);
		add.getPlayerInfoDataLists().write(0, Collections.singletonList(
				new PlayerInfoData(own, 0, NativeGameMode.fromBukkit(player.getGameMode()), null)));
		sendFiltered(player, add);
	}

	// ---------------------------------------------------------------- tab entry of hidden staff

	void scheduleTabRemoval(PlayerDisguise disguise, Player viewer) {
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if(!viewer.isOnline() || !disguise.isHiddenFromTab() || disguises.get(disguise.getUniqueId()) != disguise) return;
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, disguise,
					new WrappedGameProfile(disguise.getUniqueId(), disguise.getFakeName())));
			disguise.getRemovedFor().add(viewer.getUniqueId());
		}, 40L);
	}

	void sendTabAdd(Player viewer, PlayerDisguise disguise) {
		sendFiltered(viewer, PacketRewriter.playerInfo(PlayerInfoAction.ADD_PLAYER, disguise, PacketRewriter.fakeProfile(disguise)));
	}

	/** Sent as is, without going through the rewriter. */
	void sendRaw(Player player, PacketContainer packet) {
		try {
			ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet, false);
		} catch(Exception e) {
			debug("Could not send a packet to " + player.getName(), e);
		}
	}

	private void sendFiltered(Player player, PacketContainer packet) {
		try {
			ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
		} catch(Exception e) {
			debug("Could not send a packet to " + player.getName(), e);
		}
	}

	/** Shows the staff member in tab again once the real player has left. */
	private void unhide(PlayerDisguise disguise) {
		disguise.setHiddenFromTab(false);
		disguise.setOwnerRank(null);
		Player player = Bukkit.getPlayer(disguise.getUniqueId());
		Set<UUID> viewers = new HashSet<>(disguise.getRemovedFor());
		disguise.getRemovedFor().clear();
		if(player == null) return;
		// Back to the chosen rank, and back into the nametag team under the fake name: reset the TAB
		// group first so TAB sends the team again (the real player's team left with them).
		if(tab != null) tab.apply(player, null, disguise.getFakeName());
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if(player.isOnline() && disguises.get(player.getUniqueId()) == disguise) applyLook(player, disguise);
		}, 2L);
		for(UUID uuid : viewers) {
			Player viewer = Bukkit.getPlayer(uuid);
			if(viewer == null || !viewer.canSee(player)) continue;
			viewer.hidePlayer(player);
			viewer.showPlayer(player);
		}
	}

	private void hide(PlayerDisguise disguise, Player player, Player owner) {
		disguise.setHiddenFromTab(true);
		disguise.setOwnerRank(realRankOf(owner));
		applyLook(player, disguise);
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == player) continue;
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, disguise,
					new WrappedGameProfile(disguise.getUniqueId(), disguise.getFakeName())));
			disguise.getRemovedFor().add(viewer.getUniqueId());
		}
	}

	// ---------------------------------------------------------------- messages

	private static void send(CommandSender sender, String message) {
		if(sender != null && message != null && !message.isEmpty()) sender.sendMessage(message);
	}

	private static void finish(Consumer<Boolean> done, boolean success) {
		if(done != null) done.accept(success);
	}

	/** A message that names real players on purpose (admin info, /disguise list): never name-swapped. */
	public void sendUnchanged(CommandSender sender, String message) {
		if(!(sender instanceof Player) || !available) {
			sender.sendMessage(message);
			return;
		}
		PacketContainer packet = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.CHAT);
		packet.getChatComponents().write(0, WrappedChatComponent.fromJson(ComponentSerializer.toString(TextComponent.fromLegacyText(message))));
		sendRaw((Player)sender, packet);
	}

	/** The "disguised players" part of /disguise list. */
	public List<String> listLines(CommandSender viewer) {
		Language language = language();
		List<String> lines = new ArrayList<>();
		if(!available) return lines;
		boolean admin = has(viewer, PERM_ADMIN);
		List<PlayerDisguise> shown = new ArrayList<>();
		for(PlayerDisguise disguise : online) {
			PlayerDisguiseSettings.Profile profile = disguise.getProfile();
			if(profile != null && (admin || profile.showInList)) shown.add(disguise);
		}
		shown.sort(Comparator.comparing(disguise -> disguise.getRealName().toLowerCase(Locale.ENGLISH)));
		lines.add(language.LIST_DISGUISED_PLAYERS);
		if(shown.isEmpty()) {
			lines.add(language.LIST_DISGUISED_NOBODY);
		}
		for(PlayerDisguise disguise : shown) {
			lines.add(language.LIST_DISGUISED_ENTRY.replace("%player%", disguise.getRealName()).replace("%name%", disguise.getFakeName())
					+ (admin && disguise.isStaff() ? language.LIST_DISGUISED_STAFF : ""));
		}
		return lines;
	}

	// ---------------------------------------------------------------- events

	@EventHandler(priority = EventPriority.LOWEST)
	public void onJoin(PlayerJoinEvent event) {
		Player joined = event.getPlayer();

		// Someone is disguised as the player who just joined.
		for(PlayerDisguise disguise : online) {
			if(disguise.getUniqueId().equals(joined.getUniqueId()) || !disguise.getFakeName().equalsIgnoreCase(joined.getName())) continue;
			Player disguised = Bukkit.getPlayer(disguise.getUniqueId());
			if(disguised == null) continue;
			if(disguise.isStaff()) {
				hide(disguise, disguised, joined);
			} else {
				Bukkit.getScheduler().runTask(plugin, () -> {
					if(undisguise(disguised)) {
						send(disguised, language().UNDISGUISED_REAL_PLAYER_JOINED.replace("%name%", joined.getName()));
					}
				});
			}
		}

		// The player who joined is still disguised from before.
		PlayerDisguise disguise = disguises.get(joined.getUniqueId());
		if(disguise == null) return;
		markOnline(joined, disguise);
		Player owner = Bukkit.getPlayerExact(disguise.getFakeName());
		if(owner != null && owner != joined) {
			if(disguise.isStaff()) {
				disguise.setHiddenFromTab(true);
				disguise.setOwnerRank(realRankOf(owner));
			} else {
				disguises.remove(joined.getUniqueId());
				save();
				Bukkit.getScheduler().runTaskLater(plugin, () -> send(joined,
						language().UNDISGUISED_REAL_PLAYER_JOINED.replace("%name%", disguise.getFakeName())), 20L);
			}
		}
		rebuildSnapshots();
		if(disguises.get(joined.getUniqueId()) == disguise) {
			// After EssentialsX and TAB have set the player up.
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if(joined.isOnline() && disguises.get(joined.getUniqueId()) == disguise) applyLook(joined, disguise);
			}, 5L);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		Player quit = event.getPlayer();
		lastTabInput.remove(quit.getUniqueId());
		for(PlayerDisguise disguise : disguises.values()) {
			disguise.getRemovedFor().remove(quit.getUniqueId());
		}
		PlayerDisguise own = disguises.get(quit.getUniqueId());
		if(own != null) own.setOffline();

		// A staff member disguised as the player who left shows up in tab again.
		for(PlayerDisguise disguise : online) {
			if(disguise != own && disguise.isHiddenFromTab() && disguise.getFakeName().equalsIgnoreCase(quit.getName())) {
				Bukkit.getScheduler().runTask(plugin, () -> unhide(disguise));
			}
		}
		rebuildSnapshots();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onChat(AsyncPlayerChatEvent event) {
		Player player = event.getPlayer();
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		PlayerDisguiseSettings.Profile profile = disguise == null ? null : disguise.getProfile();
		if(profile == null) return;

		if(!ChatColor.stripColor(player.getDisplayName()).contains(disguise.getFakeName())) {
			player.setDisplayName(disguise.getFakeName());
		}
		String format = event.getFormat();
		String before = format;
		if(profile.fakeRankInChat) {
			// The prefix comes from the temporary LuckPerms prefix (see applyLook). The message colour
			// of the fake rank also goes in front of the message, so it wins whatever EssentialsX
			// put before it.
			String fakeColor = chatColors.forGroup(effectiveRank(disguise));
			int message = format.indexOf("%2$s");
			if(fakeColor != null && message >= 0) {
				int start = message;
				while(start >= 2 && format.charAt(start - 2) == ChatColor.COLOR_CHAR) start -= 2;
				format = format.substring(0, start) + fakeColor + format.substring(message);
			}
			if(fakeColor != null && !fakeColor.isEmpty()) {
				event.setMessage(fakeColor + event.getMessage());
			}
		}
		if(profile.hideChatTag && placeholderApi) {
			String tag = PlaceholderHook.chatTag(player);
			if(tag != null && !tag.isEmpty()) {
				int index = format.indexOf(tag);
				if(index >= 0) format = format.substring(0, index) + format.substring(index + tag.length());
			}
		}
		try {
			event.setFormat(format);
		} catch(RuntimeException e) {
			debug("Could not change the chat format", e);
		}
		debug("Chat format of " + player.getName() + ": " + before.replace(ChatColor.COLOR_CHAR, '&') + "  ->  " + format.replace(ChatColor.COLOR_CHAR, '&'));
	}

	private static String color(String text) {
		return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onCommand(PlayerCommandPreprocessEvent event) {
		String message = event.getMessage();
		if(message.length() < 2) return;
		String[] parts = message.substring(1).split(" ", -1);
		String label = parts[0].toLowerCase(Locale.ENGLISH);
		if(label.contains(":")) label = label.substring(label.indexOf(':') + 1);

		if(SKIN_COMMANDS.contains(label) && disguises.containsKey(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
			send(event.getPlayer(), language().SKIN_BLOCKED_WHILE_DISGUISED);
			return;
		}
		if(OWN_COMMANDS.contains(label) || parts.length < 2) return;

		boolean changed = false;
		for(int i = 1; i < parts.length; i++) {
			for(PlayerDisguise disguise : online) {
				PlayerDisguiseSettings.Profile profile = disguise.getProfile();
				if(profile == null || !profile.commandsUseFakeName || !parts[i].equalsIgnoreCase(disguise.getFakeName())) continue;
				// While the real player is online, the name means the real player.
				if(Bukkit.getPlayerExact(disguise.getFakeName()) != null) continue;
				parts[i] = disguise.getRealName();
				changed = true;
				break;
			}
		}
		if(changed) event.setMessage("/" + String.join(" ", parts));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDamage(EntityDamageByEntityEvent event) {
		if(!(event.getEntity() instanceof Player)) return;
		Player victim = (Player)event.getEntity();
		Player attacker = null;
		if(event.getDamager() instanceof Player) {
			attacker = (Player)event.getDamager();
		} else if(event.getDamager() instanceof Projectile && ((Projectile)event.getDamager()).getShooter() instanceof Player) {
			attacker = (Player)((Projectile)event.getDamager()).getShooter();
		}
		if(attacker == null || attacker == victim) return;
		removeInCombat(victim);
		removeInCombat(attacker);
	}

	private void removeInCombat(Player player) {
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		PlayerDisguiseSettings.Profile profile = disguise == null ? null : disguise.getProfile();
		if(profile == null || !profile.removeInCombat) return;
		if(undisguise(player)) send(player, language().UNDISGUISED_IN_COMBAT);
	}

	/** %idisguise_name%: the name other players see. */
	String visibleName(Player player) {
		PlayerDisguise disguise = player == null ? null : disguises.get(player.getUniqueId());
		if(player == null) return "";
		return disguise != null ? disguise.getFakeName() : player.getName();
	}

}
