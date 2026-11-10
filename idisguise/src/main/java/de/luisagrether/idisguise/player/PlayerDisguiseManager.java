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
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

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
	// Fake invisibility: who is invisible, and the listener for armor / sounds / particles (only while one is online).
	private Invisibility invisibility;
	private InvisiblePackets invisiblePackets;
	private boolean invisibleListening;
	public static final String PERM_INVIS = "iDisguise.invis";
	// The packet listener only runs while a player-disguised player is online (or just left, see leaving).
	private boolean listening;
	// Disguised players who just left: their disguise still covers the "left the game" message and
	// an NPC copy (CombatLogX / Citizens) with their real name. player -> disguise, player -> until when
	private final Map<UUID, PlayerDisguise> leaving = new ConcurrentHashMap<>();
	private final Map<UUID, Long> leavingUntil = new ConcurrentHashMap<>();
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
	private FactionsHook factions;
	// FactionsUUID, also when real-player-faction is off (/f bypass while disguised). Read once.
	private FactionsHook factionsApi;
	private boolean factionsRead;
	// Staff whose /f bypass was on when a disguise with player permissions turned it off (given back after).
	private final Set<UUID> bypassTaken = ConcurrentHashMap.newKeySet();
	private DeluxeTagsHook deluxeTags;
	public static final String PERM_TAGS = "iDisguise.tags";
	public static final String PERM_TAGS_ALL = "iDisguise.tags.all";
	private static final String NO_TAG = "none";
	private static final Set<String> FACTION_COMMANDS = new HashSet<>(java.util.Arrays.asList("f", "faction", "factions"));
	// disguised player -> viewer -> tab line last sent by the keeper
	private final Map<UUID, Map<UUID, String>> tabLines = new ConcurrentHashMap<>();
	// player -> when they last put on a player disguise themselves (cooldown)
	private final Map<UUID, Long> lastDisguised = new ConcurrentHashMap<>();
	// viewer -> disguised player -> how TAB would show the real player (staff with reveal on)
	private final Map<UUID, Map<UUID, TabLook.Look>> revealLooks = new ConcurrentHashMap<>();
	// viewer (reveal on) -> disguised players who have an iDisguise nametag team on that viewer's screen
	private final Map<UUID, Set<UUID>> revealTeams = new ConcurrentHashMap<>();
	private int revealTick;

	public PlayerDisguiseManager(iDisguise plugin) {
		this.plugin = plugin;
	}

	// ---------------------------------------------------------------- start / stop

	/**
	 * /disguise reload: player-disguise.yml again. Returns false when player disguises are not running
	 * (turning them on or off needs a restart).
	 */
	public boolean reloadSettings() {
		if(!available) return false;
		PlayerDisguiseSettings fresh = new PlayerDisguiseSettings(plugin);
		if(!fresh.enabled) return false;
		settings = fresh;
		tabLook = new TabLook();
		factions = settings.realPlayerFaction ? factionsApi() : null;
		for(Player player : Bukkit.getOnlinePlayers()) {
			PlayerDisguise disguise = disguises.get(player.getUniqueId());
			if(disguise != null) markOnline(player, disguise);
		}
		rebuildSnapshots();
		tabLines.clear();
		for(Player player : Bukkit.getOnlinePlayers()) {
			PlayerDisguise disguise = disguises.get(player.getUniqueId());
			if(disguise != null && tab != null) tab.setTagSuffix(player, ownerTagSuffix(player, disguise));
			if(disguise != null && disguise.hasPlayerPermissions()) takePermissions(player);
		}
		return true;
	}

	public void enable() {
		// The log also records mob disguises, so it exists even when player disguises are off.
		log = new DisguiseLog(plugin.getDataFolder(), plugin.getLogger());
		// What other plugins' configs need (TAB, InteractiveChat, Factions); written again on every start.
		try {
			plugin.saveResource("OUTSIDE-CHANGES.txt", true);
		} catch(RuntimeException e) {
			plugin.getLogger().warning("Could not write OUTSIDE-CHANGES.txt: " + e);
		}
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
		Bukkit.getPluginManager().registerEvents(new DisguiseMenu.Clicks(plugin), plugin);
		deluxeTags = DeluxeTagsHook.create(plugin.getLogger());
		hookVanishEvents();
		tabLook = new TabLook();
		factions = settings.realPlayerFaction ? factionsApi() : null;
		checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 600L, 600L);
		revealTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			updateRevealLooks();
			keepTabLines();
			keepLeaving();
		}, 20L, 20L);
		rewriter = new PacketRewriter(plugin, this);
		invisibility = new Invisibility(plugin.getDataFolder(), plugin.getLogger());
		invisiblePackets = new InvisiblePackets(plugin, this);
		for(Player player : Bukkit.getOnlinePlayers()) invisibility.online(player);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		if(placeholderApi) {
			try {
				PlaceholderHook.register(this);
			} catch(Throwable t) {
				plugin.getLogger().warning("Could not register the %idisguise_name% placeholder: " + t);
			}
		}
		available = true;

		// Players already online (plugin reload): show their disguises again. The packet listener must
		// be on first (rebuildSnapshots), so the refresh goes out disguised.
		for(Player player : Bukkit.getOnlinePlayers()) {
			PlayerDisguise disguise = disguises.get(player.getUniqueId());
			if(disguise != null) markOnline(player, disguise);
		}
		rebuildSnapshots();
		for(Player player : Bukkit.getOnlinePlayers()) {
			PlayerDisguise disguise = disguises.get(player.getUniqueId());
			if(disguise != null) {
				applyLook(player, disguise);
				removeRealEntry(player);
				refresh(player);
			}
		}
	}

	public void disable() {
		if(log != null) {
			// Given with /odisguise: not saved, so the stop ends them.
			for(PlayerDisguise disguise : online) {
				if(disguise.isGivenByStaff()) log.end(disguise.getRealName(), disguise.getUniqueId(), "player", disguise.getFakeName());
			}
			log.close();
		}
		if(!available) return;
		for(Player player : Bukkit.getOnlinePlayers()) {
			givePermissionsBack(player);
			giveBypassBack(player);
		}
		checkTask.cancel();
		revealTask.cancel();
		for(Player player : Bukkit.getOnlinePlayers()) {
			PlayerDisguise disguise = disguises.get(player.getUniqueId());
			if(disguise != null) resetLook(player, disguise);
		}
		if(listening) rewriter.unregister();
		listening = false;
		if(invisibleListening) invisiblePackets.unregister();
		invisibleListening = false;
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

	/**
	 * True when the player may put on a player disguise now; otherwise says how long to wait.
	 * Staff and admins never wait.
	 */
	public boolean checkCooldown(Player player) {
		if(settings == null || settings.cooldownSeconds <= 0 || isStaff(player) || has(player, PERM_ADMIN)) return true;
		Long last = lastDisguised.get(player.getUniqueId());
		if(last == null) return true;
		long left = settings.cooldownSeconds * 1000L - (System.currentTimeMillis() - last);
		if(left <= 0) return true;
		send(player, language().DISGUISE_ON_COOLDOWN.replace("%seconds%", String.valueOf((left + 999) / 1000L)));
		return false;
	}

	/** The player just put on a player disguise themselves: the cooldown starts. */
	public void startCooldown(Player player) {
		lastDisguised.put(player.getUniqueId(), System.currentTimeMillis());
	}

	/** May pick a tag for a disguise at all: staff, iDisguise.tags, iDisguise.tags.all (and DeluxeTags is there). */
	public boolean canUseTags(CommandSender sender) {
		if(deluxeTags == null) return false;
		return !(sender instanceof Player) || has(sender, PERM_STAFF) || has(sender, PERM_TAGS) || has(sender, PERM_TAGS_ALL);
	}

	/** May pick at least one fake rank. */
	public boolean canUseRanks(CommandSender sender) {
		return !usableRanks(sender).isEmpty();
	}

	/** Staff, iDisguise.tags.all or the console: every tag; iDisguise.tags: tags they own in DeluxeTags. */
	private boolean canUseTag(CommandSender sender, DeluxeTagsHook.Tag tag) {
		if(!canUseTags(sender)) return false;
		if(!(sender instanceof Player) || has(sender, PERM_STAFF) || has(sender, PERM_TAGS_ALL)) return true;
		return deluxeTags.owns((Player)sender, tag);
	}

	/** Tags this sender may give a disguise (tab completion, tag list). */
	List<DeluxeTagsHook.Tag> usableTags(CommandSender sender) {
		List<DeluxeTagsHook.Tag> result = new ArrayList<>();
		if(!canUseTags(sender)) return result;
		for(DeluxeTagsHook.Tag tag : deluxeTags.tags()) {
			if(canUseTag(sender, tag)) result.add(tag);
		}
		return result;
	}

	/** Tag identifiers for tab completion ("none" first). */
	public List<String> usableTagIds(CommandSender sender) {
		List<String> result = new ArrayList<>();
		if(!canUseTags(sender)) return result;
		result.add(NO_TAG);
		for(DeluxeTagsHook.Tag tag : usableTags(sender)) result.add(tag.id);
		return result;
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

	/** iDisguise.rank.players: every rank in player-ranks, iDisguise.rank.staff: every rank in staff-ranks. */
	private boolean canUseRank(CommandSender sender, String rank) {
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

	/** The disguise rank as it looks in tab (colours and capitals), for the player's own messages. */
	public String shownRankLook(Player player) {
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		return disguise == null ? "-" : rankLook(effectiveRank(disguise), player);
	}

	/**
	 * A rank as players see it: its TAB tab prefix ("&5Obsidian"), without the trailing space and colour;
	 * a rank whose prefix shows no text: its name in grey ("&7Default").
	 */
	private String rankLook(String rank, Player player) {
		String group = rank != null ? rank : settings.defaultRank;
		if(group == null) return "-";
		String shown = tabLook == null ? "" : tabLook.tabPrefix(group, player);
		shown = shown.replaceAll("(?:\\s|\u00a7.)+$", "");
		if(ChatColor.stripColor(shown).trim().isEmpty()) {
			return ChatColor.GRAY + group.substring(0, 1).toUpperCase(Locale.ENGLISH) + group.substring(1);
		}
		return shown;
	}

	public boolean canUseRandom(CommandSender sender) {
		return available && has(sender, PERM_RANDOM);
	}

	/**
	 * /disguise <name>: rank and tag are picked in a menu, only what this player may pick: ranks (with a
	 * tag button when tags are allowed too), or only tags (the tag list right away). Nothing to pick, or
	 * not from a player (console): disguised straight away. /odisguise: the menu opens for the sender.
	 */
	public void disguiseCommand(CommandSender sender, Player target, String name, boolean self, Consumer<Boolean> done) {
		Player chooser = sender instanceof Player ? (Player)sender : null;
		Player owner = Bukkit.getPlayerExact(name);
		// Disguised as someone who is online, the rank is theirs: only the tag can be picked.
		boolean ranks = (owner == null || owner == target) && canUseRanks(sender);
		boolean tags = !usableTags(sender).isEmpty();
		if(!available || chooser == null || (!ranks && !tags)
				|| !iDisguise.ACCOUNTNAME.matcher(name).matches() || "random".equalsIgnoreCase(name)) {
			disguise(sender, target, name, null, null, done);
			return;
		}
		// Check the name first: the menu only opens for a name that can be used.
		// Otherwise the normal path runs and says why not.
		String problem = nameProblem(sender, target, name);
		boolean blockKnown = !has(sender, PERM_ANY_NAME) && settings.blockKnownNamesForPlayers;
		if(problem != null || plugin.isPlayerNameBlacklisted(name)
				|| (luckPerms == null && blockKnown && hasPlayedBefore(name, target))) {
			disguise(sender, target, name, null, null, done);
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			String realRank = null;
			boolean known = false;
			UUID knownUuid = null;
			try {
				if(luckPerms != null) {
					UUID uuid = luckPerms.lookupUniqueId(name).get(10, TimeUnit.SECONDS);
					knownUuid = uuid;
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
			Menu menu = new Menu(chooser, target, name, realRank, null, done);
			menu.ranks = ranks;
			// The menu opens right away (wither skull on top); the skin comes after it.
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(!target.isOnline() || !chooser.isOnline()) return;
				if(refused) {
					disguise(sender, target, name, null, null, done);
				} else if(ranks) {
					openRankMenu(menu);
				} else {
					openTagMenu(menu, 0, true);
				}
			});
			if(refused) return;
			// The skin for the head, which the disguise then uses too (a random skin would otherwise differ).
			// Mojang answers are cached. Clicked before it's here: the disguise looks it up itself.
			SkinResolver.Skin skin = skins.resolve(name, knownUuid, settings.randomSkins);
			Bukkit.getScheduler().runTask(plugin, () -> {
				// The head gave another name meanwhile: that one gets its own skin.
				if(menu.rerolls != 0) return;
				menu.skin = skin;
				// Swapped in the open menu (rank or tag), nothing reopens.
				if(menu.shown != null && chooser.isOnline() && chooser.getOpenInventory().getTopInventory().getHolder() == menu.shown) {
					menu.shown.replace(DisguiseMenu.HEADER, menuHeader(menu));
				}
			});
		});
	}

	/** What stays the same while the rank / tag menus switch: who picks, for whom, the name and its skin. */
	private static final class Menu {
		final Player chooser;
		final Player target;
		// The name and what belongs to it; a click on the head swaps them for a random name (main thread only).
		String name;
		String realRank;
		// null until the lookup is done.
		SkinResolver.Skin skin;
		// How many random names the head gave: a lookup for an older name is thrown away.
		int rerolls;
		// The head says "no new names right now" once.
		boolean noNames;
		// The rank menu can be shown (not when the name is someone online, or without rank permission).
		boolean ranks;
		// What is open: the rank menu (page -1) or that page of the tag list.
		int page = -1;
		boolean tagOnly;
		// The menu open right now (rank or tag), for swapping the head in when the skin arrives.
		DisguiseMenu shown;
		final Consumer<Boolean> done;
		// Picked so far: the tag (null = default, "" = none) and the invisibility switch.
		String tag;
		boolean invis;
		// "Player permissions": staff extras off while disguised (OFF by default).
		boolean playerPerms;

		Menu(Player chooser, Player target, String name, String realRank, SkinResolver.Skin skin, Consumer<Boolean> done) {
			this.chooser = chooser;
			this.target = target;
			this.name = name;
			this.realRank = realRank;
			this.skin = skin;
			this.done = done;
		}
	}

	/**
	 * Disguises from a menu choice (the cooldown is checked again: two open menus can't skip it).
	 * invis: the invisibility button was on, so the player also turns invisible.
	 */
	private void disguiseFromMenu(Menu menu, String rank, String tag, boolean invis) {
		Player chooser = menu.chooser;
		Player target = menu.target;
		if(chooser != target || checkCooldown(chooser)) {
			boolean button = permsButton(menu) != null;
			disguise(chooser, target, menu.name, rank, tag, menu.skin, success -> {
				if(success && button) setPlayerPermissions(target, menu.playerPerms);
				if(success && invis && target.isOnline()) setInvisible(chooser, target, true);
				finish(menu.done, success);
			});
		} else {
			finish(menu.done, false);
		}
	}

	/** The invisibility button, or null when this player may not use fake invisibility. */
	private org.bukkit.inventory.ItemStack invisButton(Player chooser, boolean invis) {
		if(invisibility == null || !has(chooser, PERM_INVIS)) return null;
		Language language = language();
		return RankMenu.item(settings.menuItem("invis"), invis ? language.RANK_MENU_INVIS_ON : language.RANK_MENU_INVIS_OFF,
				RankMenu.lines(language.RANK_MENU_INVIS_LORE));
	}

	/** The chosen tag as others see it after the name (" [Nemesis]"), or "" for none / the default. */
	private String tagPreview(String tag) {
		if(tag == null || tag.isEmpty() || deluxeTags == null) return "";
		DeluxeTagsHook.Tag found = deluxeTags.find(tag);
		if(found == null || ChatColor.stripColor(found.label()).trim().isEmpty()) return "";
		return " " + found.label();
	}

	/**
	 * The menu's top item: a player head with the disguise's skin (the textures are put straight into the
	 * item, so the server never looks anything up), or the configured header item when there is no skin.
	 */
	private org.bukkit.inventory.ItemStack headerItem(SkinResolver.Skin skin, String title, List<String> lore) {
		if(skin == null || skin.value == null) return RankMenu.item(settings.menuItem("header"), title, lore);
		org.bukkit.inventory.ItemStack item = RankMenu.item("SKULL_ITEM:3", title, lore);
		try {
			org.bukkit.inventory.ItemStack craft = com.comphenix.protocol.utility.MinecraftReflection.getBukkitItemStack(
					com.comphenix.protocol.utility.MinecraftReflection.getMinecraftItemStack(item));
			com.comphenix.protocol.wrappers.nbt.NbtCompound tag = com.comphenix.protocol.wrappers.nbt.NbtFactory.asCompound(
					com.comphenix.protocol.wrappers.nbt.NbtFactory.fromItemTag(craft));
			com.comphenix.protocol.wrappers.nbt.NbtCompound owner = com.comphenix.protocol.wrappers.nbt.NbtFactory.ofCompound("SkullOwner");
			owner.put("Id", UUID.nameUUIDFromBytes(("iDisguise-head:" + skin.value).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
			com.comphenix.protocol.wrappers.nbt.NbtCompound texture = com.comphenix.protocol.wrappers.nbt.NbtFactory.ofCompound("");
			texture.put("Value", skin.value);
			if(skin.signature != null) texture.put("Signature", skin.signature);
			com.comphenix.protocol.wrappers.nbt.NbtCompound properties = com.comphenix.protocol.wrappers.nbt.NbtFactory.ofCompound("Properties");
			properties.put(com.comphenix.protocol.wrappers.nbt.NbtFactory.ofList("textures", texture));
			owner.put(properties);
			tag.put(owner);
			return craft;
		} catch(RuntimeException e) {
			debug("Could not put the skin on the menu head", e);
			return RankMenu.item(settings.menuItem("header"), title, lore);
		}
	}

	/** The head on top of both menus: the disguise's skin and the name; a click gives another random name. */
	private org.bukkit.inventory.ItemStack menuHeader(Menu menu) {
		Language language = language();
		List<String> lore = new ArrayList<>();
		// For another player (/odisguise): the menu says so.
		if(menu.chooser != menu.target) lore.add(language.RANK_MENU_FOR.replace("%player%", menu.target.getName()));
		if(canReroll(menu.chooser)) {
			if(!lore.isEmpty()) lore.add("");
			lore.addAll(RankMenu.lines(menu.noNames ? language.RANK_MENU_HEADER_NO_NAMES : language.RANK_MENU_HEADER_REROLL));
		}
		return headerItem(menu.skin, language.RANK_MENU_HEADER.replace("%name%", menu.name), lore.isEmpty() ? null : lore);
	}

	/** The head gives random names: only with iDisguise.random. */
	private boolean canReroll(Player chooser) {
		return randomNames != null && has(chooser, PERM_RANDOM);
	}

	/**
	 * The head was clicked: another random name (from the names kept ready, so nothing waits). The menu
	 * opens again over itself with the new name (the cursor stays). The skin is only looked up for the name
	 * the player stops on, so clicking fast never floods Mojang.
	 */
	private void rerollName(Menu menu) {
		Player chooser = menu.chooser;
		Player target = menu.target;
		if(!canReroll(chooser)) return;
		String current = menu.name;
		String name = randomNames.take(candidate -> !candidate.equalsIgnoreCase(current) && !plugin.isPlayerNameBlacklisted(candidate)
				&& nameProblem(chooser, target, candidate) == null && Bukkit.getPlayerExact(candidate) == null
				&& (luckPerms != null || !hasPlayedBefore(candidate, target)));
		if(name == null) {
			menu.noNames = true;
			if(menu.shown != null) menu.shown.replace(DisguiseMenu.HEADER, menuHeader(menu));
			menu.noNames = false;
			return;
		}
		menu.name = name;
		menu.realRank = null;
		menu.skin = null;
		int roll = ++menu.rerolls;
		if(menu.page < 0) {
			openRankMenu(menu);
		} else if(menu.tagOnly && canUseRanks(chooser)) {
			// Tags only because the old name was someone online: the new name can have a rank.
			menu.ranks = true;
			openRankMenu(menu);
		} else {
			openTagMenu(menu, menu.page, menu.tagOnly);
		}
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if(menu.rerolls != roll || !chooser.isOnline()) return;
			Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
				boolean known = false;
				if(luckPerms != null) {
					try {
						UUID uuid = luckPerms.lookupUniqueId(name).get(10, TimeUnit.SECONDS);
						known = uuid != null && !uuid.equals(target.getUniqueId());
					} catch(Exception e) {
						debug("LuckPerms lookup failed for " + name, e);
					}
				}
				// Has played here after all (a ready name can be older than that): another one.
				if(known) {
					Bukkit.getScheduler().runTask(plugin, () -> {
						if(menu.rerolls == roll && isOpen(menu)) rerollName(menu);
					});
					return;
				}
				SkinResolver.Skin skin = skins.resolve(name, null, settings.randomSkins);
				Bukkit.getScheduler().runTask(plugin, () -> {
					if(menu.rerolls != roll) return;
					menu.skin = skin;
					if(isOpen(menu)) menu.shown.replace(DisguiseMenu.HEADER, menuHeader(menu));
				});
			});
		}, 10L);
	}

	private boolean isOpen(Menu menu) {
		return menu.shown != null && menu.chooser.isOnline() && menu.chooser.getOpenInventory().getTopInventory().getHolder() == menu.shown;
	}

	/** The "random rank & tag" button, or null when no rank can be picked in this menu. */
	private org.bukkit.inventory.ItemStack randomButton(Menu menu) {
		if(!menu.ranks) return null;
		Language language = language();
		boolean tags = !usableTags(menu.chooser).isEmpty();
		List<String> lore = RankMenu.lines(tags ? language.RANK_MENU_RANDOM_LORE : language.RANK_MENU_RANDOM_RANK_LORE);
		// Staff see staff ranks in the menu: say that the button never picks one.
		if(has(menu.chooser, PERM_RANK + "staff")) lore.add(language.RANK_MENU_RANDOM_NO_STAFF);
		lore.add("");
		lore.add(language.RANK_MENU_RANDOM_CLICK);
		return RankMenu.item(settings.menuItem("random"), tags ? language.RANK_MENU_RANDOM : language.RANK_MENU_RANDOM_RANK, lore);
	}

	/** A random rank the player may use ("no rank" counts as one of them), never a staff rank. */
	private String randomRank(CommandSender sender) {
		List<String> choices = new ArrayList<>();
		for(String usable : usableRanks(sender)) {
			if(!settings.isStaffRank(usable)) choices.add(usable);
		}
		choices.add(NO_RANK);
		return choices.get(new java.util.Random().nextInt(choices.size()));
	}

	/** A random tag the player may use ("" = none counts as one), or null = no tags to pick. */
	private String randomTag(CommandSender sender) {
		if(!canUseTags(sender)) return null;
		List<String> tags = new ArrayList<>();
		tags.add("");
		for(DeluxeTagsHook.Tag usable : usableTags(sender)) tags.add(usable.id);
		return tags.get(new java.util.Random().nextInt(tags.size()));
	}

	private org.bukkit.inventory.ItemStack menuFrame() {
		return RankMenu.item(settings.menuItem("frame"), " ", null);
	}

	private org.bukkit.inventory.ItemStack cancelItem() {
		Language language = language();
		return RankMenu.item(settings.menuItem("cancel"), language.RANK_MENU_CANCEL, RankMenu.lines(language.RANK_MENU_CANCEL_LORE));
	}

	/**
	 * The "player permissions" button, or null: only when both the one picking and the one disguised are
	 * staff (a normal player disguised with /odisguise keeps their own rank's perks), and with LuckPerms.
	 */
	private org.bukkit.inventory.ItemStack permsButton(Menu menu) {
		boolean on = menu.playerPerms;
		if(luckPerms == null || settings.playerPermissions.isEmpty() || !has(menu.chooser, PERM_STAFF) || !has(menu.target, PERM_STAFF)) return null;
		Language language = language();
		return RankMenu.item(settings.menuItem("player-permissions"), on ? language.RANK_MENU_PERMS_ON : language.RANK_MENU_PERMS_OFF,
				RankMenu.lines(language.RANK_MENU_PERMS_LORE));
	}

	private void switchPerms(Menu menu, DisguiseMenu shown) {
		menu.playerPerms = !menu.playerPerms;
		shown.replace(DisguiseMenu.PLAYER_PERMS, permsButton(menu));
	}

	/** The invisibility button was clicked: switched in the open menu, nothing reopens. */
	private void switchInvis(Menu menu, DisguiseMenu shown) {
		menu.invis = !menu.invis;
		shown.replace(DisguiseMenu.INVIS, invisButton(menu.chooser, menu.invis));
	}

	private void openRankMenu(Menu menu) {
		Player chooser = menu.chooser;
		Player target = menu.target;
		String name = menu.name;
		String shownTag = tagPreview(menu.tag);
		Language language = language();
		// "No rank" first, then the ranks in order: player ranks, staff ranks right after them.
		List<RankMenu.Entry> rankEntries = new ArrayList<>();
		rankEntries.add(new RankMenu.Entry(NO_RANK, RankMenu.item(settings.menuItem("no-rank"), ChatColor.WHITE + name + shownTag,
				RankMenu.lines(language.RANK_MENU_RANK_LORE))));
		List<String> usable = usableRanks(chooser);
		for(String rank : usable) {
			if(!settings.isStaffRank(rank)) rankEntries.add(new RankMenu.Entry(rank, rankItem(rank, name + shownTag, null)));
		}
		for(String rank : usable) {
			if(settings.isStaffRank(rank)) rankEntries.add(new RankMenu.Entry(rank, rankItem(rank, name + shownTag, null)));
		}
		RankMenu.Entry real = menu.realRank == null ? null : new RankMenu.Entry(null,
				rankItem(menu.realRank, name + shownTag, language.RANK_MENU_REAL_RANK.replace("%name%", name)));
		RankMenu.Entry cancel = new RankMenu.Entry(RankMenu.CANCEL_ACTION, cancelItem());
		// The tag button: only when this player may pick at least one tag.
		RankMenu.Entry tagButton = null;
		if(!usableTags(chooser).isEmpty()) {
			DeluxeTagsHook.Tag current = menu.tag == null || menu.tag.isEmpty() ? null : deluxeTags.find(menu.tag);
			String shown = current != null ? current.label() : language.RANK_MENU_TAG_NONE;
			tagButton = new RankMenu.Entry(RankMenu.TAG, RankMenu.item(settings.menuItem("tag"),
					language.RANK_MENU_TAG.replace("%tag%", shown), RankMenu.lines(language.RANK_MENU_TAG_LORE)));
		}
		String title = (chooser != target ? language.RANK_MENU_TITLE_OTHER.replace("%player%", target.getName()) : language.RANK_MENU_TITLE)
				.replace("%name%", name);
		menu.page = -1;
		menu.shown = new RankMenu(title, menuHeader(menu), canReroll(chooser), menuFrame(), rankEntries, real, cancel, tagButton,
				randomButton(menu), invisButton(chooser, menu.invis), permsButton(menu),
				(shown, choice) -> {
					if(RankMenu.TAG.equals(choice)) {
						openTagMenu(menu, 0, false);
					} else if(RankMenu.HEAD_ACTION.equals(choice)) {
						rerollName(menu);
						return true;
					} else if(RankMenu.RANDOM_ACTION.equals(choice)) {
						disguiseFromMenu(menu, randomRank(chooser), randomTag(chooser), menu.invis);
					} else if(RankMenu.INVIS_ACTION.equals(choice)) {
						switchInvis(menu, shown);
						return true;
					} else if(RankMenu.PERMS_ACTION.equals(choice)) {
						switchPerms(menu, shown);
						return true;
					} else if(RankMenu.CANCEL_ACTION.equals(choice)) {
						finish(menu.done, false);
					} else {
						disguiseFromMenu(menu, choice, menu.tag, menu.invis);
					}
					return false;
				});
		menu.shown.open(chooser);
	}

	/**
	 * The tag list. From the rank menu (tagOnly false) a choice goes back to the rank menu with that tag;
	 * for players who may only pick tags (tagOnly true) a choice disguises right away.
	 */
	private void openTagMenu(Menu menu, int page, boolean tagOnly) {
		Player chooser = menu.chooser;
		Player target = menu.target;
		String name = menu.name;
		String tag = menu.tag;
		Language language = language();
		// "No tag" first, then every tag this player may use; 36 per page.
		List<RankMenu.Entry> all = new ArrayList<>();
		List<String> noneLore = RankMenu.lines(language.TAG_MENU_NONE_LORE);
		noneLore.add("");
		noneLore.add(tag != null && tag.isEmpty() ? language.TAG_MENU_SELECTED : language.TAG_MENU_PICK);
		all.add(new RankMenu.Entry(TagMenu.NONE, RankMenu.item(settings.menuItem("no-tag"), language.TAG_MENU_NONE, noneLore)));
		for(DeluxeTagsHook.Tag entry : usableTags(chooser)) {
			List<String> lore = new ArrayList<>();
			if(!entry.description.isEmpty()) {
				lore.add(ChatColor.GRAY + ChatColor.translateAlternateColorCodes('&', entry.description));
				lore.add("");
			}
			lore.add(entry.id.equalsIgnoreCase(tag) ? language.TAG_MENU_SELECTED : language.TAG_MENU_PICK);
			String title = entry.shown().trim().isEmpty() ? ChatColor.WHITE + entry.id : entry.label();
			all.add(new RankMenu.Entry(entry.id, RankMenu.item(settings.menuItem("tag-entry"), title, lore)));
		}
		int pages = Math.max(1, (all.size() + DisguiseMenu.PER_PAGE - 1) / DisguiseMenu.PER_PAGE);
		int shownPage = Math.max(0, Math.min(page, pages - 1));
		List<RankMenu.Entry> entries = new ArrayList<>(all.subList(shownPage * DisguiseMenu.PER_PAGE,
				Math.min(all.size(), (shownPage + 1) * DisguiseMenu.PER_PAGE)));
		// Only tags: there is no rank menu to go back to.
		org.bukkit.inventory.ItemStack back = tagOnly ? null
				: RankMenu.item(settings.menuItem("back"), language.TAG_MENU_BACK, RankMenu.lines(language.TAG_MENU_BACK_LORE));
		List<String> pageLore = RankMenu.lines(language.TAG_MENU_PAGE.replace("%page%", String.valueOf(shownPage + 1))
				.replace("%pages%", String.valueOf(pages)));
		org.bukkit.inventory.ItemStack previous = shownPage > 0 ? RankMenu.item(settings.menuItem("page"), language.TAG_MENU_PREVIOUS, pageLore) : null;
		org.bukkit.inventory.ItemStack next = shownPage < pages - 1 ? RankMenu.item(settings.menuItem("page"), language.TAG_MENU_NEXT, pageLore) : null;
		String title = (chooser != target ? language.TAG_MENU_TITLE_OTHER.replace("%player%", target.getName()) : language.TAG_MENU_TITLE)
				.replace("%name%", name);
		menu.page = shownPage;
		menu.tagOnly = tagOnly;
		menu.shown = new TagMenu(title, menuHeader(menu), canReroll(chooser), menuFrame(), entries, back, cancelItem(), previous, next,
				randomButton(menu), invisButton(chooser, menu.invis), permsButton(menu),
				(shown, choice) -> {
					if(TagMenu.PREVIOUS_ACTION.equals(choice)) {
						openTagMenu(menu, shownPage - 1, tagOnly);
					} else if(TagMenu.NEXT_ACTION.equals(choice)) {
						openTagMenu(menu, shownPage + 1, tagOnly);
					} else if(RankMenu.HEAD_ACTION.equals(choice)) {
						rerollName(menu);
						return true;
					} else if(RankMenu.RANDOM_ACTION.equals(choice)) {
						disguiseFromMenu(menu, randomRank(chooser), randomTag(chooser), menu.invis);
					} else if(RankMenu.INVIS_ACTION.equals(choice)) {
						switchInvis(menu, shown);
						return true;
					} else if(RankMenu.PERMS_ACTION.equals(choice)) {
						switchPerms(menu, shown);
						return true;
					} else if(TagMenu.BACK.equals(choice)) {
						openRankMenu(menu);
					} else if(RankMenu.CANCEL_ACTION.equals(choice)) {
						finish(menu.done, false);
					} else {
						menu.tag = TagMenu.NONE.equals(choice) ? "" : choice;
						if(tagOnly) {
							disguiseFromMenu(menu, null, menu.tag, menu.invis);
						} else {
							openRankMenu(menu);
						}
					}
					return false;
				});
		menu.shown.open(chooser);
	}

	/** A rank in the menu: named the way others will see you ("[Gold] Notch"). ownLore: lore instead of the usual one. */
	private org.bukkit.inventory.ItemStack rankItem(String rank, String name, String ownLore) {
		String prefix = color(luckPerms == null ? null : luckPerms.groupPrefix(rank)).trim();
		String title = (ChatColor.stripColor(prefix).trim().isEmpty() ? ChatColor.WHITE + rank + " " : prefix + " ") + ChatColor.WHITE + name;
		List<String> lore = RankMenu.lines(ownLore != null ? ownLore : language().RANK_MENU_RANK_LORE);
		return RankMenu.item(settings.menuItem(rank), title, lore);
	}

	/** /disguise random: a real Minecraft account's name and skin, and a random rank the player may use. */
	public void disguiseRandom(CommandSender sender, Player target, Consumer<Boolean> done) {
		if(!available || randomNames == null) {
			send(sender, language().DISGUISE_TYPE_NOT_SUPPORTED);
			finish(done, false);
			return;
		}
		pickRandom(sender, target, done, 5);
	}

	/** Takes a name from the pool and checks again that nobody with it has played here (a pool name can be older than that). */
	private void pickRandom(CommandSender sender, Player target, Consumer<Boolean> done, int triesLeft) {
		String name = randomNames.take(candidate -> {
			if(plugin.isPlayerNameBlacklisted(candidate) || nameProblem(sender, target, candidate) != null) return false;
			if(Bukkit.getPlayerExact(candidate) != null) return false;
			return luckPerms != null || !hasPlayedBefore(candidate, target);
		});
		if(name == null || triesLeft <= 0) {
			send(sender, language().RANDOM_NOT_READY);
			finish(done, false);
			return;
		}
		if(luckPerms == null) {
			randomFound(sender, target, name, done);
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			boolean known;
			try {
				known = luckPerms.lookupUniqueId(name).get(10, TimeUnit.SECONDS) != null;
			} catch(Exception e) {
				known = true; // unsure: don't use it
			}
			boolean finalKnown = known;
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(!target.isOnline()) return;
				if(finalKnown) {
					pickRandom(sender, target, done, triesLeft - 1);
				} else {
					randomFound(sender, target, name, done);
				}
			});
		});
	}

	/**
	 * A random name was found: the disguise menu opens with it (rank, tag, the head for another name),
	 * like /disguise <name>. Nothing to pick in a menu (or from the console): disguised right away.
	 */
	private void randomFound(CommandSender sender, Player target, String name, Consumer<Boolean> done) {
		if(sender instanceof Player && (canUseRanks(sender) || !usableTags(sender).isEmpty())) {
			disguiseCommand(sender, target, name, sender == target, done);
		} else {
			disguiseRandomAs(sender, target, name, done);
		}
	}

	private void disguiseRandomAs(CommandSender sender, Player target, String name, Consumer<Boolean> done) {
		// Every rank the player may use has the same chance; staff ranks are never picked at random.
		disguise(sender, target, name, randomRank(sender), randomTag(sender), done);
	}

	// ---------------------------------------------------------------- disguise

	/**
	 * /disguise <name> [rank]. Checks everything, looks up rank and skin in the background and
	 * then disguises. sender == null: no messages and no permission checks (developer API).
	 */
	public void disguise(CommandSender sender, Player target, String name, String rankInput, Consumer<Boolean> done) {
		disguise(sender, target, name, rankInput, null, done);
	}

	/** tag: DeluxeTags identifier already checked ("" = no tag), or null = the default tag. */
	public void disguise(CommandSender sender, Player target, String name, String rankInput, String tag, Consumer<Boolean> done) {
		disguise(sender, target, name, rankInput, tag, null, done);
	}

	/** knownSkin: the skin the menu already looked up (its head showed it), or null = look it up now. */
	private void disguise(CommandSender sender, Player target, String name, String rankInput, String tag, SkinResolver.Skin knownSkin,
			Consumer<Boolean> done) {
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
		if(rankInput != null && !canUseRanks(sender)) {
			send(sender, language.DISGUISE_NO_PERMISSION);
			finish(done, false);
			return;
		}
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
		// Rank and skin lookups can take a few seconds: say it is working (from the menu the skin is known already).
		if(knownSkin == null) send(sender, language.PLAYER_DISGUISE_LOADING.replace("%name%", name));
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
			SkinResolver.Skin skin = knownSkin != null ? knownSkin : skins.resolve(name, knownUuid, settings.randomSkins);
			debug("Skin for " + name + ": " + skin.source);
			String finalRank = rank;
			UUID finalUuid = knownUuid;
			Bukkit.getScheduler().runTask(plugin, () -> apply(sender, target, name, finalRank, tag, skin, finalUuid, done));
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

	private void apply(CommandSender sender, Player target, String name, String rank, String chosenTag, SkinResolver.Skin skin, UUID knownUuid, Consumer<Boolean> done) {
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
			// Straight from one disguise to the next: the real rank is never put back in between.
			rebuildOwnerNameTag(target, previous);
			removeDisguiseEntry(target, previous);
			rebuildSnapshots();
		}
		plugin.removeMobDisguise(target);
		givePermissionsBack(target);

		PlayerDisguise disguise = new PlayerDisguise(target.getUniqueId(), target.getName(), name, rank, skin.value, skin.signature);
		disguise.setGivenByStaff(sender != target);
		disguise.setChosenTag(chosenTag);
		Player nameOwner = Bukkit.getPlayerExact(name);
		if(nameOwner != null && nameOwner != target) {
			disguise.setOwnerUuid(nameOwner.getUniqueId());
		} else if(knownUuid != null && !knownUuid.equals(target.getUniqueId())) {
			disguise.setOwnerUuid(knownUuid);
		}
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
		// Staff play like a normal player while disguised, unless the menu's button says otherwise.
		disguise.setPlayerPermissions(luckPerms != null && has(target, PERM_STAFF));
		disguises.put(target.getUniqueId(), disguise);
		rebuildSnapshots();
		save();
		if(disguise.hasPlayerPermissions()) {
			takePermissions(target);
			stopFlying(target);
			stopBypass(target);
		} else {
			giveBypassBack(target);
		}

		applyLook(target, disguise);
		removeRealEntry(target);
		refresh(target);
		log.start(target.getName(), target.getUniqueId(), "player", name, effectiveRank(disguise));
		if(previous != null) log.end(target.getName(), target.getUniqueId(), "player", previous.getFakeName());

		// The tag only for those who may pick tags (others never hear about tags).
		String tagPart = "";
		if(canUseTags(sender)) {
			DeluxeTagsHook.Tag picked = chosenTag == null || chosenTag.isEmpty() ? null : deluxeTags.find(chosenTag);
			tagPart = language.PLAYER_DISGUISED_TAG.replace("%tag%", picked != null && !picked.shown().trim().isEmpty()
					? picked.label() : language.RANK_MENU_TAG_NONE);
		}
		String disguisedText = sender != target
				? language.PLAYER_DISGUISED_OTHER.replace("%player%", target.getName())
				: language.PLAYER_DISGUISED;
		send(sender, disguisedText.replace("%name%", name).replace("%rank%", rankLook(effectiveRank(disguise), target)).replace("%tag%", tagPart));
		finish(done, true);
	}

	// ---------------------------------------------------------------- player permissions

	/** What was taken away from whom (exact nodes), to give exactly that back. */
	private final Map<UUID, List<String>> takenPermissions = new ConcurrentHashMap<>();

	/** The menu's "player permissions" button for a disguise that was just made. */
	private void setPlayerPermissions(Player player, boolean on) {
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		if(disguise == null || disguise.hasPlayerPermissions() == on) return;
		disguise.setPlayerPermissions(on);
		save();
		if(on) {
			takePermissions(player);
			stopFlying(player);
			stopBypass(player);
		} else {
			givePermissionsBack(player);
			giveBypassBack(player);
		}
	}

	/**
	 * Takes the staff extras from player-permissions away for now (LuckPerms, never saved). "x.*" also takes
	 * every x.<something> the player has: an exact permission would otherwise beat the "false" on x.*.
	 */
	private void takePermissions(Player player) {
		if(luckPerms == null || !player.isOnline()) return;
		givePermissionsBack(player);
		java.util.Set<String> nodes = new java.util.LinkedHashSet<>(settings.playerPermissions);
		for(String pattern : settings.playerPermissions) {
			if(!pattern.endsWith(".*")) continue;
			String start = pattern.substring(0, pattern.length() - 1);
			for(org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
				String node = info.getPermission().toLowerCase(Locale.ENGLISH);
				if(info.getValue() && node.startsWith(start)) nodes.add(node);
			}
		}
		if(nodes.isEmpty()) return;
		List<String> taken = new ArrayList<>(nodes);
		try {
			luckPerms.denyForNow(player, taken);
			takenPermissions.put(player.getUniqueId(), taken);
			debug("Player permissions for " + player.getName() + ": " + taken);
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not take staff permissions from " + player.getName() + " while disguised: " + t);
		}
	}

	/**
	 * Disguised with player permissions: flight that was on (/fly) goes off, like for a normal player.
	 * Not in creative or spectator, where flying is part of the game mode.
	 */
	private static void stopFlying(Player player) {
		org.bukkit.GameMode mode = player.getGameMode();
		if(mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR) return;
		player.setFlying(false);
		player.setAllowFlight(false);
	}

	private FactionsHook factionsApi() {
		if(!factionsRead) {
			factionsRead = true;
			factionsApi = FactionsHook.create(plugin.getLogger());
		}
		return factionsApi;
	}

	/** Disguised with player permissions: /f bypass goes off (noted, so it comes back after only if it was on). */
	private void stopBypass(Player player) {
		FactionsHook api = factionsApi();
		if(api == null || !api.bypassing(player)) return;
		api.setBypassing(player, false);
		bypassTaken.add(player.getUniqueId());
	}

	/** /f bypass back on, only when a disguise turned it off. */
	private void giveBypassBack(Player player) {
		if(!bypassTaken.remove(player.getUniqueId())) return;
		FactionsHook api = factionsApi();
		if(api != null && player.isOnline()) api.setBypassing(player, true);
	}

	private void givePermissionsBack(Player player) {
		List<String> taken = takenPermissions.remove(player.getUniqueId());
		if(taken == null || luckPerms == null) return;
		try {
			luckPerms.undeny(player, taken);
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not give " + player.getName() + " their staff permissions back (a relog does): " + t);
		}
	}

	/** Removes the player disguise. Returns false when the player had none. */
	public boolean undisguise(Player player) {
		PlayerDisguise disguise = disguises.remove(player.getUniqueId());
		if(disguise == null) return false;
		givePermissionsBack(player);
		giveBypassBack(player);
		rebuildSnapshots();
		save();
		log.end(player.getName(), player.getUniqueId(), "player", disguise.getFakeName());
		// Disguised as someone online, TAB never moved the name: after the reveal team is gone the name
		// would have no nametag on that staff member's screen until TAB sends it again, so TAB does it now.
		if(dropRevealTeams(player) && disguise.isHiddenFromTab() && tab != null) tab.rebuildNameTag(player);
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
		// The vanish plugin's packets go through iDisguise (the body and tab entry already show the
		// disguise), so only a real-name tab entry that may have slipped past is removed. Sending the body
		// again here left a second, ghost body on 1.8 clients.
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == player || !viewer.canSee(player)) continue;
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, null,
					new WrappedGameProfile(player.getUniqueId(), player.getName())));
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
							// Appearing again with player permissions: PremiumVanish's fly goes off, like for a normal
							// player (after PremiumVanish has set it). Vanishing keeps whatever it gives.
							if(event.getClass().getSimpleName().contains("Show")) {
								Bukkit.getScheduler().runTaskLater(plugin, () -> stopFlyingAfterAppear((Player)player), 2L);
								Bukkit.getScheduler().runTaskLater(plugin, () -> stopFlyingAfterAppear((Player)player), 10L);
							}
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

	/** Unvanished while disguised with player permissions (and still visible): flight off. */
	private void stopFlyingAfterAppear(Player player) {
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		if(!player.isOnline() || disguise == null || !disguise.hasPlayerPermissions()) return;
		for(org.bukkit.metadata.MetadataValue value : player.getMetadata("vanished")) {
			if(value.asBoolean()) return;
		}
		stopFlying(player);
	}

	/** Other players' clients drop the tab entry the disguise had (it has its own ID). */
	private void removeDisguiseEntry(Player player, PlayerDisguise disguise) {
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == player) continue;
			sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, disguise,
					new WrappedGameProfile(disguise.getFakeUniqueId(), disguise.getFakeName())));
		}
	}

	// ---------------------------------------------------------------- fake invisibility

	Invisibility invisibility() {
		return invisibility;
	}

	public boolean isInvisible(Player player) {
		return invisibility != null && invisibility.isInvisible(player.getUniqueId());
	}

	/** Turns fake invisibility on or off for target (sender gets the message). */
	public void setInvisible(CommandSender sender, Player target, boolean on) {
		Language language = language();
		if(invisibility == null) {
			send(sender, language.DISGUISE_TYPE_NOT_SUPPORTED);
			return;
		}
		if(on && plugin.hasMobDisguise(target)) {
			send(sender, language.INVISIBLE_NOT_WITH_MOB);
			return;
		}
		if(on == invisibility.isInvisible(target.getUniqueId())) {
			sendInvisibleMessage(sender, target, on);
			return;
		}
		invisibility.set(target.getUniqueId(), on, sender != target);
		if(on) {
			invisibility.online(target);
			log.start(target.getName(), target.getUniqueId(), "invisible", "invisible", null);
		} else {
			invisibility.offline(target.getUniqueId());
			log.end(target.getName(), target.getUniqueId(), "invisible", "invisible");
		}
		updateListener();
		if(tab != null) tab.setNameTagHidden(target, on);
		// Everyone gets the new look right away, the player too (invisible in F5, effect icon).
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if(viewer == target || !viewer.canSee(target)) continue;
			viewer.hidePlayer(target);
			viewer.showPlayer(target);
		}
		sendOwnInvisibility(target);
		sendInvisibleMessage(sender, target, on);
	}

	public void toggleInvisible(CommandSender sender, Player target) {
		setInvisible(sender, target, !isInvisible(target));
	}

	private void sendInvisibleMessage(CommandSender sender, Player target, boolean on) {
		Language language = language();
		if(sender == target) {
			send(sender, on ? language.INVISIBLE_ON : language.INVISIBLE_OFF);
		} else {
			send(sender, (on ? language.INVISIBLE_ON_OTHER : language.INVISIBLE_OFF_OTHER).replace("%player%", target.getName()));
		}
	}

	/** The player's look (metadata and equipment) for one viewer, through the rewriter. */
	void sendInvisibleState(Player target, Player viewer) {
		if(!target.isOnline() || !viewer.isOnline() || target.getWorld() != viewer.getWorld()) return;
		try {
			sendFiltered(viewer, lookPacket(target));
			org.bukkit.inventory.ItemStack[] armor = target.getInventory().getArmorContents();
			org.bukkit.inventory.ItemStack[] items = {target.getItemInHand(), armor[0], armor[1], armor[2], armor[3]};
			for(int slot = 0; slot < items.length; slot++) {
				PacketContainer equipment = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.ENTITY_EQUIPMENT);
				equipment.getIntegers().write(0, target.getEntityId());
				equipment.getIntegers().write(1, slot);
				equipment.getItemModifier().write(0, items[slot]);
				sendFiltered(viewer, equipment);
			}
		} catch(RuntimeException e) {
			debug("Could not send the look of " + target.getName() + " to " + viewer.getName(), e);
		}
	}

	/**
	 * A metadata packet with only the values invisibility changes (flags, potion colour, arrows), copied
	 * as new objects from the player's real values. Goes through the rewriter, which makes it invisible
	 * or not for each viewer.
	 */
	private static PacketContainer lookPacket(Player player) {
		com.comphenix.protocol.wrappers.WrappedDataWatcher real = com.comphenix.protocol.wrappers.WrappedDataWatcher.getEntityWatcher(player);
		List<com.comphenix.protocol.wrappers.WrappedWatchableObject> values = new ArrayList<>();
		for(int index : new int[] {0, 7, 9}) {
			Object value = real.getObject(index);
			if(value != null) values.add(new com.comphenix.protocol.wrappers.WrappedWatchableObject(index, value));
		}
		PacketContainer metadata = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.ENTITY_METADATA);
		metadata.getIntegers().write(0, player.getEntityId());
		metadata.getWatchableCollectionModifier().write(0, values);
		return metadata;
	}

	/** Right after the body was sent to a viewer (one tick later, when the client has it). */
	void sendInvisibleStateLater(UUID target, Player viewer) {
		Bukkit.getScheduler().runTask(plugin, () -> {
			Player player = Bukkit.getPlayer(target);
			if(player != null) sendInvisibleState(player, viewer);
		});
	}

	/**
	 * The player's own screen: invisible in F5 and the invisibility icon (no particles), or back to
	 * normal. Only on their client: the server never gives them a real potion.
	 */
	private void sendOwnInvisibility(Player player) {
		if(!player.isOnline() || invisibility == null) return;
		boolean on = invisibility.isInvisible(player.getUniqueId());
		try {
			PacketContainer effect;
			if(on) {
				effect = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.ENTITY_EFFECT);
				effect.getIntegers().write(0, player.getEntityId());
				effect.getIntegers().write(1, 32767); // shown as **:** (never counts down)
				effect.getBytes().write(0, (byte)14);
				effect.getBytes().write(1, (byte)0);
				effect.getBytes().write(2, (byte)1);
			} else {
				effect = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.REMOVE_ENTITY_EFFECT);
				effect.getIntegers().write(0, player.getEntityId());
				effect.getIntegers().write(1, 14);
			}
			// A real invisibility potion the player may have: leave its icon alone.
			if(on || !player.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) sendRaw(player, effect);
			sendFiltered(player, lookPacket(player));
		} catch(RuntimeException e) {
			debug("Could not send the invisibility of " + player.getName() + " to themselves", e);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		if(!isInvisible(player)) return;
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if(!player.isOnline()) return;
			invisibility.online(player);
			updateListener();
			sendOwnInvisibility(player);
		}, 5L);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldChange(PlayerChangedWorldEvent event) {
		Player player = event.getPlayer();
		if(!isInvisible(player)) return;
		invisibility.move(player, player.getLocation());
		Bukkit.getScheduler().runTaskLater(plugin, () -> sendOwnInvisibility(player), 5L);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		if(invisibility != null && invisibility.anyOnline()) invisibility.move(event.getPlayer(), event.getTo());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(PlayerTeleportEvent event) {
		if(invisibility != null && invisibility.anyOnline()) invisibility.move(event.getPlayer(), event.getTo());
	}

	/** Disguises saved by an older version don't know whose name they use: asked LuckPerms once. */
	private void findOwner(PlayerDisguise disguise) {
		if(disguise.getOwnerUuid() != null || luckPerms == null || factions == null) return;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				UUID uuid = luckPerms.lookupUniqueId(disguise.getFakeName()).get(10, TimeUnit.SECONDS);
				if(uuid != null && !uuid.equals(disguise.getUniqueId())) {
					disguise.setOwnerUuid(uuid);
					Bukkit.getScheduler().runTask(plugin, this::save);
				}
			} catch(Exception e) {
				debug("LuckPerms lookup failed for " + disguise.getFakeName(), e);
			}
		});
	}

	/**
	 * The TAB nametag suffix for a disguise that shows a real player's faction: the rank's tagsuffix from
	 * groups.yml with the faction placeholders pointed at that player (TAB fills them per viewer).
	 * null = TAB's normal suffix.
	 */
	private String ownerTagSuffix(Player player, PlayerDisguise disguise) {
		if(tabLook == null || factionOwner(disguise) == null || disguise.isHiddenFromTab()) return null;
		String rank = effectiveRank(disguise);
		if(rank == null) rank = settings.defaultRank;
		String suffix = tabLook.tagSuffix(rank, player.getWorld().getName());
		if(!suffix.contains("factionsuuid_")) return null;
		return suffix.replace("%rel_factionsuuid_relation_color%", "%rel_idisguise_ownercolor%")
				.replace("%factionsuuid_faction_name%", "%idisguise_ownerfaction%");
	}

	/** %idisguise_ownerfaction%: faction name of the real player this disguise shows ("" otherwise). */
	String ownerFaction(Player player) {
		PlayerDisguise disguise = player == null ? null : disguises.get(player.getUniqueId());
		UUID owner = disguise == null ? null : factionOwner(disguise);
		return owner == null ? "" : factions.factionName(owner);
	}

	/** %rel_idisguise_ownercolor%: relation colour between a viewer and the real player a disguise shows. */
	String ownerColor(Player one, Player two) {
		if(one == null || two == null) return "";
		PlayerDisguise disguise = disguises.get(two.getUniqueId());
		Player viewer = one;
		if(disguise == null || factionOwner(disguise) == null) {
			disguise = disguises.get(one.getUniqueId());
			viewer = two;
		}
		UUID owner = disguise == null ? null : factionOwner(disguise);
		return owner == null ? "" : factions.relationColor(viewer, owner);
	}

	/** The real player whose faction this disguise shows, or null (made-up name or turned off). */
	private UUID factionOwner(PlayerDisguise disguise) {
		return factions == null ? null : disguise.getOwnerUuid();
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
		for(PlayerDisguise left : leaving.values()) {
			if(!newReplacing.contains(left)) newReplacing.add(left);
		}
		online = Collections.unmodifiableList(newOnline);
		replacing = Collections.unmodifiableList(newReplacing);
		updateListener();
	}

	/** A disguised player left: their disguise stays for a few seconds and while an NPC copy of them exists. */
	private void startLeaving(PlayerDisguise disguise) {
		leaving.put(disguise.getUniqueId(), disguise);
		leavingUntil.put(disguise.getUniqueId(), System.currentTimeMillis() + 5000L);
	}

	/** Every second: forget players who left once the grace time is over and no NPC copy of them is left. */
	private void keepLeaving() {
		if(leaving.isEmpty()) return;
		boolean changed = false;
		long now = System.currentTimeMillis();
		for(PlayerDisguise disguise : new ArrayList<>(leaving.values())) {
			UUID uuid = disguise.getUniqueId();
			if(hasNpcCopy(disguise.getRealName())) {
				leavingUntil.put(uuid, now + 5000L);
			} else if(now > leavingUntil.getOrDefault(uuid, 0L)) {
				leaving.remove(uuid);
				leavingUntil.remove(uuid);
				changed = true;
			}
		}
		if(changed) rebuildSnapshots();
	}

	/** An NPC (CombatLogX / Citizens: a player entity marked "NPC") with that name exists. */
	private static boolean hasNpcCopy(String realName) {
		for(org.bukkit.World world : Bukkit.getWorlds()) {
			for(Player npc : world.getEntitiesByClass(Player.class)) {
				if(npc.hasMetadata("NPC") && realName.equalsIgnoreCase(npc.getName())) return true;
			}
		}
		return false;
	}

	/**
	 * Listens to packets only while someone player-disguised is online. Only listening (even without
	 * changing anything) can change the timing of packets other plugins send (it broke TAB's sidebar
	 * with team packets), so with nobody disguised iDisguise doesn't touch packets at all.
	 */
	private void updateListener() {
		if(rewriter == null) return;
		boolean invisible = invisibility != null && invisibility.anyOnline();
		if(invisiblePackets != null && invisible != invisibleListening) {
			if(invisible) {
				invisiblePackets.register();
			} else {
				invisiblePackets.unregister();
			}
			invisibleListening = invisible;
		}
		boolean wanted = !online.isEmpty() || !leaving.isEmpty() || invisible;
		if(wanted == listening) return;
		if(wanted) {
			rewriter.register();
		} else {
			rewriter.unregister();
		}
		listening = wanted;
		debug("Packet listener " + (wanted ? "on" : "off"));
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
		// Right away, before the refresh: others never see the new name with the old (or real) rank.
		if(tab != null) {
			tab.apply(player, effectiveRank(disguise), disguise.getFakeName(), sortName(disguise));
			tab.setTagSuffix(player, ownerTagSuffix(player, disguise));
			tab.setTabSuffix(player, ownTabSuffix(player, disguise));
		}
	}

	/**
	 * The TAB team name the disguise sorts under in tab (fake rank, then fake name), in TAB's own format,
	 * or null (disguised as someone online: no own tab entry; or TAB's sorting isn't GROUPS + A to Z).
	 */
	private String sortName(PlayerDisguise disguise) {
		if(tabLook == null || disguise.isHiddenFromTab()) return null;
		String rank = effectiveRank(disguise);
		return tabLook.sortName(rank != null ? rank : settings.defaultRank, disguise.getFakeName());
	}

	/** The own tab line's suffix while disguised: the rank's suffix with [V] when vanished (staff ranks have it already). */
	private String ownTabSuffix(Player player, PlayerDisguise disguise) {
		if(tabLook == null) return null;
		String rank = effectiveRank(disguise);
		return tabLook.ownTabSuffix(rank != null ? rank : settings.defaultRank, player.getWorld().getName(), placeholderApi);
	}

	/** %idisguise_vanishsuffix%: the [V] for a disguised player's own tab line ("" when not disguised). */
	String vanishSuffix(Player player) {
		PlayerDisguise disguise = player == null ? null : disguises.get(player.getUniqueId());
		if(disguise == null || tabLook == null) return "";
		String rank = effectiveRank(disguise);
		return tabLook.ownVanishSuffix(rank != null ? rank : settings.defaultRank, player);
	}

	private void resetLook(Player player, PlayerDisguise disguise) {
		String previous = disguise.getPreviousDisplayName();
		player.setDisplayName(previous != null ? previous : player.getName());
		// Right away, so the refresh after it already carries the real rank.
		if(tab != null) {
			tab.apply(player, null, null);
			tab.setTagSuffix(player, null);
			tab.setTabSuffix(player, null);
		}
		rebuildOwnerNameTag(player, disguise);
	}

	/**
	 * Disguised as a player who is online: TAB sometimes drops that player's own nametag team for
	 * others when the disguise ends (their rank above the head was gone). Rebuild it.
	 */
	private void rebuildOwnerNameTag(Player player, PlayerDisguise disguise) {
		Player owner = Bukkit.getPlayerExact(disguise.getFakeName());
		if(tab != null && owner != null && owner != player) {
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if(owner.isOnline()) tab.rebuildNameTag(owner);
			}, 20L);
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
		resendOwnEntry(player);
	}

	/** The player's own tab entry again (through the rewriter, which decides the name in it). */
	private void resendOwnEntry(Player player) {
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
		}, isNear(viewer, disguise) ? 60L : 1L);
	}

	/** Close enough that the viewer's game shows the player's body (it needs the tab entry for the skin). */
	private boolean isNear(Player viewer, PlayerDisguise disguise) {
		Player player = Bukkit.getPlayer(disguise.getUniqueId());
		if(player == null || player.getWorld() != viewer.getWorld()) return false;
		return player.getLocation().distanceSquared(viewer.getLocation()) < 80 * 80;
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
		// Own entry with the fake name again (it had the real one while the real player was online).
		resendOwnEntry(player);
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
		// Own entry back to the real account name, so TAB gives the name back to the real player's team.
		resendOwnEntry(player);
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
					+ (admin && disguise.isStaff() ? language.LIST_DISGUISED_STAFF : "")
					+ (admin && invisibility != null && invisibility.isInvisible(disguise.getUniqueId()) ? language.LIST_INVISIBLE : ""));
		}
		// Ops only: everyone with fake invisibility on (disguised or not).
		if(admin && invisibility != null) {
			List<String> names = new ArrayList<>();
			for(Player player : Bukkit.getOnlinePlayers()) {
				if(invisibility.isInvisible(player.getUniqueId())) names.add(player.getName());
			}
			if(!names.isEmpty()) lines.add(language.LIST_INVISIBLE_PLAYERS.replace("%players%", String.join(", ", names)));
		}
		return lines;
	}

	// ---------------------------------------------------------------- events

	@EventHandler(priority = EventPriority.LOWEST)
	public void onJoin(PlayerJoinEvent event) {
		Player joined = event.getPlayer();
		// Invisible: before the server sends the player to others, so they arrive invisible.
		if(invisibility != null && invisibility.isInvisible(joined.getUniqueId())) {
			invisibility.online(joined);
			updateListener();
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				sendOwnInvisibility(joined);
				if(tab != null && joined.isOnline() && isInvisible(joined)) tab.setNameTagHidden(joined, true);
			}, 20L);
		}
		leaving.remove(joined.getUniqueId());
		leavingUntil.remove(joined.getUniqueId());

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
		findOwner(disguise);
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
				if(joined.isOnline() && disguises.get(joined.getUniqueId()) == disguise) {
					applyLook(joined, disguise);
					if(disguise.hasPlayerPermissions()) {
						takePermissions(joined);
						stopBypass(joined);
					}
				}
			}, 5L);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		Player quit = event.getPlayer();
		if(invisibility != null) {
			// Given by staff: ends with the logout.
			if(invisibility.isGivenByStaff(quit.getUniqueId())) {
				invisibility.set(quit.getUniqueId(), false, false);
				log.end(quit.getName(), quit.getUniqueId(), "invisible", "invisible");
			}
			invisibility.offline(quit.getUniqueId());
		}
		lastTabInput.remove(quit.getUniqueId());
		// LuckPerms forgets temporary permissions on logout; taken again on join while still disguised.
		takenPermissions.remove(quit.getUniqueId());
		revealPlayers.remove(quit.getUniqueId());
		revealLooks.remove(quit.getUniqueId());
		revealTeams.remove(quit.getUniqueId());
		dropRevealTeams(quit);
		tabLines.remove(quit.getUniqueId());
		for(Map<UUID, String> lines : tabLines.values()) lines.remove(quit.getUniqueId());
		for(PlayerDisguise disguise : disguises.values()) {
			disguise.getRemovedFor().remove(quit.getUniqueId());
		}
		PlayerDisguise own = disguises.get(quit.getUniqueId());
		if(own != null && own.getProfile() != null && own.getProfile().replaceNameInMessages) startLeaving(own);
		if(own != null && own.isGivenByStaff()) {
			// Given with /odisguise: ends when the player leaves.
			disguises.remove(quit.getUniqueId());
			save();
			log.end(quit.getName(), quit.getUniqueId(), "player", own.getFakeName());
			for(Player viewer : Bukkit.getOnlinePlayers()) {
				if(viewer != quit) sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, own,
						new WrappedGameProfile(own.getFakeUniqueId(), own.getFakeName())));
			}
			own.setOffline();
			own = null;
		} else if(own != null) {
			// The disguise's tab entry goes away here: when this was the last disguised player, the packet
			// listener stops, and the server's own "player left" packet only names the real entry.
			for(Player viewer : Bukkit.getOnlinePlayers()) {
				if(viewer != quit) sendRaw(viewer, PacketRewriter.playerInfo(PlayerInfoAction.REMOVE_PLAYER, own,
						new WrappedGameProfile(own.getFakeUniqueId(), own.getFakeName())));
			}
			own.setOffline();
		}

		// A staff member disguised as the player who left shows up in tab again.
		for(PlayerDisguise disguise : online) {
			if(disguise != own && disguise.isHiddenFromTab() && disguise.getFakeName().equalsIgnoreCase(quit.getName())) {
				Bukkit.getScheduler().runTask(plugin, () -> unhide(disguise));
			}
		}
		rebuildSnapshots();
	}

	/**
	 * What a player types in chat stays as typed: otherwise "is Dann1e online?" would reach everyone
	 * (the writer too) as "is Hall3f online?" and tell who is behind the disguise.
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onChatTyped(AsyncPlayerChatEvent event) {
		Collection<PlayerDisguise> names = replacing;
		if(names.isEmpty()) return;
		String marked = NameReplacer.markTyped(event.getMessage(), names);
		if(!marked.equals(event.getMessage())) event.setMessage(marked);
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
		if(profile.hideChatTag || disguise.getChosenTag() != null) {
			// Your own DeluxeTags tag is replaced by the tag picked for the disguise, else by the tag of
			// the player whose name you use (live while that player is online, none for a made-up name).
			String shownTag = disguise.getChatTag() == null ? "" : disguise.getChatTag();
			if(disguise.getChosenTag() != null) {
				DeluxeTagsHook.Tag picked = deluxeTags == null || disguise.getChosenTag().isEmpty() ? null : deluxeTags.find(disguise.getChosenTag());
				shownTag = picked == null ? "" : picked.shown();
			} else if(placeholderApi) {
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
		UUID owner = factionOwner(disguise);
		if(owner != null) {
			// Sent here with the real player's faction tag (Factions would add your own); the DeluxeTags
			// placeholder must be filled first, since nobody else gets to see this format.
			if(format.contains("{deluxetags_tag}")) {
				String ownTag = placeholderApi ? PlaceholderHook.chatTag(player) : null;
				format = format.replace("{deluxetags_tag}", ownTag == null ? "" : ownTag);
			}
			if(factions.sendChat(event, format, owner)) {
				debug("Chat of " + player.getName() + " sent with the faction of " + disguise.getFakeName());
				return;
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
		if(invisibility != null && invisibility.anyOnline()) {
			for(Player player : Bukkit.getOnlinePlayers()) {
				if(!isInvisible(player)) continue;
				// Own invisibility ends when the permission is gone (given by staff: until logout).
				if(!invisibility.isGivenByStaff(player.getUniqueId()) && !has(player, PERM_INVIS)) {
					setInvisible(player, player, false);
					continue;
				}
				if(tab != null) tab.setNameTagHidden(player, true);
			}
		}
		for(PlayerDisguise disguise : new ArrayList<>(online)) {
			Player player = Bukkit.getPlayer(disguise.getUniqueId());
			if(player == null) continue;
			if(!disguise.isGivenByStaff() && !plugin.canDisguiseAsPlayer(player)) {
				if(undisguise(player)) send(player, language().UNDISGUISED_NO_ACCESS);
				continue;
			}
			// Disguised as someone online: follow their rank if it changed (chat shows it).
			Player owner = disguise.isHiddenFromTab() ? Bukkit.getPlayerExact(disguise.getFakeName()) : null;
			if(owner != null && owner != player) disguise.setOwnerRank(realRankOf(owner));
			if(tab != null && tab.ensure(player, effectiveRank(disguise), disguise.getFakeName(), sortName(disguise))) {
				// TAB was reloaded: it also forgot the disguise name for the nametag (it reads it from the
				// player's own tab entry) and the nametag faction.
				resendOwnEntry(player);
				tab.setTagSuffix(player, ownerTagSuffix(player, disguise));
				tab.setTabSuffix(player, ownTabSuffix(player, disguise));
			}
		}
	}

	/** An NPC copy of a disguised player (e.g. CombatLogX on logout) with the real name: the disguise for it, or null. */
	PlayerDisguise disguiseOfCopy(String profileName) {
		if(profileName == null) return null;
		for(PlayerDisguise disguise : replacing) {
			if(disguise.getRealName().equalsIgnoreCase(profileName)) return disguise;
		}
		for(PlayerDisguise disguise : leaving.values()) {
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
				dropRevealTeamsOf(viewer);
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
			}
			send(sender, on ? language.REVEAL_PLAYERS_ON : language.REVEAL_PLAYERS_OFF);
		}
	}

	/**
	 * Every second: the tab line (rank, name, faction) of each disguised player as every other player
	 * should see it, built like TAB would from groups.yml with the disguise rank. TAB can't update the
	 * disguise's tab entry (it has its own ID), so without this a rank or faction change, or a TAB update
	 * that came too early, would stay wrong for other players.
	 */
	private void keepTabLines() {
		if(tabLook == null || online.isEmpty()) return;
		for(PlayerDisguise disguise : online) {
			Player target = Bukkit.getPlayer(disguise.getUniqueId());
			if(target == null || disguise.isHiddenFromTab()) continue;
			Map<UUID, String> sent = tabLines.computeIfAbsent(disguise.getUniqueId(), id -> new ConcurrentHashMap<>());
			for(Player viewer : Bukkit.getOnlinePlayers()) {
				if(viewer == target || !viewer.canSee(target) || revealPlayers.contains(viewer.getUniqueId())
						|| disguise.getRemovedFor().contains(viewer.getUniqueId())) continue;
				UUID owner = factionOwner(disguise);
				TabLook.Look look;
				try {
					look = tabLook.look(viewer, target, effectiveRank(disguise), factions, owner);
				} catch(RuntimeException e) {
					debug("Could not build the tab line of " + target.getName(), e);
					return;
				}
				String line = NameReplacer.replace(look.tabLine, replacing);
				if(line.equals(sent.get(viewer.getUniqueId()))) continue;
				sent.put(viewer.getUniqueId(), line);
				sendRaw(viewer, PacketRewriter.displayName(disguise.getFakeUniqueId(), disguise.getFakeName(), target, line));
			}
		}
	}

	/** The viewer's game got the disguise's tab entry again: the keeper sends the line once more. */
	void forgetTabLine(UUID disguised, UUID viewer) {
		Map<UUID, String> sent = tabLines.get(disguised);
		if(sent != null) sent.remove(viewer);
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
				Set<UUID> teams = revealTeams.computeIfAbsent(viewerId, id -> ConcurrentHashMap.newKeySet());
				if(teams.add(target.getUniqueId())) {
					sendRaw(viewer, revealTeam(target, look, 0));
				} else if(!look.sameTag(before)) {
					sendRaw(viewer, revealTeam(target, look, 2));
				} else if(revealTick % 5 == 0) {
					// TAB may have put the name back into its own team (e.g. after a rank change).
					sendRaw(viewer, revealTeam(target, look, 3));
				}
			}
		}
		revealTick++;
	}

	/**
	 * Reveal players: an iDisguise team only on that viewer's screen, with the real name in it and the
	 * real rank and faction as nametag. Sent by iDisguise itself; TAB's packets are never touched.
	 * mode 0 = create, 1 = remove, 2 = update, 3 = add the name.
	 */
	private static PacketContainer revealTeam(Player target, TabLook.Look look, int mode) {
		PacketContainer packet = new PacketContainer(com.comphenix.protocol.PacketType.Play.Server.SCOREBOARD_TEAM);
		String name = "idR" + target.getUniqueId().toString().replace("-", "").substring(0, 13);
		packet.getStrings().write(0, name);
		packet.getIntegers().write(1, mode);
		if(mode == 0 || mode == 2) {
			packet.getStrings().write(1, name);
			packet.getStrings().write(2, cut(look.tagPrefix, 16));
			packet.getStrings().write(3, cut(look.tagSuffix, 16));
		}
		if(mode == 0 || mode == 3) {
			packet.getSpecificModifier(Collection.class).write(0, new ArrayList<>(Collections.singletonList(target.getName())));
		}
		return packet;
	}

	/** Removes the reveal nametag teams of a disguised player (disguise ended or they left). */
	private boolean dropRevealTeams(Player target) {
		boolean dropped = false;
		for(Map.Entry<UUID, Set<UUID>> entry : revealTeams.entrySet()) {
			if(!entry.getValue().remove(target.getUniqueId())) continue;
			dropped = true;
			Player viewer = Bukkit.getPlayer(entry.getKey());
			if(viewer != null) sendRaw(viewer, revealTeam(target, null, 1));
		}
		return dropped;
	}

	/** Reveal turned off: all reveal nametag teams go away for that viewer. */
	private void dropRevealTeamsOf(Player viewer) {
		Set<UUID> teams = revealTeams.remove(viewer.getUniqueId());
		if(teams == null) return;
		for(UUID id : teams) {
			Player target = Bukkit.getPlayer(id);
			if(target != null) sendRaw(viewer, revealTeam(target, null, 1));
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

	/**
	 * /disguise info <name>: who is or was behind that disguise name, and (when it's a real player) every
	 * disguise that player used. Sections with nothing in them are left out.
	 */
	public void infoCommand(CommandSender sender, String[] args) {
		Language language = language();
		if(!has(sender, PERM_WHOIS)) {
			send(sender, language.DISGUISE_NO_PERMISSION);
			return;
		}
		if(args.length < 2) {
			send(sender, language.INFO_USAGE);
			return;
		}
		String name = args[1];
		PlayerDisguise current = null;
		for(PlayerDisguise disguise : disguises.values()) {
			if(disguise.getFakeName().equalsIgnoreCase(name)) current = disguise;
		}
		PlayerDisguise now = current;
		boolean admin = has(sender, PERM_ADMIN);
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			List<DisguiseLog.Session> asName = DisguiseLog.newest(log.sessions(name, null), 8);
			List<DisguiseLog.Session> ofPlayer = DisguiseLog.newest(log.sessions(null, name), 10);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(now == null && asName.isEmpty() && ofPlayer.isEmpty()) {
					send(sender, language.INFO_NONE.replace("%name%", name));
					return;
				}
				if(now != null || !asName.isEmpty()) {
					sendUnchanged(sender, language.WHOIS_HEADER.replace("%name%", name));
					if(now != null) {
						sendUnchanged(sender, language.WHOIS_NOW.replace("%player%", now.getRealName())
								.replace("%state%", now.getProfile() != null ? language.WHOIS_ONLINE : language.WHOIS_OFFLINE)
								+ (admin && invisibility != null && invisibility.isInvisible(now.getUniqueId()) ? language.LIST_INVISIBLE : ""));
					}
					for(DisguiseLog.Session session : asName) sendUnchanged(sender, line(language, session));
				}
				if(!ofPlayer.isEmpty()) {
					sendUnchanged(sender, language.HISTORY_HEADER.replace("%player%", name));
					for(DisguiseLog.Session session : ofPlayer) sendUnchanged(sender, line(language, session));
				}
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
		// Staff: the first player name is the command's target and works as typed (/tp <real name>);
		// a real name later on (message text) is marked like for everyone else, so it isn't swapped.
		boolean staffTargetSeen = false;
		for(int i = 1; i < parts.length; i++) {
			boolean translated = false;
			for(PlayerDisguise disguise : online) {
				PlayerDisguiseSettings.Profile profile = disguise.getProfile();
				if(profile == null || !profile.commandsUseFakeName || !parts[i].equalsIgnoreCase(disguise.getFakeName())) continue;
				// Shows the real player's faction, so /f who <name> means the real player too.
				if(factionOwner(disguise) != null && FACTION_COMMANDS.contains(label)) continue;
				// The name of a real account: only messages and teleport requests reach the disguise; /pay,
				// /mail ... go to the real player (never money or items to the wrong person).
				if(disguise.getOwnerUuid() != null && !settings.realPlayerCommands.contains(label)) continue;
				// While the real player is online, the name means the real player.
				if(Bukkit.getPlayerExact(disguise.getFakeName()) != null) continue;
				parts[i] = disguise.getRealName();
				changed = true;
				translated = true;
				break;
			}
			if(translated) {
				staffTargetSeen = true;
				continue;
			}
			// The real name (or the start of it, which many plugins accept) of a disguised player acts
			// like an offline player for everyone else: otherwise "/msg <real name>" would reach the
			// disguised player and tell who they are. An invisible marker INSIDE the name makes the lookup
			// fail; it is removed from everything players see (NameReplacer.MARKER), and keeps the name swap
			// from turning the reply ("Dann1e not found") into the fake name.
			Player match = matchingPlayer(parts[i]);
			if(staff && match != null && !staffTargetSeen) {
				staffTargetSeen = true;
				continue;
			}
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
	 * %idisguise_tabprefix%: the TAB tab prefix (groups.yml) of the rank other players see: the disguise
	 * rank while player-disguised ("no rank" = default-rank), otherwise the real rank.
	 */
	String realTabPrefix(Player player) {
		if(player == null) return "";
		PlayerDisguise disguise = disguises.get(player.getUniqueId());
		boolean disguised = disguise != null && disguise.getProfile() != null;
		// Not disguised: exactly what TAB shows (%tab_tabprefix%), so normal players never see a difference.
		if(!disguised && placeholderApi) {
			String own = PlaceholderHook.tabTabPrefix(player);
			if(own != null) return own;
		}
		if(tabLook == null) return "";
		String group;
		if(disguised) {
			group = effectiveRank(disguise);
			if(group == null) group = settings.defaultRank;
		} else {
			group = luckPerms == null ? null : luckPerms.primaryGroup(player);
		}
		return group == null ? "" : tabLook.tabPrefix(group, player);
	}

	/** Real names of disguised players swapped for the fake ones (for %idisguise_swap_...%). */
	String swapNames(String text) {
		if(text == null) return "";
		return NameReplacer.replace(text, replacing);
	}

	/** %idisguise_name%: the name other players see. */
	String visibleName(Player player) {
		PlayerDisguise disguise = player == null ? null : disguises.get(player.getUniqueId());
		if(player == null) return "";
		return disguise != null ? disguise.getFakeName() : player.getName();
	}

}
