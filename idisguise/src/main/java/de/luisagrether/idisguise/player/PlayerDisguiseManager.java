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
	public static final String PERM_RANDOM = "iDisguise.random";
	/** See who is disguised as whom in /disguise list (admins always do). */
	public static final String PERM_LIST_PLAYERS = "iDisguise.listplayers";
	public static final String PERM_REVEAL = "iDisguise.reveal";
	public static final String PERM_WHOIS = "iDisguise.whois";
	/** rankInput meaning "explicitly no rank" (the default rank, never a copied one). */
	public static final String NO_RANK = "\u0000no-rank";

	private static final Set<String> OWN_COMMANDS = new HashSet<>(java.util.Arrays.asList(
			"disguise", "d", "dis", "odisguise", "od", "undisguise", "ud", "undis", "idisguise"));
	private static final Set<String> VANISH_EVENTS = new HashSet<>(java.util.Arrays.asList(
			"de.myzelyam.api.vanish.PlayerShowEvent", "de.myzelyam.api.vanish.PlayerHideEvent"));

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
	private boolean skinsRestorer;
	private RandomNames randomNames;
	private DisguiseLog log;
	// Staff who see through disguises (only on their own screen).
	private final Set<UUID> revealPlayers = ConcurrentHashMap.newKeySet();
	private org.bukkit.scheduler.BukkitTask checkTask;
	private org.bukkit.scheduler.BukkitTask revealTask;
	private TabLook tabLook;
	// viewer -> disguised player -> how TAB would show the real player (staff with reveal on)
	private final Map<UUID, Map<UUID, TabLook.Look>> revealLooks = new ConcurrentHashMap<>();
	// viewer -> TAB team name -> the disguised player in it, and TAB's last settings for the team
	private final Map<UUID, Map<String, TeamState>> teams = new ConcurrentHashMap<>();

	static final class TeamState {
		final UUID player;
		volatile PacketContainer info;

		TeamState(UUID player) {
			this.player = player;
		}
	}

	public PlayerDisguiseManager(iDisguise plugin) {
		this.plugin = plugin;
	}

	// ---------------------------------------------------------------- start / stop

	public void enable() {
		// The log also records mob disguises, so it exists even when player disguises are off.
		log = new DisguiseLog(plugin.getDataFolder(), plugin.getLogger());
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
		luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null ? LuckPermsHook.create(plugin.getLogger()) : null;
		tab = TabHook.create(plugin);
		skins = new SkinResolver(plugin.getLogger());
		File essentials = new File(settings.essentialsConfig);
		if(!essentials.isAbsolute()) essentials = new File(Bukkit.getWorldContainer(), settings.essentialsConfig);
		chatColors = new ChatColors(essentials);
		placeholderApi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
		skinsRestorer = Bukkit.getPluginManager().getPlugin("SkinsRestorer") != null;
		debug("Hooks: LuckPerms " + (luckPerms != null ? "yes" : "NO") + ", TAB " + (tab != null ? "yes" : "NO")
				+ ", SkinsRestorer " + (Bukkit.getPluginManager().getPlugin("SkinsRestorer") != null ? "yes" : "NO")
				+ ", PlaceholderAPI " + (placeholderApi ? "yes" : "NO")
				+ ", EssentialsX chat colours " + (essentials.isFile() ? "read from " + essentials.getPath() : "NOT FOUND at " + essentials.getPath()));

		for(PlayerDisguise disguise : store.load()) {
			disguises.put(disguise.getUniqueId(), disguise);
		}
		randomNames = new RandomNames(plugin, settings.randomNamePool, name -> {
			if(luckPerms == null) return false;
			try {
				return luckPerms.lookupUniqueId(name).get(10, TimeUnit.SECONDS) != null;
			} catch(Exception e) {
				return true; // unsure: don't use it
			}
		});
		randomNames.refill();
		Bukkit.getPluginManager().registerEvents(new RankMenu.Clicks(plugin), plugin);
		hookVanishEvents();
		tabLook = new TabLook();
		checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 600L, 600L);
		revealTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateRevealLooks, 20L, 20L);
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
				removeRealEntry(player);
				refresh(player);
			}
		}
		rebuildSnapshots();
	}

	public void disable() {
		if(log != null) log.close();
		if(!available) return;
		checkTask.cancel();
		revealTask.cancel();
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
			if(canUseRank(sender, rank)) result.add(rank);
		}
		return result;
	}

	/** iDisguise.rank.<rank>, or the group nodes iDisguise.rank.players / .staff / .* */
	private boolean canUseRank(CommandSender sender, String rank) {
		if(has(sender, PERM_RANK + rank) || has(sender, PERM_RANK + "*")) return true;
		return settings.isStaffRank(rank) ? has(sender, PERM_RANK + "staff") : has(sender, PERM_RANK + "players");
	}

	/** Other plugins (CombatLogX ...) see player disguises too. */
	public boolean visibleToOtherPlugins() {
		return settings != null && settings.visibleToOtherPlugins;
	}

	/** Whether /disguise list shows this sender who is disguised as whom. */
	public boolean canListPlayers(CommandSender sender) {
		return available && (has(sender, PERM_ADMIN) || has(sender, PERM_LIST_PLAYERS));
	}

	/** The rank others see on this disguised player (for the status line), or null. */
	public String shownRank(Player player) {
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		return disguise == null ? null : effectiveRank(disguise);
	}

	public boolean canUseRandom(CommandSender sender) {
		return available && has(sender, PERM_RANDOM);
	}

	/**
	 * /disguise <name> [rank] from a player for themselves: without a rank, a menu with the ranks
	 * they may use opens (when there is more than one and the real player isn't online).
	 */
	public void disguiseCommand(CommandSender sender, Player target, String name, String rankInput, boolean self, Consumer<Boolean> done) {
		Player owner = Bukkit.getPlayerExact(name);
		boolean menu = available && rankInput == null && self && sender instanceof Player
				&& (owner == null || owner == target) && !usableRanks(sender).isEmpty()
				&& iDisguise.ACCOUNTNAME.matcher(name).matches() && !"random".equalsIgnoreCase(name);
		if(!menu) {
			disguise(sender, target, name, rankInput, done);
			return;
		}
		// Check the name first: the menu only opens for a name that can be used.
		// Otherwise the normal path runs and says why not.
		String problem = nameProblem(sender, target, name);
		boolean blockKnown = !has(sender, PERM_ANY_NAME) && settings.blockKnownNamesForPlayers;
		if(problem != null || plugin.isPlayerNameBlacklisted(name)
				|| (luckPerms == null && blockKnown && hasPlayedBefore(name, target))) {
			disguise(sender, target, name, rankInput, done);
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			String realRank = null;
			boolean known = false;
			try {
				if(luckPerms != null) {
					UUID uuid = luckPerms.lookupUniqueId(name).get(10, TimeUnit.SECONDS);
					known = uuid != null && !uuid.equals(target.getUniqueId());
					if(uuid != null && settings.copyRealRank) {
						String group = luckPerms.lookupPrimaryGroup(name).get(10, TimeUnit.SECONDS);
						if(settings.isCopyableGroup(group)) realRank = group.toLowerCase(Locale.ENGLISH);
					}
				}
			} catch(Exception e) {
				debug("LuckPerms lookup failed for " + name, e);
			}
			boolean refused = known && blockKnown;
			String finalRealRank = realRank;
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(!target.isOnline()) return;
				if(refused) {
					disguise(sender, target, name, rankInput, done);
				} else {
					openRankMenu(target, name, finalRealRank, done);
				}
			});
		});
	}

	private void openRankMenu(Player player, String name, String realRank, Consumer<Boolean> done) {
		Language language = language();
		List<RankMenu.Entry> playerEntries = new ArrayList<>();
		List<RankMenu.Entry> staffEntries = new ArrayList<>();
		// "No rank" first, like the lowest rank.
		playerEntries.add(new RankMenu.Entry(NO_RANK, RankMenu.item(settings.menuItem("no-rank"), ChatColor.WHITE + name,
				RankMenu.lines(language.RANK_MENU_RANK_LORE))));
		for(String rank : usableRanks(player)) {
			RankMenu.Entry entry = new RankMenu.Entry(rank, rankItem(rank, name, null));
			(settings.isStaffRank(rank) ? staffEntries : playerEntries).add(entry);
		}
		RankMenu.Entry real = realRank == null ? null : new RankMenu.Entry(null,
				rankItem(realRank, name, language.RANK_MENU_REAL_RANK.replace("%name%", name)));
		RankMenu.Entry cancel = new RankMenu.Entry(RankMenu.CANCEL, RankMenu.item(settings.menuItem("cancel"), language.RANK_MENU_CANCEL,
				RankMenu.lines(language.RANK_MENU_CANCEL_LORE)));
		org.bukkit.inventory.ItemStack header = RankMenu.item(settings.menuItem("header"), language.RANK_MENU_HEADER.replace("%name%", name),
				RankMenu.lines(language.RANK_MENU_HEADER_LORE));
		org.bukkit.inventory.ItemStack frame = RankMenu.item(settings.menuItem("frame"), " ", null);
		new RankMenu(language.RANK_MENU_TITLE.replace("%name%", name), header, frame, playerEntries, staffEntries, real, cancel,
				choice -> disguise(player, player, name, choice, done)).open(player);
	}

	/** A rank in the menu: named the way others will see you ("[Gold] Notch"). firstLine: optional extra lore line on top. */
	private org.bukkit.inventory.ItemStack rankItem(String rank, String name, String firstLine) {
		String prefix = color(luckPerms == null ? null : luckPerms.groupPrefix(rank)).trim();
		String title = (ChatColor.stripColor(prefix).trim().isEmpty() ? ChatColor.WHITE + rank + " " : prefix + " ") + ChatColor.WHITE + name;
		List<String> lore = RankMenu.lines(language().RANK_MENU_RANK_LORE);
		if(firstLine != null) lore.add(0, firstLine);
		return RankMenu.item(settings.menuItem(rank), title, lore);
	}

	/** /disguise random: a real Minecraft account's name and skin, and a random rank the player may use. */
	public void disguiseRandom(CommandSender sender, Player target, Consumer<Boolean> done) {
		if(!available || randomNames == null) {
			send(sender, language().DISGUISE_TYPE_NOT_SUPPORTED);
			finish(done, false);
			return;
		}
		String name = randomNames.take(candidate -> {
			if(plugin.isPlayerNameBlacklisted(candidate) || nameProblem(sender, target, candidate) != null) return false;
			if(Bukkit.getPlayerExact(candidate) != null) return false;
			return luckPerms != null || !hasPlayedBefore(candidate, target);
		});
		if(name == null) {
			send(sender, language().RANDOM_NOT_READY);
			finish(done, false);
			return;
		}
		// Every rank the player may use has the same chance, "no rank" counts as one of them.
		List<String> choices = new ArrayList<>(usableRanks(sender));
		choices.add(NO_RANK);
		String rank = choices.get(new java.util.Random().nextInt(choices.size()));
		disguise(sender, target, name, rank, done);
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
		if(!iDisguise.ACCOUNTNAME.matcher(name).matches() || "random".equalsIgnoreCase(name)) {
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
		boolean noRank = NO_RANK.equals(rankInput);
		if(noRank) rankInput = null;
		if(rankInput != null) {
			chosenRank = settings.findRank(rankInput);
			if(chosenRank == null) {
				send(sender, language.PLAYER_DISGUISE_RANK_UNKNOWN.replace("%rank%", rankInput)
						.replace("%ranks%", String.join(", ", usableRanks(sender))));
				finish(done, false);
				return;
			}
			if(!canUseRank(sender, chosenRank)) {
				send(sender, language.PLAYER_DISGUISE_RANK_NO_PERMISSION.replace("%rank%", chosenRank));
				finish(done, false);
				return;
			}
		}
		String problem = nameProblem(sender, target, name);
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
		boolean blockKnown = !anyName && settings.blockKnownNamesForPlayers;
		boolean joinedWithoutLuckPerms = luckPerms == null && blockKnown && hasPlayedBefore(name, target);

		String rankArgument = chosenRank;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			UUID knownUuid = null;
			String rank = rankArgument;
			try {
				if(luckPerms != null) {
					knownUuid = luckPerms.lookupUniqueId(name).get(10, TimeUnit.SECONDS);
					if(rank == null && !noRank && settings.copyRealRank && knownUuid != null) {
						String group = luckPerms.lookupPrimaryGroup(name).get(10, TimeUnit.SECONDS);
						if(settings.isCopyableGroup(group)) rank = group.toLowerCase(Locale.ENGLISH);
					}
				}
			} catch(Exception e) {
				debug("LuckPerms lookup failed for " + name, e);
			}
			boolean joined = joinedWithoutLuckPerms || (knownUuid != null && !knownUuid.equals(target.getUniqueId()));
			if(joined && blockKnown) {
				Bukkit.getScheduler().runTask(plugin, () -> {
					send(sender, language.PLAYER_DISGUISE_NAME_PLAYED_BEFORE.replace("%name%", name));
					finish(done, false);
				});
				return;
			}
			SkinResolver.Skin skin = skins.resolve(name, knownUuid, settings.randomSkins);
			debug("Skin for " + name + ": " + skin.source);
			String finalRank = rank;
			UUID finalUuid = knownUuid;
			Bukkit.getScheduler().runTask(plugin, () -> apply(sender, target, name, finalRank, skin, finalUuid, done));
		});
	}

	/** Why this name can't be used right now, or null. */
	private String nameProblem(CommandSender sender, Player target, String name) {
		for(PlayerDisguise other : disguises.values()) {
			if(other.getUniqueId().equals(target.getUniqueId())) continue;
			if(other.getFakeName().equalsIgnoreCase(name)) return language().PLAYER_DISGUISE_NAME_TAKEN;
			// The real name of someone who is disguised right now: names would get mixed up. The message
			// must not tell that this player is disguised: players get the usual "has played here" one.
			if(other.getProfile() != null && other.getRealName().equalsIgnoreCase(name)) {
				boolean blockKnown = !has(sender, PERM_ANY_NAME) && settings.blockKnownNamesForPlayers;
				return blockKnown ? language().PLAYER_DISGUISE_NAME_PLAYED_BEFORE : language().PLAYER_DISGUISE_NAME_UNAVAILABLE;
			}
		}
		return null;
	}

	private static boolean hasPlayedBefore(String name, Player target) {
		for(OfflinePlayer player : Bukkit.getOfflinePlayers()) {
			if(name.equalsIgnoreCase(player.getName()) && !player.getUniqueId().equals(target.getUniqueId())) return true;
		}
		return false;
	}

	private void apply(CommandSender sender, Player target, String name, String rank, SkinResolver.Skin skin, UUID knownUuid, Consumer<Boolean> done) {
		Language language = language();
		if(!target.isOnline()) {
			finish(done, false);
			return;
		}
		String problem = nameProblem(sender, target, name);
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
			removeDisguiseEntry(target, previous);
			rebuildSnapshots();
		}
		plugin.removeMobDisguise(target);

		PlayerDisguise disguise = new PlayerDisguise(target.getUniqueId(), target.getName(), name, rank, skin.value, skin.signature);
		if(previous != null && previous.getPreviousDisplayName() != null) {
			disguise.setPreviousDisplayName(previous.getPreviousDisplayName());
		}
		markOnline(target, disguise);
		Player owner = Bukkit.getPlayerExact(name);
		// The chat tag of the player whose name this is (none for a made-up name).
		if(placeholderApi) {
			String tag = null;
			try {
				if(owner != null && owner != target) {
					tag = PlaceholderHook.chatTag(owner);
				} else if(knownUuid != null) {
					tag = PlaceholderHook.chatTag(Bukkit.getOfflinePlayer(knownUuid));
				}
			} catch(Throwable t) {
				debug("Could not read the chat tag of " + name, t);
			}
			disguise.setChatTag(tag == null ? "" : tag);
			debug("Chat tag for " + name + ": '" + disguise.getChatTag() + "'");
		}
		if(owner != null && owner != target) {
			disguise.setHiddenFromTab(true);
			disguise.setOwnerRank(realRankOf(owner));
		}
		disguises.put(target.getUniqueId(), disguise);
		rebuildSnapshots();
		save();

		applyLook(target, disguise);
		removeRealEntry(target);
		refresh(target);
		log.start(target.getName(), target.getUniqueId(), "player", name, effectiveRank(disguise));
		if(previous != null) log.end(target.getName(), target.getUniqueId(), "player", previous.getFakeName());

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
		log.end(player.getName(), player.getUniqueId(), "player", disguise.getFakeName());
		if(player.isOnline()) {
			resetLook(player, disguise);
			removeDisguiseEntry(player, disguise);
			refresh(player);
		}
		return true;
	}

	/**
	 * Other players' clients drop the player's normal tab entry before the disguise is added (it has
	 * its own ID). Sent past the packet rewriter, which would otherwise turn it into the disguise ID.
	 */
	private void removeRealEntry(Player player) {
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == player) continue;
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, null,
					new WrappedGameProfile(player.getUniqueId(), player.getName())));
		}
	}

	/**
	 * Rebuilds a disguised player's tab entry and body for everyone who can see them, after a vanish
	 * plugin hid / showed them with its own packets (those bypass iDisguise).
	 */
	void repair(Player player) {
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		if(!player.isOnline() || disguise == null) return;
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == player || !viewer.canSee(player)) continue;
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, disguise,
					new WrappedGameProfile(disguise.getFakeUniqueId(), disguise.getFakeName())));
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, null,
					new WrappedGameProfile(player.getUniqueId(), player.getName())));
			disguise.getRemovedFor().remove(viewer.getUniqueId());
			viewer.hidePlayer(player);
			viewer.showPlayer(player);
		}
	}

	/** PremiumVanish: rebuild a disguised player right after they vanish / appear. */
	@SuppressWarnings("unchecked")
	private void hookVanishEvents() {
		for(String className : VANISH_EVENTS) {
			try {
				Class<? extends org.bukkit.event.Event> eventClass = (Class<? extends org.bukkit.event.Event>)Class.forName(className);
				Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR, (listener, event) -> {
					try {
						Object player = event.getClass().getMethod("getPlayer").invoke(event);
						if(player instanceof Player && disguises.containsKey(((Player)player).getUniqueId())) {
							Bukkit.getScheduler().runTaskLater(plugin, () -> repair((Player)player), 2L);
							Bukkit.getScheduler().runTaskLater(plugin, () -> repair((Player)player), 20L);
						}
					} catch(ReflectiveOperationException e) {
						debug("Could not read the vanish event", e);
					}
				}, plugin);
			} catch(ClassNotFoundException e) {
				// PremiumVanish isn't installed
			}
		}
	}

	/** Other players' clients drop the tab entry the disguise had (it has its own ID). */
	private void removeDisguiseEntry(Player player, PlayerDisguise disguise) {
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == player) continue;
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, disguise,
					new WrappedGameProfile(disguise.getFakeUniqueId(), disguise.getFakeName())));
		}
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
		// A moment after the refresh (hide / show): TAB reacts to both, and doing them at the same time
		// made it send the nametag team twice ("team already exists" on the client).
		if(tab != null) {
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if(player.isOnline() && disguises.get(player.getUniqueId()) == disguise) {
					tab.apply(player, effectiveRank(disguise), disguise.getFakeName());
				}
			}, 10L);
		}
	}

	private void resetLook(Player player, PlayerDisguise disguise) {
		String previous = disguise.getPreviousDisplayName();
		player.setDisplayName(previous != null ? previous : player.getName());
		if(tab != null) {
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if(player.isOnline() && !disguises.containsKey(player.getUniqueId())) tab.apply(player, null, null);
			}, 10L);
		}
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
					new WrappedGameProfile(disguise.getFakeUniqueId(), disguise.getFakeName())));
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
					new WrappedGameProfile(disguise.getFakeUniqueId(), disguise.getFakeName())));
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
		if(!admin && !has(viewer, PERM_LIST_PLAYERS)) return lines;
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
		// No longer allowed to use player disguises (e.g. a rank ran out): the disguise ends.
		if(!plugin.canDisguiseAsPlayer(joined)) {
			disguises.remove(joined.getUniqueId());
			save();
			log.end(joined.getName(), joined.getUniqueId(), "player", disguise.getFakeName());
			Bukkit.getScheduler().runTaskLater(plugin, () -> send(joined, language().UNDISGUISED_NO_ACCESS), 20L);
			return;
		}
		markOnline(joined, disguise);
		Player owner = Bukkit.getPlayerExact(disguise.getFakeName());
		if(owner != null && owner != joined) {
			if(disguise.isStaff()) {
				disguise.setHiddenFromTab(true);
				disguise.setOwnerRank(realRankOf(owner));
			} else {
				disguises.remove(joined.getUniqueId());
				save();
				log.end(joined.getName(), joined.getUniqueId(), "player", disguise.getFakeName());
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
		revealPlayers.remove(quit.getUniqueId());
		revealLooks.remove(quit.getUniqueId());
		teams.remove(quit.getUniqueId());
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

	// HIGH, not HIGHEST: Factions (relation-coloured faction tags) builds its own copy of the
	// message for every player at HIGHEST, so the change has to be made before that.
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
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
		if(profile.fakeRankInChat && luckPerms != null) {
			// The real prefix is swapped for the fake rank's prefix right in the chat line (LuckPerms
			// may keep giving the real one to other plugins).
			// The real prefix right now: the player may have got a new rank since disguising.
			String realPrefix = color(luckPerms.prefix(player));
			String fakePrefix = color(luckPerms.groupPrefix(effectiveRank(disguise)));
			int index = realPrefix.isEmpty() ? -1 : format.indexOf(realPrefix);
			if(index >= 0) {
				if(!realPrefix.equals(fakePrefix)) {
					format = format.substring(0, index) + fakePrefix + format.substring(index + realPrefix.length());
				}
			} else if(!ChatColor.stripColor(fakePrefix).trim().isEmpty()) {
				// The player has no prefix of their own: put the fake one in front of the name
				// (before the colour codes of the name), where a real prefix would be.
				format = insertBeforeName(format, fakePrefix);
			}
		}
		if(profile.fakeRankInChat) {
			// The message colour of the fake rank also goes in front of the message, so it wins
			// whatever EssentialsX put before it.
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
		if(profile.hideChatTag) {
			// Your own DeluxeTags tag is replaced by the tag of the player whose name you use
			// (live while that player is online, none for a made-up name).
			String shownTag = disguise.getChatTag() == null ? "" : disguise.getChatTag();
			if(placeholderApi) {
				Player owner = Bukkit.getPlayerExact(disguise.getFakeName());
				if(owner != null && owner != player) {
					String ownerTag = PlaceholderHook.chatTag(owner);
					shownTag = ownerTag == null ? "" : ownerTag;
				}
			}
			String ownTag = placeholderApi ? PlaceholderHook.chatTag(player) : null;
			if(format.contains("{deluxetags_tag}")) {
				// DeluxeTags hasn't filled in its tag yet at this point.
				format = format.replace("{deluxetags_tag}", shownTag);
			} else if(ownTag != null && !ownTag.isEmpty() && format.contains(ownTag)) {
				int index = format.indexOf(ownTag);
				format = format.substring(0, index) + shownTag + format.substring(index + ownTag.length());
			}
		}
		try {
			event.setFormat(format);
		} catch(RuntimeException e) {
			debug("Could not change the chat format", e);
		}
		debug("Chat format of " + player.getName() + ": " + before.replace(ChatColor.COLOR_CHAR, '&') + "  ->  " + format.replace(ChatColor.COLOR_CHAR, '&'));
	}

	/** "... &7%1$s ..." -> "... [Gold] &7%1$s ...", without leaving two spaces in a row. */
	private static String insertBeforeName(String format, String prefix) {
		int name = format.indexOf("%1$s");
		if(name < 0) return format;
		int at = name;
		while(at >= 2 && format.charAt(at - 2) == ChatColor.COLOR_CHAR) at -= 2;
		String before = format.substring(0, at);
		// An empty {PREFIX} leaves a space behind: drop it when another space comes right before it.
		String stripped = ChatColor.stripColor(before);
		if(before.endsWith(" ") && stripped.length() >= 2 && stripped.charAt(stripped.length() - 2) == ' ') {
			before = before.substring(0, before.length() - 1);
		}
		boolean needsSpace = !ChatColor.stripColor(before).isEmpty() && !ChatColor.stripColor(before).endsWith(" ");
		return before + (needsSpace ? " " : "") + prefix + " " + format.substring(at);
	}

	/**
	 * The online player Bukkit would find for this text (like Bukkit.getPlayer): the exact name, or
	 * else the shortest name starting with it. Null when nobody matches.
	 */
	private static Player matchingPlayer(String text) {
		if(text.length() < 2 || !text.matches("[A-Za-z0-9_]+")) return null;
		String lower = text.toLowerCase(Locale.ENGLISH);
		Player best = null;
		int bestDelta = Integer.MAX_VALUE;
		for(Player player : Bukkit.getOnlinePlayers()) {
			String name = player.getName().toLowerCase(Locale.ENGLISH);
			if(name.equals(lower)) return player;
			if(name.startsWith(lower)) {
				int delta = name.length() - lower.length();
				if(delta < bestDelta) {
					best = player;
					bestDelta = delta;
				}
			}
		}
		return best;
	}

	/** The server log always shows who really wrote a message (players never see this line). */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onChatLog(AsyncPlayerChatEvent event) {
		PlayerDisguise disguise = disguises.get(event.getPlayer().getUniqueId());
		if(disguise == null || disguise.getProfile() == null) return;
		plugin.getLogger().info("[Disguised] " + disguise.getRealName() + " as " + disguise.getFakeName() + ": "
				+ ChatColor.stripColor(event.getMessage()));
	}

	/**
	 * Every 30 seconds: a player who lost iDisguise.disguise.PLAYER is undisguised, and the disguise
	 * rank is put back into TAB if TAB lost it (e.g. after /tab reload).
	 */
	private void check() {
		for(PlayerDisguise disguise : new ArrayList<>(online)) {
			Player player = Bukkit.getPlayer(disguise.getUniqueId());
			if(player == null) continue;
			if(!plugin.canDisguiseAsPlayer(player)) {
				if(undisguise(player)) send(player, language().UNDISGUISED_NO_ACCESS);
				continue;
			}
			if(tab != null) tab.ensure(player, effectiveRank(disguise), disguise.getFakeName());
		}
	}

	/** An NPC copy of a disguised player (e.g. CombatLogX on logout) with the real name: the disguise for it, or null. */
	PlayerDisguise disguiseOfCopy(String profileName) {
		if(profileName == null) return null;
		for(PlayerDisguise disguise : replacing) {
			if(disguise.getRealName().equalsIgnoreCase(profileName)) return disguise;
		}
		return null;
	}

	// ---------------------------------------------------------------- staff: reveal

	PlayerDisguiseSettings settings() {
		return settings;
	}

	boolean isRevealingPlayers(UUID viewer) {
		return revealPlayers.contains(viewer);
	}

	/** /disguise reveal: toggles seeing through player disguises, on the sender's own screen only. */
	public void revealCommand(CommandSender sender, String[] args) {
		Language language = language();
		if(!(sender instanceof Player)) {
			sender.sendMessage(language.CONSOLE_USE_COMMAND);
			return;
		}
		Player viewer = (Player)sender;
		if(!available || !has(sender, PERM_REVEAL)) {
			send(sender, language.DISGUISE_NO_PERMISSION);
			return;
		}
		{
			boolean on = revealPlayers.add(viewer.getUniqueId());
			if(!on) revealPlayers.remove(viewer.getUniqueId());
			if(on) {
				updateRevealLooks();
			} else {
				revealLooks.remove(viewer.getUniqueId());
			}
			for(PlayerDisguise disguise : online) {
				Player disguised = Bukkit.getPlayer(disguise.getUniqueId());
				if(disguised == null || disguised == viewer) continue;
				// Drop the entry the viewer has now, then let the server send the player again.
				sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, disguise, on
						? new WrappedGameProfile(disguise.getFakeUniqueId(), disguise.getFakeName())
						: new WrappedGameProfile(disguised.getUniqueId(), disguised.getName())));
				disguise.getRemovedFor().remove(viewer.getUniqueId());
				if(viewer.canSee(disguised)) {
					viewer.hidePlayer(disguised);
					viewer.showPlayer(disguised);
				}
				resendTeam(viewer, disguise, on);
			}
			send(sender, on ? language.REVEAL_PLAYERS_ON : language.REVEAL_PLAYERS_OFF);
		}
	}

	/** How the viewer (reveal players on) should see this disguised player, or null. */
	TabLook.Look revealLook(UUID viewer, UUID disguised) {
		Map<UUID, TabLook.Look> looks = revealLooks.get(viewer);
		return looks == null ? null : looks.get(disguised);
	}

	/**
	 * Every second (main thread, placeholders need it): the real look of every disguised player for
	 * every staff member with reveal on. Changes are sent straight away.
	 */
	private void updateRevealLooks() {
		if(revealPlayers.isEmpty() || tabLook == null) return;
		for(UUID viewerId : revealPlayers) {
			Player viewer = Bukkit.getPlayer(viewerId);
			if(viewer == null) continue;
			Map<UUID, TabLook.Look> looks = revealLooks.computeIfAbsent(viewerId, id -> new ConcurrentHashMap<>());
			for(PlayerDisguise disguise : online) {
				Player target = Bukkit.getPlayer(disguise.getUniqueId());
				if(target == null || target == viewer) continue;
				TabLook.Look look;
				try {
					look = tabLook.look(viewer, target, luckPerms != null ? luckPerms.primaryGroup(target) : null);
				} catch(RuntimeException e) {
					debug("Could not build the real look of " + target.getName(), e);
					continue;
				}
				TabLook.Look before = looks.put(target.getUniqueId(), look);
				if(before != null && !before.tabLine.equals(look.tabLine)) {
					sendRaw(viewer, PacketRewriter.displayName(target, look.tabLine));
				}
				if(before != null && !look.sameTag(before)) {
					resendTeam(viewer, disguise, true);
				}
			}
		}
	}

	/** Remembers which TAB team a disguised player is in, per viewer (from the packets TAB sends). */
	void trackTeam(UUID viewer, PacketContainer packet) {
		String team = packet.getStrings().readSafely(0);
		Integer mode = packet.getIntegers().readSafely(1);
		if(team == null || mode == null) return;
		Map<String, TeamState> states = teams.computeIfAbsent(viewer, id -> new ConcurrentHashMap<>());
		if(mode == 1) {
			states.remove(team);
			return;
		}
		if(mode == 0 || mode == 3) {
			@SuppressWarnings("rawtypes")
			Collection names = packet.getSpecificModifier(Collection.class).readSafely(0);
			if(names != null) {
				for(Object name : names) {
					for(PlayerDisguise disguise : online) {
						if(disguise.getRealName().equalsIgnoreCase(String.valueOf(name)) || disguise.getFakeName().equalsIgnoreCase(String.valueOf(name))) {
							states.put(team, new TeamState(disguise.getUniqueId()));
						}
					}
				}
			}
		}
		TeamState state = states.get(team);
		if(state != null && (mode == 0 || mode == 2)) state.info = packet.deepClone();
	}

	/** The TAB team of a disguised player as this viewer knows it, or null. */
	TeamState teamOf(UUID viewer, String team) {
		Map<String, TeamState> states = teams.get(viewer);
		return states == null ? null : states.get(team);
	}

	/**
	 * Puts the right name into the disguised player's TAB team for this viewer and sends the team's
	 * look again: the real name and rank (reveal on) or the disguise (reveal off).
	 */
	private void resendTeam(Player viewer, PlayerDisguise disguise, boolean reveal) {
		Map<String, TeamState> states = teams.get(viewer.getUniqueId());
		if(states == null) return;
		for(Map.Entry<String, TeamState> entry : states.entrySet()) {
			TeamState state = entry.getValue();
			if(!state.player.equals(disguise.getUniqueId()) || state.info == null) continue;
			PacketContainer join = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.SCOREBOARD_TEAM);
			join.getStrings().write(0, entry.getKey());
			join.getIntegers().write(1, 3);
			join.getSpecificModifier(Collection.class).write(0, new ArrayList<>(java.util.Collections.singletonList(
					reveal || disguise.isHiddenFromTab() ? disguise.getRealName() : disguise.getFakeName())));
			sendRaw(viewer, join);
			PacketContainer update = state.info.deepClone();
			update.getIntegers().write(1, 2);
			update.getSpecificModifier(Collection.class).write(0, new ArrayList<String>());
			TabLook.Look look = reveal ? revealLook(viewer.getUniqueId(), disguise.getUniqueId()) : null;
			if(look != null) {
				update.getStrings().write(2, cut(look.tagPrefix, 16));
				update.getStrings().write(3, cut(look.tagSuffix, 16));
			}
			sendRaw(viewer, update);
		}
	}

	static String cut(String text, int length) {
		if(text == null) return "";
		String result = text.length() > length ? text.substring(0, length) : text;
		if(result.endsWith(String.valueOf(ChatColor.COLOR_CHAR))) result = result.substring(0, result.length() - 1);
		return result;
	}

	// ---------------------------------------------------------------- staff: whois / history

	/** Mob disguises go into the same log. */
	public void logMob(Player player, org.bukkit.entity.EntityType type, boolean start) {
		if(log == null) return;
		if(start) {
			log.start(player.getName(), player.getUniqueId(), "mob", type.name(), null);
		} else {
			log.end(player.getName(), player.getUniqueId(), "mob", type.name());
		}
	}

	/** /disguise whois <name>: who is (or was) behind a disguise name. */
	public void whoisCommand(CommandSender sender, String[] args) {
		Language language = language();
		if(!has(sender, PERM_WHOIS)) {
			send(sender, language.DISGUISE_NO_PERMISSION);
			return;
		}
		if(args.length < 2) {
			send(sender, language.WHOIS_USAGE);
			return;
		}
		String name = args[1];
		PlayerDisguise current = null;
		for(PlayerDisguise disguise : disguises.values()) {
			if(disguise.getFakeName().equalsIgnoreCase(name)) current = disguise;
		}
		PlayerDisguise now = current;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			List<DisguiseLog.Session> sessions = DisguiseLog.newest(log.sessions(name, null), 8);
			Bukkit.getScheduler().runTask(plugin, () -> {
				sendUnchanged(sender, language.WHOIS_HEADER.replace("%name%", name));
				if(now != null) {
					sendUnchanged(sender, language.WHOIS_NOW.replace("%player%", now.getRealName())
							.replace("%state%", now.getProfile() != null ? language.WHOIS_ONLINE : language.WHOIS_OFFLINE));
				}
				if(sessions.isEmpty() && now == null) sendUnchanged(sender, language.WHOIS_NONE);
				for(DisguiseLog.Session session : sessions) sendUnchanged(sender, line(language, session));
			});
		});
	}

	/** /disguise history <player>: every disguise a real player used. */
	public void historyCommand(CommandSender sender, String[] args) {
		Language language = language();
		if(!has(sender, PERM_WHOIS)) {
			send(sender, language.DISGUISE_NO_PERMISSION);
			return;
		}
		if(args.length < 2) {
			send(sender, language.HISTORY_USAGE);
			return;
		}
		String name = args[1];
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			List<DisguiseLog.Session> sessions = DisguiseLog.newest(log.sessions(null, name), 10);
			Bukkit.getScheduler().runTask(plugin, () -> {
				sendUnchanged(sender, language.HISTORY_HEADER.replace("%player%", name));
				if(sessions.isEmpty()) sendUnchanged(sender, language.HISTORY_NONE);
				for(DisguiseLog.Session session : sessions) sendUnchanged(sender, line(language, session));
			});
		});
	}

	private static String line(Language language, DisguiseLog.Session session) {
		String disguise = session.kind.equals("mob") ? session.disguise.toLowerCase(Locale.ENGLISH) : session.disguise
				+ (session.rank.equals("-") ? "" : " (" + session.rank + ")");
		return language.LOG_ENTRY.replace("%start%", session.start).replace("%end%", session.end != null ? session.end : language.LOG_STILL_ON)
				.replace("%player%", session.realName).replace("%disguise%", disguise);
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

		if(settings.repairAfterCommands.contains(label) && disguises.containsKey(event.getPlayer().getUniqueId())) {
			// A vanish plugin may send its own tab packets past iDisguise: rebuild the entry afterwards.
			Player player = event.getPlayer();
			Bukkit.getScheduler().runTaskLater(plugin, () -> repair(player), 10L);
			Bukkit.getScheduler().runTaskLater(plugin, () -> repair(player), 40L);
		}
		if(settings.blockedCommands.contains(label) && disguises.containsKey(event.getPlayer().getUniqueId())) {
			event.setCancelled(true);
			send(event.getPlayer(), language().COMMAND_BLOCKED_WHILE_DISGUISED.replace("%command%", "/" + label));
			return;
		}
		if(OWN_COMMANDS.contains(label) || settings.commandsNotTranslated.contains(label) || parts.length < 2) return;

		Player sender = event.getPlayer();
		boolean staff = has(sender, PERM_REVEAL) || has(sender, PERM_ADMIN);
		boolean changed = false;
		for(int i = 1; i < parts.length; i++) {
			boolean translated = false;
			for(PlayerDisguise disguise : online) {
				PlayerDisguiseSettings.Profile profile = disguise.getProfile();
				if(profile == null || !profile.commandsUseFakeName || !parts[i].equalsIgnoreCase(disguise.getFakeName())) continue;
				// While the real player is online, the name means the real player.
				if(Bukkit.getPlayerExact(disguise.getFakeName()) != null) continue;
				parts[i] = disguise.getRealName();
				changed = true;
				translated = true;
				break;
			}
			if(translated || staff) continue;
			// The real name (or the start of it, which many plugins accept) of a disguised player acts
			// like an offline player for everyone else: otherwise "/msg <real name>" would reach the
			// disguised player and tell who they are. An invisible marker INSIDE the name makes the lookup
			// fail; it is removed from everything players see (NameReplacer.MARKER), and keeps the name swap
			// from turning the reply ("Dann1e not found") into the fake name.
			Player match = matchingPlayer(parts[i]);
			if(match != null && match != sender) {
				PlayerDisguise disguise = disguises.get(match.getUniqueId());
				if(disguise != null && disguise.getProfile() != null && disguise.getProfile().replaceNameInMessages) {
					parts[i] = NameReplacer.mark(parts[i]);
					changed = true;
				}
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

	/**
	 * %idisguise_tabprefix%: the TAB tab prefix of the player's REAL rank (groups.yml), for their own
	 * scoreboard: %tab_tabprefix% shows the disguise rank while disguised.
	 */
	String realTabPrefix(Player player) {
		if(player == null || luckPerms == null || tabLook == null) return "";
		String group = luckPerms.primaryGroup(player);
		return group == null ? "" : tabLook.tabPrefix(group, player.getWorld().getName());
	}

	/** %idisguise_name%: the name other players see. */
	String visibleName(Player player) {
		PlayerDisguise disguise = player == null ? null : disguises.get(player.getUniqueId());
		if(player == null) return "";
		return disguise != null ? disguise.getFakeName() : player.getName();
	}

}
