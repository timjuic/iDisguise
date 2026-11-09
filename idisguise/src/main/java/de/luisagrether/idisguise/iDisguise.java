package de.luisagrether.idisguise;

import java.beans.Statement;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import com.cryptomorin.xseries.XSound;
import com.cryptomorin.xseries.messages.ActionBar;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import de.luisagrether.idisguise.api.DisguiseAPI;
import de.luisagrether.idisguise.api.EventCancelledException;
import de.luisagrether.idisguise.api.PlayerDisguiseAsPlayerEvent;
import de.luisagrether.idisguise.api.PlayerDisguiseEvent;
import de.luisagrether.idisguise.api.PlayerInteractDisguisedPlayerEvent;
import de.luisagrether.idisguise.api.PlayerUndisguiseEvent;
import de.luisagrether.idisguise.io.Config;
import de.luisagrether.idisguise.io.Language;
import de.luisagrether.idisguise.io.UpdateCheck;
import de.luisagrether.idisguise.player.PlayerDisguiseManager;
import de.luisagrether.util.ObjectUtil;
import de.luisagrether.util.StringUtil;

public class iDisguise extends JavaPlugin implements Listener, DisguiseAPI {
	
	public static final Pattern INT_VAL = Pattern.compile("[+-]?[0-9]+");
	public static final Pattern DOUBLE_VAL = Pattern.compile("[+-]?[0-9]*\\.[0-9]+");
	public static final Pattern ENUM_VAL = Pattern.compile("(?:([A-Za-z0-9.]+)\\.){0,1}([A-Za-z0-9_]+)");
	public static final Pattern ITEMSTACK_VAL = Pattern.compile("([A-Za-z0-9_]+),([0-9]+)");
	public static final Pattern STRING_VAL = Pattern.compile("\".*\"");
	public static final Pattern ACCOUNTNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

	private static final Pattern MC_VERSION = Pattern.compile("([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?");
	private static int[] MINECRAFT_VERSION;
	private static String PACKAGE_VERSION = null;
	private static boolean LEGACY_INJECTION = false;
	private static Method LegacyInjector_inject = null;
	private static Method LegacyInjector_toggleIntercept = null;
	private static Method LegacyInjector_injectAll = null;
	private static Method LegacyInjector_toggleInterceptAll = null;
	private static Method CraftEntity_getHandle = null;
	private static Method Entity_copyMetadataFrom = null;
	private static boolean LEGACY_DISABLE_AI = false;
	private static Class<?> EntityInsentient = null;
	private static Method EntityInsentient_setNoAI = null;
	private static boolean PLAYER_DISGUISE_AVAILABLE = false;
	private static boolean LEGACY_PROFILES = false;
	private static boolean PLAYER_DISGUISE_VIEWSELF = false;
	private static boolean LEGACY_PLAYER_DISGUISE_VIEWSELF = false;
	private static Method OfflinePlayer_getPlayerProfile = null;
	private static Method PlayerProfile_update = null;
	private static Class<?> CraftPlayerProfile = null;
	private static Constructor<?> CraftPlayerProfile_new = null;
	private static Method CraftPlayerProfile_buildGameProfile = null;
	private static Field CraftOfflinePlayer_profile = null;
	private static Method CraftPlayer_getHandle = null;
	private static Method CraftPlayer_getProfile = null;
	private static Method CraftServer_getServer = null;
	private static Method MinecraftServer_getMinecraftSessionService = null;
	private static Method MinecraftSessionService_fillProfileProperties = null;
	private static Constructor<?> GameProfile_new = null;
	private static Method GameProfile_getProperties = null;
	private static Field PropertyMap_properties = null;
	private static sun.misc.Unsafe UNSAFE = null;
	private static Class<?> EntityPlayer = null;
	private static Field EntityPlayer_playerConnection = null;
	private static Method PlayerConnection_sendPacket = null;
	private static Constructor<?> PacketUpdatePlayerInfo_new = null;
	private static Constructor<?> PacketRemovePlayerInfo_new = null;
	private static Object UpdatePlayerInfo_ADD_PLAYER = null;
	private static Object UpdatePlayerInfo_REMOVE_PLAYER = null;
	private static Method World_setGameRule = null;
	private static Object GameRule_doMobSpawning = null;
	private static Object GameRule_doMobSpawning_value = null;
	private static boolean LEGACY_MATERIALS = false;
	private static Method Material_createBlockData = null;
	private static Class<?> BlockData = null;
	private static Class<?> MaterialData = null;
	private static Constructor<?> MaterialData_new = null;

	private static String formatOBCClass(String path) {
		return PACKAGE_VERSION == null ? "org.bukkit.craftbukkit." + path : "org.bukkit.craftbukkit." + PACKAGE_VERSION + "." + path;
	}

	private static String formatNMSClass(String path) {
		return PACKAGE_VERSION == null ? "net.minecraft.server." + path : "net.minecraft.server." + PACKAGE_VERSION + "." + path;
	}

	static {
		try {
			Matcher m = MC_VERSION.matcher(Bukkit.getBukkitVersion());
			if(m.find()) {
				if(m.group(3) != null) {
					MINECRAFT_VERSION = new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
				} else {
					MINECRAFT_VERSION = new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
				}
			}
			
			PACKAGE_VERSION = Bukkit.getServer().getClass().getName();
			PACKAGE_VERSION = PACKAGE_VERSION.substring(0, PACKAGE_VERSION.lastIndexOf("."));
			PACKAGE_VERSION = PACKAGE_VERSION.substring(PACKAGE_VERSION.lastIndexOf(".") + 1);
			if(PACKAGE_VERSION.equals("craftbukkit")) PACKAGE_VERSION = null;
		} catch(NumberFormatException|IndexOutOfBoundsException e) {
			MINECRAFT_VERSION = null;
		}
		if(MINECRAFT_VERSION != null) {
			if(MINECRAFT_VERSION[0] == 1 && MINECRAFT_VERSION[1] < 18) {
				LEGACY_INJECTION = true;
				if(MINECRAFT_VERSION[1] >= 14) {
					try {
						Class<?> LegacyInjector = Class.forName("de.luisagrether.idisguise.impl.EntityTracker_" + PACKAGE_VERSION);
						LegacyInjector_inject = LegacyInjector.getMethod("inject", Entity.class, Player.class);
						LegacyInjector_toggleIntercept = LegacyInjector.getMethod("toggleIntercept", Entity.class, Player.class, boolean.class);
					} catch(ClassNotFoundException|NoSuchMethodException e) {
					}
				} else if(MINECRAFT_VERSION[1] >= 8) {
					try {
						Class<?> LegacyInjector = Class.forName("de.luisagrether.idisguise.impl.EntityTrackerEntry_" + PACKAGE_VERSION);
						LegacyInjector_inject = LegacyInjector.getMethod("inject", Entity.class, Player.class);
						LegacyInjector_toggleIntercept = LegacyInjector.getMethod("toggleIntercept", Entity.class, Player.class, boolean.class);
						try {
							LegacyInjector_injectAll = LegacyInjector.getMethod("injectAll", Entity.class);
							LegacyInjector_toggleInterceptAll = LegacyInjector.getMethod("toggleInterceptAll", Entity.class, boolean.class);
						} catch(NoSuchMethodException e) {
							// Older impl without all-observer support; fall back to hidePlayer at runtime.
						}
					} catch(ClassNotFoundException|NoSuchMethodException e) {
					}
				}
				try {
					Class<?> CraftEntity = Class.forName(formatOBCClass("entity.CraftEntity"));
					CraftEntity_getHandle = CraftEntity.getMethod("getHandle");
					Class<?> Entity = CraftEntity_getHandle.getReturnType();
					if(MINECRAFT_VERSION[1] == 17) {
						Entity_copyMetadataFrom = Entity.getDeclaredMethod("t", Entity);
					} else if(MINECRAFT_VERSION[1] >= 13) {
						Entity_copyMetadataFrom = Entity.getDeclaredMethod("v", Entity);
					} else if(MINECRAFT_VERSION[1] >= 9) {
						Entity_copyMetadataFrom = Entity.getDeclaredMethod("a", Entity);
					} else {
						Entity_copyMetadataFrom = Entity.getDeclaredMethod("n", Entity);
					}
					Entity_copyMetadataFrom.setAccessible(true);
				} catch(ClassNotFoundException|NoSuchMethodException e) {
					LegacyInjector_inject = null;
				}
				try {
					LivingEntity.class.getDeclaredMethod("setAI", boolean.class);
					// everything fine
				} catch(NoSuchMethodException e) {
					try {
						EntityInsentient = Class.forName(formatNMSClass("EntityInsentient"));
						EntityInsentient_setNoAI = EntityInsentient.getDeclaredMethod("k", boolean.class);
						EntityInsentient_setNoAI.setAccessible(true);
						LEGACY_DISABLE_AI = true;
					} catch(ClassNotFoundException|NoSuchMethodException e2) {
						LegacyInjector_inject = null;
					}
				}
			}
			try {
				Class<?> CraftPlayer = Class.forName(formatOBCClass("entity.CraftPlayer"));
				CraftPlayer_getHandle = CraftPlayer.getMethod("getHandle");
				CraftPlayer_getProfile = CraftPlayer.getMethod("getProfile");
				
				Class<?> GameProfile = null;
				try {
					OfflinePlayer_getPlayerProfile = OfflinePlayer.class.getDeclaredMethod("getPlayerProfile");
					Class<?> PlayerProfile = OfflinePlayer_getPlayerProfile.getReturnType();
					PlayerProfile_update = PlayerProfile.getDeclaredMethod("update");
					CraftPlayerProfile = Class.forName(formatOBCClass("profile.CraftPlayerProfile"));
					CraftPlayerProfile_new = CraftPlayerProfile.getConstructor(UUID.class, String.class);
					CraftPlayerProfile_buildGameProfile = CraftPlayerProfile.getDeclaredMethod("buildGameProfile");
					GameProfile = CraftPlayerProfile_buildGameProfile.getReturnType();
					
					LEGACY_PROFILES = false;
				} catch(NoSuchMethodException|ClassNotFoundException e) {
					Class<?> CraftOfflinePlayer = Class.forName(formatOBCClass("CraftOfflinePlayer"));
					CraftOfflinePlayer_profile = CraftOfflinePlayer.getDeclaredField("profile");
					CraftOfflinePlayer_profile.setAccessible(true);
					Class<?> CraftServer = Bukkit.getServer().getClass();
					CraftServer_getServer = CraftServer.getMethod("getServer");
					Class<?> MinecraftServer = CraftServer_getServer.getReturnType();
					for(Method method : MinecraftServer.getMethods()) {
						if(method.getReturnType().getSimpleName().equals("MinecraftSessionService")) {
							MinecraftServer_getMinecraftSessionService = method;
							break;
						}
					}
					if(MinecraftServer_getMinecraftSessionService == null) throw new NoSuchMethodException("Method MinecraftServer.getMinecraftSessionService() not found.");
					
					Class<?> MinecraftSessionService = MinecraftServer_getMinecraftSessionService.getReturnType();
					for(Method method : MinecraftSessionService.getMethods()) {
						if(method.getName().equals("fillProfileProperties")) {
							MinecraftSessionService_fillProfileProperties = method;
							LEGACY_PROFILES = true;
							break;
						}
					}
					if(MinecraftSessionService_fillProfileProperties == null) throw new NoSuchMethodException("Method MinecraftSessionService.fillProfileProperties() not found.");
					
					GameProfile = MinecraftSessionService_fillProfileProperties.getReturnType();
					GameProfile_new = GameProfile.getConstructor(UUID.class, String.class);
				}
				
				for(Method method : GameProfile.getMethods()) {
					if(StringUtil.equals(method.getName(), "getProperties", "properties")) {
						GameProfile_getProperties = method;
						break;
					}
				}
				if(GameProfile_getProperties == null) throw new NoSuchMethodException("Method GameProfile.properties() not found.");
				
				Class<?> PropertyMap = GameProfile_getProperties.getReturnType();
				PropertyMap_properties = PropertyMap.getDeclaredField("properties");
				PropertyMap_properties.setAccessible(true);
				// Always init Unsafe -- the immutable PropertyMap swap below depends on it,
				// and some non-vanilla servers (e.g. WineSpigot) ship an immutable inner map even
				// on older Minecraft versions where the original code skipped the init.
				Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
				theUnsafe.setAccessible(true);
				UNSAFE = (sun.misc.Unsafe)theUnsafe.get(null);

				PLAYER_DISGUISE_AVAILABLE = true;
			} catch(ClassNotFoundException|NoSuchFieldException|NoSuchMethodException|IllegalAccessException e) {
				e.printStackTrace();
			}
			try {
				EntityPlayer = CraftPlayer_getHandle.getReturnType();
				for(Field field : EntityPlayer.getFields()) {
					if(StringUtil.equals(field.getName(), "playerConnection", "connection") || field.getType().getSimpleName().equals("PlayerConnection")) {
						EntityPlayer_playerConnection = field;
						break;
					}
				}
				if(EntityPlayer_playerConnection == null) throw new NoSuchFieldException("Field EntityPlayer.playerConnection not found.");

				Class<?> PlayerConnection = EntityPlayer_playerConnection.getType();
				for(Method method : PlayerConnection.getMethods()) {
					if(StringUtil.equals(method.getName(), "sendPacket", "send") && method.getParameterTypes().length == 1 /*&& method.getParameterTypes()[0].getSimpleName().equals("Packet")*/) {
						PlayerConnection_sendPacket = method;
						break;
					}
				}
				if(PlayerConnection_sendPacket == null) throw new NoSuchMethodException("Method PlayerConnection.sendPacket() not found.");

				try {
					Class<?> PacketRemovePlayerInfo = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
					PacketRemovePlayerInfo_new = PacketRemovePlayerInfo.getConstructor(List.class);
					Class<?> PacketUpdatePlayerInfo = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
					Class<?> UpdatePlayerInfo = null;
					for(Class<?> clazz : PacketUpdatePlayerInfo.getDeclaredClasses()) {
						if(clazz.isEnum()) {
							UpdatePlayerInfo = clazz;
							break;
						}
					}
					PacketUpdatePlayerInfo_new = PacketUpdatePlayerInfo.getConstructor(UpdatePlayerInfo, EntityPlayer);
					UpdatePlayerInfo_ADD_PLAYER = UpdatePlayerInfo.getEnumConstants()[0];
					PLAYER_DISGUISE_VIEWSELF = true;
				} catch(ClassNotFoundException e) {
					try {
						Class<?> PacketUpdatePlayerInfo = Class.forName("net.minecraft.network.protocol.game.PacketPlayOutPlayerInfo");
						Class<?> UpdatePlayerInfo = null;
						for(Class<?> clazz : PacketUpdatePlayerInfo.getDeclaredClasses()) {
							if(clazz.isEnum()) {
								UpdatePlayerInfo = clazz;
								break;
							}
						}
						PacketUpdatePlayerInfo_new = PacketUpdatePlayerInfo.getConstructor(UpdatePlayerInfo, Array.newInstance(EntityPlayer, 0).getClass());
						UpdatePlayerInfo_ADD_PLAYER = UpdatePlayerInfo.getEnumConstants()[0];
						UpdatePlayerInfo_REMOVE_PLAYER = UpdatePlayerInfo.getEnumConstants()[UpdatePlayerInfo.getEnumConstants().length - 1];
						PLAYER_DISGUISE_VIEWSELF = true;
						LEGACY_PLAYER_DISGUISE_VIEWSELF = true;
					} catch(ClassNotFoundException e2) {
						try {
							Class<?> PacketUpdatePlayerInfo = Class.forName(formatNMSClass("PacketPlayOutPlayerInfo"));
							Class<?> UpdatePlayerInfo = null;
							for(Class<?> clazz : PacketUpdatePlayerInfo.getDeclaredClasses()) {
								if(clazz.isEnum()) {
									UpdatePlayerInfo = clazz;
									break;
								}
							}
							if(UpdatePlayerInfo == null) {
								UpdatePlayerInfo = Class.forName(formatNMSClass("EnumPlayerInfoAction"));
							}
							PacketUpdatePlayerInfo_new = PacketUpdatePlayerInfo.getConstructor(UpdatePlayerInfo, Array.newInstance(EntityPlayer, 0).getClass());
							UpdatePlayerInfo_ADD_PLAYER = UpdatePlayerInfo.getEnumConstants()[0];
							UpdatePlayerInfo_REMOVE_PLAYER = UpdatePlayerInfo.getEnumConstants()[UpdatePlayerInfo.getEnumConstants().length - 1];
							PLAYER_DISGUISE_VIEWSELF = true;
							LEGACY_PLAYER_DISGUISE_VIEWSELF = true;
						} catch(ClassNotFoundException e3) {
							e.printStackTrace();
						}
					}
				}
				if(PLAYER_DISGUISE_VIEWSELF) {
					try {
						World_setGameRule = World.class.getDeclaredMethod("setGameRuleValue", String.class, String.class);
						GameRule_doMobSpawning = "doMobSpawning";
						GameRule_doMobSpawning_value = "false";
					} catch(NoSuchMethodException e) {
						try {
							Class<?> GameRule = Class.forName("org.bukkit.GameRule");
							World_setGameRule = World.class.getDeclaredMethod("setGameRule", GameRule, Object.class);
							GameRule_doMobSpawning_value = false;
							try {
								GameRule_doMobSpawning = GameRule.getDeclaredField("DO_MOB_SPAWNING").get(null);
							} catch(NoSuchFieldException|IllegalAccessException e2) {
								try {
									GameRule_doMobSpawning = GameRule.getDeclaredField("SPAWN_MOBS").get(null);
								} catch(NoSuchFieldException|IllegalAccessException e3) {
									PLAYER_DISGUISE_VIEWSELF = false;
								}
							}
						} catch(ClassNotFoundException|NoSuchMethodException e2) {
							PLAYER_DISGUISE_VIEWSELF = false;
						}
					}
				}
			} catch(NoSuchFieldException|NoSuchMethodException e) {
				e.printStackTrace();
			}
			try {
				Material_createBlockData = Material.class.getMethod("createBlockData");
				BlockData = Material_createBlockData.getReturnType();
			} catch(NoSuchMethodException e) {
				LEGACY_MATERIALS = true;
				try {
					MaterialData = Class.forName("org.bukkit.material.MaterialData");
					MaterialData_new = MaterialData.getConstructor(Material.class);
				} catch(ClassNotFoundException|NoSuchMethodException e2) {
				}
			}
		}
	}
	
	private static iDisguise INSTANCE;
	
	private boolean debugMode;
	private Config config;
	private Language language;
	private Metrics metrics;
	private Map<EntityType, List<String>> tabCompletions = new HashMap<>();
	private Map<UUID, Entity> disguiseMap = new HashMap<>();
	private PlayerDisguiseManager playerDisguises;
	// Players who were disguised when they started a cross-world teleport. Re-disguise happens
	// in PlayerChangedWorldEvent (after the teleport completes) so the new mob spawns in the
	// destination world and the invisibility potion lands in the right context.
	private Map<UUID, EntityType> pendingCrossWorldDisguises = new HashMap<>();
	private Map<UUID, Long> mobSoundCooldowns = new HashMap<>();
	// In-memory only -- intentionally not persisted to disk. A server restart should drop everyone
	// back to undisguised, so we never serialize these.
	private Map<UUID, EntityType> pendingRejoinMobDisguises = new HashMap<>();
	private Map<UUID, Long> lastDisguiseTimestamps = new HashMap<>();
	private Map<UUID, Long> lastPearlBlockNotice = new HashMap<>();
	private Map<UUID, Long> lastBlockPlaceNotice = new HashMap<>();
	private Map<UUID, Long> lastBlockBreakNotice = new HashMap<>();
	// Set only while disguise0 spawns the disguise mob, so handleDisguiseSpawn can tell that spawn
	// apart from anything else spawning during the call.
	private EntityType spawningDisguiseType;
	// null when WorldGuard 6 isn't installed; disguise.blocked-regions does nothing then.
	private WorldGuardRegions worldGuardRegions;
	
	public iDisguise() { INSTANCE = this; }
	
	public void onEnable() {
		if(MINECRAFT_VERSION == null) {
			getLogger().severe("This Minecraft server version is not supported (1)!");
			getPluginLoader().disablePlugin(this);
			return;
		}

		if(LEGACY_INJECTION && LegacyInjector_inject == null) {
			getLogger().severe("This Minecraft server version is not supported (2)!");
			getPluginLoader().disablePlugin(this);
			return;
		}

		if(!getDataFolder().exists()) {
			getDataFolder().mkdirs();
		}
		debugMode = new File(getDataFolder(), "debug").isFile();

		config = new Config(this);
		config.loadData();
		config.saveData();

		// Config-driven debug flag (in addition to the legacy file marker).
		if(config.DEBUG_MODE) debugMode = true;

		language = new Language(this);
		language.loadData();
		language.saveData();
		language.applyPrefix();

		worldGuardRegions = WorldGuardRegions.detect(this);
		if(worldGuardRegions == null && !config.BLOCKED_REGIONS.isEmpty()) {
			getLogger().warning("disguise.blocked-regions is set, but WorldGuard 6 isn't installed. Disguises won't be blocked in any region.");
		}

		for(EntityType type : EntityType.values()) {
			if(config.DISGUISE_TYPE_BLACKLIST.contains(type.name())) continue;
			
			List<String> completions = new ArrayList<>();

			if(type == EntityType.PLAYER) {
				for(String targetSkin : new String[] {"<ACCOUNT-NAME>", "Notch", "jeb_", "Dinnerbone", "LuisaGrether"}) {
					if(!config.PLAYER_DISGUISE_BLACKLIST.contains(targetSkin.toLowerCase(Locale.ENGLISH))) {
						completions.add(targetSkin);
					}
				}
			} else {
				Class<?> clazz = type.getEntityClass();
				for(Method method : clazz.getMethods()) {
					String methodName = method.getName();
					if(!config.STATEMENT_WHITELIST.contains(methodName)) continue;

					if(!LEGACY_MATERIALS && StringUtil.equals(methodName, "setDisplayBlock", "setCarriedMaterial")) continue;
					if(LEGACY_MATERIALS && StringUtil.equals(methodName, "setDisplayBlockData", "setCarriedBlock")) continue;

					if(method.isAnnotationPresent(Deprecated.class)) continue;

					if(method.getParameterTypes().length == 0) {
						completions.add(methodName + "()");
					} else if(method.getParameterTypes().length == 1) {
						if(method.getParameterTypes()[0] == boolean.class) {
							completions.add(methodName + "(true)");
							completions.add(methodName + "(false)");
						} else if(method.getParameterTypes()[0] == int.class) {
							completions.add(methodName + "(<int>)");
						} else if(method.getParameterTypes()[0] == double.class || method.getParameterTypes()[0] == float.class) {
							completions.add(methodName + "(<float>)");
						} else if(method.getParameterTypes()[0] == ItemStack.class) {
							try {
								Method name = Enum.class.getDeclaredMethod("name");
								for(Material value : Material.class.getEnumConstants()) {
									completions.add(methodName + "(" + name.invoke(value) + ")");
									completions.add(methodName + "(" + name.invoke(value) + ",<int>)");
								}
							} catch(Exception e) {
							}
						} else if(method.getParameterTypes()[0] == (LEGACY_MATERIALS ? MaterialData : BlockData)) {
							try {
								Method name = Enum.class.getDeclaredMethod("name");
								for(Material value : Material.class.getEnumConstants()) {
									if(((String)name.invoke(value)).startsWith(Material.LEGACY_PREFIX)) continue;
									if(value.isBlock()) {
										completions.add(methodName + "(" + name.invoke(value) + ")");
									}
								}
							} catch(Exception e) {
							}
						} else if(method.getParameterTypes()[0] == Color.class) {
							for(Field field : Color.class.getDeclaredFields()) {
								if(field.getType().equals(Color.class)) {
									completions.add(methodName + "(" + field.getName() + ")");
								}
							}
						} else if(method.getParameterTypes()[0].isEnum()) {
							try {
								Method name = Enum.class.getDeclaredMethod("name");
								for(Object value : method.getParameterTypes()[0].getEnumConstants()) {
									completions.add(methodName + "(" + name.invoke(value) + ")");
								}
							} catch(Exception e) {
							}
						} else if(method.getParameterTypes()[0] == String.class) {
							completions.add(methodName + "(\"YOUR TEXT\")");
						} else {
							try {
								if(Class.forName("org.bukkit.Keyed").isAssignableFrom(method.getParameterTypes()[0])) {
									for(Field field : method.getParameterTypes()[0].getDeclaredFields()) {
										if(field.getType().equals(method.getParameterTypes()[0])) {
											completions.add(methodName + "(" + field.getName() + ")");
										}
									}
								}
							} catch(ClassNotFoundException e) {
							}
							/*try {
								if(Class.forName("org.bukkit.util.OldEnum").isAssignableFrom(method.getParameterTypes()[0])) {
									for(Field field : method.getParameterTypes()[0].getDeclaredFields()) {
										if(field.getType().equals(method.getParameterTypes()[0])) {
											completions.add(methodName + "(" + field.getName() + ")");
										}
									}
								}
							} catch(Exception e) {
							}*/
						}
					}
				}
			}

			tabCompletions.put(type, completions);
		}
		Bukkit.getPluginManager().registerEvents(this, this);

		try {
			/* Remove the dummy world of the old player disguise if existent */
			File worldDir = new File(Bukkit.getWorldContainer(), "iDisguiseDummyWorld");
			if(worldDir.isDirectory() && Bukkit.getWorld("iDisguiseDummyWorld") == null) {
				ObjectUtil.deleteDirectory(worldDir);
			}
		} catch(SecurityException e) {
		}
		try {
			/* Remove old dummy world if existent */
			File worldDir = new File(Bukkit.getWorldContainer(), "iDisguise-Dummy");
			if(worldDir.isDirectory()) {
				ObjectUtil.deleteDirectory(worldDir);
			}
		} catch(SecurityException e) {
		}

		if(config.USE_PERMISSION_NODES) {
			Bukkit.getPluginManager().addPermission(new Permission("iDisguise.*", PermissionDefault.OP));
			Bukkit.getPluginManager().addPermission(new Permission("iDisguise.admin", PermissionDefault.OP));
			Bukkit.getPluginManager().addPermission(new Permission("iDisguise.disguise.*", PermissionDefault.OP));
			Bukkit.getPluginManager().addPermission(new Permission("iDisguise.others", PermissionDefault.OP));
			Bukkit.getPluginManager().addPermission(new Permission("iDisguise.sound", PermissionDefault.TRUE));
			Bukkit.getPluginManager().addPermission(new Permission("iDisguise.bypass.regions", PermissionDefault.OP));
			Bukkit.getPluginManager().addPermission(new Permission("iDisguise.see", PermissionDefault.OP));
			for(EntityType type : EntityType.values()) {
				if(config.DISGUISE_TYPE_BLACKLIST.contains(type.name())) continue;
				Bukkit.getPluginManager().addPermission(new Permission("iDisguise.disguise." + type.name(), PermissionDefault.OP));
			}
		}

		playerDisguises = new PlayerDisguiseManager(this);
		playerDisguises.enable();

		Bukkit.getServicesManager().register(DisguiseAPI.class, this, this, ServicePriority.Normal);

		/* Metrics start */
		metrics = new Metrics(this);
		metrics.addCustomChart(new Metrics.SingleLineChart("disguisedPlayers", () -> (disguiseMap.size() + playerDisguises.onlineCount())));
		metrics.addCustomChart(new Metrics.SimplePie("storageType", () -> "unavailable"));
		metrics.addCustomChart(new Metrics.SimplePie("updateChecking", () -> config.UPDATE_CHECK ? config.UPDATE_DOWNLOAD ? "check and download" : "check only" : "disabled"));
		metrics.addCustomChart(new Metrics.SimplePie("realisticSoundEffects", () -> "enabled"));
		metrics.addCustomChart(new Metrics.SimplePie("undisguisePermission", () -> "unavailable"));
		metrics.addCustomChart(new Metrics.SimplePie("viewableDisguises", () -> "enabled"));
		metrics.addCustomChart(new Metrics.SimplePie("channelInjector", () -> "unavailable"));
		metrics.addCustomChart(new Metrics.SimplePie("ghostDisguise", () -> "unavailable"));
		/* Metrics end */

		if(config.UPDATE_CHECK) {
			Bukkit.getScheduler().runTaskLaterAsynchronously(this, new UpdateCheck(this, getServer().getConsoleSender(), config.UPDATE_DOWNLOAD), 20L);
		}

		getLogger().info("Enabled!");
	}
	
	public void onDisable() {
		// Player disguises stay saved (they survive restarts); only mob disguises end here.
		for(Player player : Bukkit.getOnlinePlayers()) {
			if(disguiseMap.containsKey(player.getUniqueId())) {
				undisguise0(player);
			}
		}
		if(playerDisguises != null) playerDisguises.disable();

		for(Entity entity : disguiseMap.values()) {
			entity.remove();
		}
		disguiseMap.clear();

		getLogger().info("Disabled!");
	}
	
	public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
		if(debugMode) {
			for(int i = 0; i < args.length; i++) getLogger().info("args[" + i + "] = \"" + args[i] + "\"");
		}
		String argsJoined = String.join(" ", args);
		List<String> argsSplit = new ArrayList<>();
		int lastSplit = 0;
		int index = 0;
		boolean quotation = false;
		while(index < argsJoined.length()) {
			if(argsJoined.charAt(index) == ' ') {
				if(!quotation) {
					if(index != lastSplit) argsSplit.add(argsJoined.substring(lastSplit, index).replace("\\\"", "\""));
					lastSplit = index + 1;
				}
			} else if(argsJoined.charAt(index) == '"' && (index == 0 || argsJoined.charAt(index - 1) != '\\')) {
				quotation = !quotation;
			}
			index++;
		}
		if(index != lastSplit) argsSplit.add(argsJoined.substring(lastSplit, index));
		args = argsSplit.toArray(new String[0]);
		if(debugMode) {
			for(int i = 0; i < args.length; i++) getLogger().info("new_args[" + i + "] = \"" + args[i] + "\"");
		}

		if(StringUtil.equalsIgnoreCase(command.getName(), "disguise", "odisguise")) {
			boolean self;
			Player target = null;
			if(command.getName().equalsIgnoreCase("disguise")) {
				if(!(sender instanceof Player)) {
					sender.sendMessage(language.CONSOLE_USE_COMMAND);
					return true;
				}
				self = true;
				target = (Player)sender;
			} else {
				if(!hasPermissionOthers(sender)) {
					sender.sendMessage(language.DISGUISE_NO_PERMISSION);
					return true;
				}
				if(args.length == 0) {
					sender.sendMessage(language.ODISGUISE_NO_PLAYERNAME_GIVEN);
					return true;
				}
				self = false;
				target = Bukkit.getPlayerExact(args[0]);
				if(target == null)
					target = Bukkit.getPlayer(args[0]);
				if(target == null && !StringUtil.equalsIgnoreCase(args[0], "help", "?")) {
					sender.sendMessage(language.ODISGUISE_PLAYER_NOT_FOUND.replace("%player%", args[0]));
					return true;
				}
				if(target != null) {
					args = Arrays.copyOfRange(args, 1, args.length);
				}
			}
			boolean invisFlag = false;
			if(playerDisguises.isAvailable() && hasNode(sender, PlayerDisguiseManager.PERM_INVIS)) {
				List<String> kept = new ArrayList<>();
				for(String arg : args) {
					if("-i".equalsIgnoreCase(arg)) {
						invisFlag = true;
					} else {
						kept.add(arg);
					}
				}
				args = kept.toArray(new String[0]);
			}
			final boolean invis = invisFlag;
			if(args.length == 0) {
				// /disguise alone: the help, with the active disguise at the bottom.
				if(self) {
					sendHelpPage(sender, 1);
					sendStatus(target);
				}
				if(anyDisguise(target)) {
					if(currentDisguise(target) == EntityType.PLAYER) {
						if(!self) {
							String fakeName = playerDisguises.get(target.getUniqueId()).getFakeName();
							playerDisguises.sendUnchanged(sender,
								language.ODISGUISE_CURRENTLY_DISGUISED_AS_PLAYER.replace("%player%", target.getName()).replace("%targetSkin%", fakeName));
						}
					} else {
						if(self) {
							target.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 1, true, false));
							Entity finalEntity = disguiseMap.get(target.getUniqueId());
							Player finalTarget = target;
							if(!LEGACY_INJECTION) {
								finalTarget.showEntity(this, finalEntity);
							} else {
								try {
									LegacyInjector_toggleIntercept.invoke(null, finalEntity, finalTarget, false);
								} catch(Exception e) {
									if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
								}
							}
							Bukkit.getScheduler().runTaskLater(this, () -> {
								if(!LEGACY_INJECTION) {
									finalTarget.hideEntity(this, finalEntity);
								} else if(finalTarget.getWorld().equals(finalEntity.getWorld())) {
									try {
										LegacyInjector_toggleIntercept.invoke(null, finalEntity, finalTarget, true);
									} catch(Exception e) {
										if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
									}
								}
							}, 100L);
						} else {
							sender.sendMessage(language.ODISGUISE_CURRENTLY_DISGUISED_AS_ENTITY.replace("%player%", target.getName()).replace("%entityType%", currentDisguise(target).name()));
						}
					}
				} else {
					if(!self) sender.sendMessage(language.ODISGUISE_CURRENTLY_NOT_DISGUISED.replace("%player%", target.getName()));
				}
			} else if(StringUtil.equalsIgnoreCase(args[0], "help", "?")) {
				int page = 1;
				if(args.length > 1) {
					try {
						page = Integer.parseInt(args[1]);
					} catch(IllegalArgumentException e) {
					}
				}
				sendHelpPage(sender, page);
				if(self) sendStatus(target);
			} else if(args[0].equalsIgnoreCase("list")) {
				sendDisguiseList(sender);
			} else if(args[0].equalsIgnoreCase("invis") && playerDisguises.isAvailable() && hasNode(sender, PlayerDisguiseManager.PERM_INVIS)) {
				playerDisguises.toggleInvisible(sender, target);
			} else if(args[0].equalsIgnoreCase("permissions") && hasPermissionAdmin(sender)) {
				sendPermissions(sender);
			} else if(args[0].equalsIgnoreCase("reload")) {
				// A command word, never a disguise name.
				if(!hasPermissionAdmin(sender)) {
					sender.sendMessage(language.DISGUISE_NO_PERMISSION);
				} else {
					config.loadData();
					config.saveData();
					debugMode = new File(getDataFolder(), "debug").isFile() || config.DEBUG_MODE;
					language.loadData();
					language.saveData();
					language.applyPrefix();
					boolean player = playerDisguises.reloadSettings();
					sender.sendMessage(player ? language.RELOADED : language.RELOADED_RESTART);
				}
			} else if(self && args[0].equalsIgnoreCase("reveal")) {
				playerDisguises.revealCommand(sender, args);
			} else if(self && args[0].equalsIgnoreCase("info")) {
				playerDisguises.infoCommand(sender, args);
			} else if(args[0].equalsIgnoreCase("random") && playerDisguises.isAvailable()) {
				if(!playerDisguises.canUseRandom(sender)) {
					sender.sendMessage(language.DISGUISE_NO_PERMISSION);
				} else if(!self || playerDisguises.checkCooldown(target)) {
					Player finalTarget = target;
					playerDisguises.disguiseRandom(sender, target, (success) -> {
						if(success && invis) playerDisguises.setInvisible(sender, finalTarget, true);
						if(success && self) playerDisguises.startCooldown(finalTarget);
						if(success && !self) {
							finalTarget.sendMessage(language.ODISGUISE_NOTIFICATION.replace("%sender%", sender.getName()));
						}
					});
				}
			} else if(args[0].equalsIgnoreCase("player")) {
				if(args.length == 1) {
					sender.sendMessage(language.PLAYER_DISGUISE_ACCOUNT_NAME_MISSING);
				} else {
					disguisePlayerCommand(sender, target, self, args[1], invis);
				}
			} else {
				try {
					EntityType type = EntityType.valueOf(args[0].toUpperCase(Locale.ENGLISH).replace('-', '_'));
					if(config.DISGUISE_TYPE_BLACKLIST.contains(type.name())) {
						sender.sendMessage(language.DISGUISE_TYPE_NOT_SUPPORTED);
					} else if(sender instanceof Player && !hasPermission((Player)sender, type)) {
						sender.sendMessage(language.DISGUISE_NO_PERMISSION);
					} else if(isDisguiseBlocked(target, target.getLocation())) {
						sender.sendMessage(self ? language.DISGUISE_BLOCKED_IN_REGION : language.ODISGUISE_BLOCKED_IN_REGION.replace("%player%", target.getName()));
					} else if(self && !checkDisguiseCooldown(target)) {
						return true;
					} else {
						try {
							Entity entity = disguise(target, type);
							if(entity == null) {
								sender.sendMessage(ChatColor.RED + "Disguise spawn was rejected here (mob spawning may be restricted in this world).");
								return true;
							}
							if(self) lastDisguiseTimestamps.put(target.getUniqueId(), System.currentTimeMillis());
							for(int i = 1; i < args.length; i++) {
								String codeLine = args[i];
								String[] codeFrags = codeLine.split("[()]", -1);
								if(config.STATEMENT_WHITELIST.contains(codeFrags[0])) {
									Statement statement = null;
									Matcher m;
									if(codeFrags[1].length() == 0) {
										statement = new Statement(entity, codeFrags[0], new Object[0]);
									} else if(codeFrags[1].equals("true")) {
										statement = new Statement(entity, codeFrags[0], new Object[] {true});
									} else if(codeFrags[1].equals("false")) {
										statement = new Statement(entity, codeFrags[0], new Object[] {false});
									} else if(INT_VAL.matcher(codeFrags[1]).matches()) {
										statement = new Statement(entity, codeFrags[0], new Object[] {Integer.valueOf(codeFrags[1])});
									} else if(DOUBLE_VAL.matcher(codeFrags[1]).matches()) {
										statement = new Statement(entity, codeFrags[0], new Object[] {Double.valueOf(codeFrags[1])});
									} else if((m = ENUM_VAL.matcher(codeFrags[1])).matches()) {
										Class<?> enumClazz = null;
										if(m.group(1) == null || m.group(1).isEmpty()) {
											for(Method method : entity.getType().getEntityClass().getMethods()) {
												if(method.getName().equals(codeFrags[0]) && method.getParameterTypes().length == 1) {
													enumClazz = method.getParameterTypes()[0];
												}
											}
										} else {
											try {
												enumClazz = Class.forName(m.group(1));
											} catch(ClassNotFoundException e) {
												try {
													enumClazz = Class.forName("org.bukkit.entity." + m.group(1));
												} catch(ClassNotFoundException e2) {
													for(Class<?> innerClazz : entity.getType().getEntityClass().getDeclaredClasses()) {
														if(innerClazz.getSimpleName().equalsIgnoreCase(m.group(1))) {
															enumClazz = innerClazz;
															break;
														}
													}
													if(enumClazz == null) {
														try {
															enumClazz = Class.forName("org.bukkit." + m.group(1));
														} catch(ClassNotFoundException e3) {
														}
													}
												}
											}
										}
										if(enumClazz == (LEGACY_MATERIALS ? MaterialData : BlockData)) {
											try {
												Material material = (Material)Material.class.getDeclaredField(m.group(2)).get(null);
												statement = new Statement(entity, codeFrags[0], new Object[] {
													LEGACY_MATERIALS ? MaterialData_new.newInstance(material) : Material_createBlockData.invoke(material)
												});
											} catch(NoSuchFieldException|IllegalAccessException|InstantiationException|InvocationTargetException e) {
												sender.sendMessage(language.DISGUISE_STATEMENT_ERROR.replace("%statement%", codeLine));
												if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
											}
										} else if(enumClazz == ItemStack.class) {
											try {
												statement = new Statement(entity, codeFrags[0], new Object[] {new ItemStack((Material)Material.class.getDeclaredField(m.group(2)).get(null))});
											} catch(NoSuchFieldException|IllegalAccessException e) {
												sender.sendMessage(language.DISGUISE_STATEMENT_ERROR.replace("%statement%", codeLine));
												if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
											}
										} else if(enumClazz != null) {
											try {
												statement = new Statement(entity, codeFrags[0], new Object[] {enumClazz.getDeclaredField(m.group(2)).get(null)});
											} catch(NoSuchFieldException|IllegalAccessException e) {
												sender.sendMessage(language.DISGUISE_STATEMENT_ERROR.replace("%statement%", codeLine));
												if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
											}
										} else {
											sender.sendMessage(language.DISGUISE_STATEMENT_ERROR.replace("%statement%", codeLine));
										}
									} else if((m = ITEMSTACK_VAL.matcher(codeFrags[1])).matches()) {
										try {
											statement = new Statement(entity, codeFrags[0], new Object[] {new ItemStack((Material)Material.class.getDeclaredField(m.group(1)).get(null), Integer.parseInt(m.group(2)))});
										} catch(NoSuchFieldException|IllegalAccessException e) {
											sender.sendMessage(language.DISGUISE_STATEMENT_ERROR.replace("%statement%", codeLine));
											if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
										}
									} else if(STRING_VAL.matcher(codeFrags[1]).matches()) {
										statement = new Statement(entity, codeFrags[0], new Object[] {codeFrags[1].substring(1, codeFrags[1].length() - 1)});
									}
									if(statement != null) {
										try {
											statement.execute();
											if(statement.getMethodName().equals("setCustomName")) {
												new Statement(entity, "setCustomNameVisible", new Object[] {true}).execute();
											} else if(statement.getMethodName().equals("setCollarColor")) {
												new Statement(entity, "setTamed", new Object[] {true}).execute();
											}
										} catch (Exception e) {
											sender.sendMessage(language.DISGUISE_STATEMENT_ERROR.replace("%statement%", codeLine));
											if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
										}
									} else {
										sender.sendMessage(language.DISGUISE_STATEMENT_UNKNOWN.replace("%statement%", codeLine));
									}
								} else {
									sender.sendMessage(language.DISGUISE_STATEMENT_FORBIDDEN.replace("%statement%", codeFrags[0]));
								}
							}
							sender.sendMessage(self ? language.DISGUISED_SUCCESSFULLY : language.DISGUISED_OTHER.replace("%player%", target.getName()));
							if(!self) {
								target.sendMessage(language.ODISGUISE_NOTIFICATION.replace("%sender%", sender.getName()));
							}
						} catch(EventCancelledException e) {
							sender.sendMessage(
								self ?
								language.DISGUISE_EVENT_CANCELLED :
								language.ODISGUISE_EVENT_CANCELLED.replace("%player%", target.getName())
							);
						}
					}
				} catch(IllegalArgumentException e) {
					// Not a mob: /disguise <name> [rank]
					if(ACCOUNTNAME.matcher(args[0]).matches() && playerDisguises.isAvailable() && canDisguiseAsPlayer(sender)) {
						disguisePlayerCommand(sender, target, self, args[0], invis);
					} else {
						if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
						sender.sendMessage(language.DISGUISE_TYPE_NOT_FOUND.replace("%entityType%", args[0]));
					}
				}
			}
		} else if(command.getName().equalsIgnoreCase("undisguise")) {
			boolean self;
			Player target = null;
			if(args.length == 0) {
				if(!(sender instanceof Player)) {
					sender.sendMessage(language.ODISGUISE_NO_PLAYERNAME_GIVEN);
					return true;
				}
				self = true;
				target = (Player)sender;
			} else {
				if(!hasPermissionOthers(sender)) {
					sender.sendMessage(language.DISGUISE_NO_PERMISSION);
					return true;
				}
				self = false;
				target = Bukkit.getPlayerExact(args[0]);
				if(target == null)
					target = Bukkit.getPlayer(args[0]);
				if(target == null) {
					sender.sendMessage(language.ODISGUISE_PLAYER_NOT_FOUND.replace("%player%", args[0]));
					return true;
				}
				args = Arrays.copyOfRange(args, 1, args.length);
			}
			if(anyDisguise(target)) {
				try {
					undisguiseChecked(target, true);
					sender.sendMessage(self ? language.UNDISGUISED_SUCCESSFULLY : language.UNDISGUISED_OTHER.replace("%player%", target.getName()));
					if(!self) {
						target.sendMessage(language.UNDISGUISE_NOTIFICATION.replace("%sender%", sender.getName()));
					}
				} catch(EventCancelledException e) {
					sender.sendMessage(
						self ?
						language.UNDISGUISE_EVENT_CANCELLED :
						language.UNDISGUISE_OTHER_EVENT_CANCELLED.replace("%player%", target.getName())
					);
				}
			} else {
				sender.sendMessage(
					self ?
					language.UNDISGUISE_NOT_DISGUISED :
					language.UNDISGUISE_OTHER_NOT_DISGUISED.replace("%player%", target.getName())
				);
			}
		}
		return true;
	}

	private void disguisePlayerCommand(CommandSender sender, Player target, boolean self, String name, boolean invis) {
		if(!playerDisguises.isAvailable() || config.DISGUISE_TYPE_BLACKLIST.contains("PLAYER")) {
			sender.sendMessage(language.DISGUISE_TYPE_NOT_SUPPORTED);
			return;
		}
		if(sender instanceof Player && !hasPermission((Player)sender, EntityType.PLAYER)) {
			sender.sendMessage(language.DISGUISE_NO_PERMISSION);
			return;
		}
		if(self && !playerDisguises.checkCooldown(target)) return;
		playerDisguises.disguiseCommand(sender, target, name, self, (success) -> {
			if(success && invis) playerDisguises.setInvisible(sender, target, true);
			if(success && self) playerDisguises.startCooldown(target);
			if(success && !self) {
				target.sendMessage(language.ODISGUISE_NOTIFICATION.replace("%sender%", sender.getName()));
			}
		});
	}

	/** Name of the plugin as players see it in titles: "Disguises", admins see the plugin name and version. */
	private String title(CommandSender sender) {
		return hasPermissionAdmin(sender) ? language.TITLE_ADMIN.replace("%version%", getDescription().getVersion()) : language.TITLE_PLAYERS;
	}

	/**
	 * One line per command: the command, then a short description. Click = typed into chat.
	 * Players see only what they can use; admins see every command of the plugin.
	 */
	private void sendHelpPage(CommandSender sender, int page) {
		boolean admin = hasPermissionAdmin(sender);
		boolean mobs = admin || hasAnyMobPermission(sender);
		boolean players = playerDisguises.isAvailable() && (admin || hasPermission(sender, EntityType.PLAYER));
		boolean random = playerDisguises.isAvailable() && (admin || playerDisguises.canUseRandom(sender));
		boolean invisible = playerDisguises.isAvailable() && hasNode(sender, PlayerDisguiseManager.PERM_INVIS);
		String invisArg = invisible ? " [-i]" : "";
		List<String[]> lines = new ArrayList<>();
		if(mobs) lines.add(new String[] {"/disguise <mob>", language.HELP_CMD_MOB, "/disguise "});
		if(players) lines.add(new String[] {"/disguise <name>" + invisArg, language.HELP_CMD_PLAYER, "/disguise "});
		if(random) lines.add(new String[] {"/disguise random" + invisArg, language.HELP_CMD_RANDOM, "/disguise random"});
		if(mobs || players || random) {
			lines.add(new String[] {"/undisguise", language.HELP_CMD_UNDISGUISE, "/undisguise"});
		}
		lines.add(new String[] {"/disguise list", playerDisguises.canListPlayers(sender) ? language.HELP_CMD_LIST : language.HELP_CMD_LIST_OWN, "/disguise list"});
		if(admin && !config.STATEMENT_WHITELIST.isEmpty()) lines.add(new String[] {"/disguise <mob> <option>", language.HELP_CMD_ALTER, "/disguise "});

		List<String[]> adminLines = new ArrayList<>();
		if(admin || hasPermissionOthers(sender)) {
			adminLines.add(new String[] {"/odisguise <player> <disguise>" + invisArg, language.HELP_CMD_ODISGUISE, "/odisguise "});
			if(invisible) adminLines.add(new String[] {"/odisguise <player> invis", language.HELP_CMD_INVIS_OTHER, "/odisguise "});
			adminLines.add(new String[] {"/undisguise <player>", language.HELP_CMD_UNDISGUISE_OTHER, "/undisguise "});
		}
		if(invisible) {
			adminLines.add(new String[] {"/disguise invis", language.HELP_CMD_INVIS, "/disguise invis"});
		}
		if(admin || hasNode(sender, PlayerDisguiseManager.PERM_REVEAL)) {
			adminLines.add(new String[] {"/disguise reveal", language.HELP_CMD_REVEAL_PLAYERS, "/disguise reveal"});
		}
		if(admin || hasNode(sender, PlayerDisguiseManager.PERM_WHOIS)) {
			adminLines.add(new String[] {"/disguise info <name>", language.HELP_CMD_INFO, "/disguise info "});
		}
		if(admin) {
			adminLines.add(new String[] {"/disguise permissions", language.HELP_CMD_PERMISSIONS, "/disguise permissions"});
			adminLines.add(new String[] {"/disguise reload", language.HELP_CMD_RELOAD, "/disguise reload"});
		}

		sender.sendMessage(language.HELP_TITLE.replace("%title%", title(sender)));
		sendHelpLines(sender, lines);
		if(!adminLines.isEmpty()) {
			sender.sendMessage(language.HELP_ADMIN_TITLE);
			sendHelpLines(sender, adminLines);
		}
	}

	/** Admin texts: English in every language. */
	private static final String[][] PERMISSIONS = {
		{"#Player disguises"},
		{"iDisguise.disguise.PLAYER", "use player disguises"},
		{"iDisguise.random", "/disguise random"},
		{"iDisguise.rank.players", "fake ranks: every rank in player-ranks"},
		{"iDisguise.rank.staff", "fake ranks: every rank in staff-ranks"},
		{"iDisguise.tags", "pick a DeluxeTags tag for a disguise (own tags only)"},
		{"iDisguise.invis", "fake invisibility: /disguise invis, -i (only ESP cheats see you)"},
		{"iDisguise.tags.all", "pick any DeluxeTags tag for a disguise (staff: always all)"},
		{"iDisguise.player.anyname", "names of real players (online or played here before)"},
		{"iDisguise.player.staff", "staff settings, hidden in /disguise list, no cooldown, Player permissions button"},
		{"#Staff tools"},
		{"iDisguise.listplayers", "see who is disguised as whom in /disguise list"},
		{"iDisguise.reveal", "/disguise reveal players: see through player disguises"},
		{"iDisguise.whois", "/disguise info: who is behind a disguise name, a player's disguises"},
		{"iDisguise.see", "right-click a disguise mob to see who is inside"},
		{"iDisguise.admin", "everyone in /disguise list, version, this list, /disguise reload, no cooldown"},
		{"#Mobs and others"},
		{"iDisguise.disguise.<MOB>", "that mob, e.g. iDisguise.disguise.COW"},
		{"iDisguise.disguise.*", "every mob AND player disguises"},
		{"iDisguise.others", "/odisguise and /undisguise <player>"},
		{"iDisguise.sound", "mob sounds (everyone by default)"},
		{"iDisguise.bypass.regions", "disguise in blocked regions"},
		{"iDisguise.*", "everything"}
	};

	private void sendPermissions(CommandSender sender) {
		sender.sendMessage(language.PERMISSIONS_TITLE.replace("%title%", title(sender)));
		for(String[] line : PERMISSIONS) {
			if(line.length == 1) {
				sender.sendMessage(language.PERMISSIONS_SECTION.replace("%section%", line[0].substring(1)));
			} else {
				sender.sendMessage(language.PERMISSIONS_LINE.replace("%node%", line[0]).replace("%description%", line[1]));
			}
		}
		if(!config.USE_PERMISSION_NODES) sender.sendMessage(language.PERMISSIONS_NODES_OFF);
	}

	private void sendHelpLines(CommandSender sender, List<String[]> lines) {
		for(String[] line : lines) {
			String text = language.HELP_LINE.replace("%command%", line[0]).replace("%description%", line[1]);
			if(sender instanceof Player) {
				net.md_5.bungee.api.chat.BaseComponent[] parts = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text);
				net.md_5.bungee.api.chat.ClickEvent click = new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.SUGGEST_COMMAND, line[2]);
				net.md_5.bungee.api.chat.HoverEvent hover = new net.md_5.bungee.api.chat.HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
						net.md_5.bungee.api.chat.TextComponent.fromLegacyText(language.HELP_CLICK));
				for(net.md_5.bungee.api.chat.BaseComponent part : parts) {
					part.setClickEvent(click);
					part.setHoverEvent(hover);
				}
				((Player)sender).spigot().sendMessage(parts);
			} else {
				sender.sendMessage(text);
			}
		}
	}

	/** "Active disguise: NOT DISGUISED / ENDERMITE / Marko (gold)" at the bottom of the help. */
	private void sendStatus(Player player) {
		String state;
		if(disguiseMap.containsKey(player.getUniqueId())) {
			state = language.STATUS_MOB.replace("%type%", disguiseMap.get(player.getUniqueId()).getType().name());
		} else if(playerDisguises.isDisguised(player)) {
			state = language.STATUS_PLAYER.replace("%name%", playerDisguises.get(player.getUniqueId()).getFakeName())
					.replace("%rank%", playerDisguises.shownRankLook(player));
		} else {
			state = language.STATUS_NONE;
		}
		if(playerDisguises.isInvisible(player)) state += language.STATUS_INVISIBLE;
		player.sendMessage(language.STATUS_ACTIVE.replace("%disguise%", state));
	}

	/** True when the sender may disguise as at least one mob. */
	private boolean hasAnyMobPermission(CommandSender sender) {
		for(EntityType type : EntityType.values()) {
			if(type == EntityType.PLAYER || config.DISGUISE_TYPE_BLACKLIST.contains(type.name())) continue;
			if(hasPermission(sender, type)) return true;
		}
		return false;
	}

	private void sendDisguiseList(CommandSender sender) {
		List<EntityType> types = new ArrayList<>();
		for(EntityType type : EntityType.values()) {
			if(type == EntityType.PLAYER) continue;
			if(config.DISGUISE_TYPE_BLACKLIST.contains(type.name())) continue;
			types.add(type);
		}
		types.sort(Comparator.comparing(Enum::name));

		boolean playerType = playerDisguises.isAvailable() && !config.DISGUISE_TYPE_BLACKLIST.contains("PLAYER");
		boolean useColors = sender instanceof Player;

		StringBuilder line = new StringBuilder();
		boolean first = true;
		for(EntityType type : types) {
			appendType(line, type.name(), hasPermission(sender, type), useColors, first);
			first = false;
		}
		if(playerType) {
			boolean has = hasPermission(sender, EntityType.PLAYER);
			if(has && useColors) {
				// The best disguise: last, so it stands out.
				if(!first) line.append(ChatColor.WHITE + ", ");
				line.append(language.LIST_PLAYER_TYPE).append(ChatColor.RESET);
			} else {
				appendType(line, "PLAYER", has, useColors, first);
			}
		}

		if(playerType) {
			for(String entry : playerDisguises.listLines(sender)) {
				playerDisguises.sendUnchanged(sender, entry);
			}
		}
		sender.sendMessage(language.LIST_YOUR_DISGUISES);
		sender.sendMessage(line.toString());
		if(useColors) {
			sender.sendMessage(language.LIST_LEGEND);
		}
	}

	private void appendType(StringBuilder line, String name, boolean has, boolean useColors, boolean first) {
		if(!first) line.append(useColors ? ChatColor.WHITE + ", " : ", ");
		if(!useColors) {
			line.append(name);
			return;
		}
		if(has) {
			line.append(ChatColor.GREEN).append(name).append(ChatColor.RESET);
		} else {
			line.append(ChatColor.RED).append(name).append(ChatColor.RESET);
		}
	}

	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> completions = new ArrayList<String>();

		if(StringUtil.equalsIgnoreCase(command.getName(), "disguise", "odisguise")) {
			boolean self = command.getName().equalsIgnoreCase("disguise");
			if(self && !(sender instanceof Player)) {
				return completions;
			}
			if(!self && !hasPermissionOthers(sender)) {
				return completions;
			}

			if(!self && args.length < 2) {
				completions.add("?");
				for(Player player : Bukkit.getOnlinePlayers()) {
					completions.add(player.getName());
				}
				if(args.length > 0) {
					for(int i = 0; i < completions.size(); i++) {
						if(!StringUtil.startsWithIgnoreCase(completions.get(i), args[0])) {
							completions.remove(i);
							i--;
						}
					}
				}
			} else if(args.length < (self ? 2 : 3)) {
				completions.add("?");
				completions.add("list");
				if(playerDisguises.canUseRandom(sender)) completions.add("random");
				if(playerDisguises.isAvailable() && hasNode(sender, PlayerDisguiseManager.PERM_INVIS)) {
					completions.add("invis");
				}
				if(self && hasNode(sender, PlayerDisguiseManager.PERM_REVEAL)) completions.add("reveal");
				if(self && hasNode(sender, PlayerDisguiseManager.PERM_WHOIS)) {
					completions.add("info");
				}
				for(EntityType type : EntityType.values()) {
					if(type.equals(EntityType.PLAYER) && !playerDisguises.isAvailable()) continue;
					if(!config.DISGUISE_TYPE_BLACKLIST.contains(type.name()) && (!(sender instanceof Player) || hasPermission((Player)sender, type))) {
						completions.add(type.name());
					}
				}
				if(args.length > (self ? 0 : 1)) {
					for(int i = 0; i < completions.size(); i++) {
						if(!StringUtil.startsWithIgnoreCase(completions.get(i), args[self ? 0 : 1].replace('-', '_'))) {
							completions.remove(i);
							i--;
						}
					}
				}
			} else {
				try {
					EntityType type = EntityType.valueOf(args[self ? 0 : 1].toUpperCase(Locale.ENGLISH).replace('-', '_'));
					if((!(sender instanceof Player) || hasPermission((Player)sender, type)) && tabCompletions.containsKey(type)) {
						if(!type.equals(EntityType.PLAYER) || args.length < (self ? 3 : 4)) {
							completions.addAll(tabCompletions.get(type));
						} else if(args.length == (self ? 3 : 4)) {
							completions.addAll(playerDisguises.usableRanks(sender));
						}
					}
					for(int i = 0; i < completions.size(); i++) {
						if(!completions.get(i).startsWith(args[args.length - 1])) {
							completions.remove(i);
							i--;
						}
					}
				} catch(IllegalArgumentException e) {
					// /disguise <name> [-i]: rank and tag are picked in the menu
					if(args.length == (self ? 2 : 3) && playerDisguises.isAvailable() && hasNode(sender, PlayerDisguiseManager.PERM_INVIS)
							&& "-i".startsWith(args[args.length - 1].toLowerCase(Locale.ENGLISH))) {
						completions.add("-i");
					}
				}
			}
		} else if(command.getName().equalsIgnoreCase("undisguise")) {
			if(!hasPermissionOthers(sender)) {
				return completions;
			}

			for(Player player : Bukkit.getOnlinePlayers()) {
				completions.add(player.getName());
			}
			if(args.length > 0) {
				for(int i = 0; i < completions.size(); i++) {
					if(!StringUtil.startsWithIgnoreCase(completions.get(i), args[0])) {
						completions.remove(i);
						i--;
					}
				}
			}
		}
		return completions;
	}

	private boolean hasPermission(CommandSender sender, EntityType type) {
		if(!(sender instanceof Player)) {
			return true;
		} else if(!config.USE_PERMISSION_NODES) {
			return ((Player)sender).isOp();
		} else {
			return ((Player)sender).hasPermission("iDisguise.*") ||
				   ((Player)sender).hasPermission("iDisguise.disguise.*") ||
				   ((Player)sender).hasPermission("iDisguise.disguise." + type.name());
		}
	}

	private boolean hasPermissionAdmin(CommandSender sender) {
		if(!(sender instanceof Player)) {
			return true;
		} else if(!config.USE_PERMISSION_NODES) {
			return ((Player)sender).isOp();
		} else {
			return ((Player)sender).hasPermission("iDisguise.*") ||
				   ((Player)sender).hasPermission("iDisguise.admin");
		}
	}

	private boolean hasPermissionOthers(CommandSender sender) {
		if(!(sender instanceof Player)) {
			return true;
		} else if(!config.USE_PERMISSION_NODES) {
			return ((Player)sender).isOp();
		} else {
			return ((Player)sender).hasPermission("iDisguise.*") ||
			       ((Player)sender).hasPermission("iDisguise.others");
		}
	}

	private boolean hasPermissionRegionBypass(Player player) {
		if(!config.USE_PERMISSION_NODES) {
			return player.isOp();
		} else {
			return player.hasPermission("iDisguise.*") ||
			       player.hasPermission("iDisguise.bypass.regions");
		}
	}

	private boolean hasPermissionSee(Player player) {
		if(!config.USE_PERMISSION_NODES) {
			return player.isOp();
		} else {
			return player.hasPermission("iDisguise.*") ||
			       player.hasPermission("iDisguise.see");
		}
	}

	// True when the location is inside a region from disguise.blocked-regions and the player has
	// no bypass. Checks the target, so /odisguise on a player in a blocked region is refused too.
	private boolean isDisguiseBlocked(Player player, Location location) {
		if(worldGuardRegions == null || config.BLOCKED_REGIONS.isEmpty()) return false;
		if(hasPermissionRegionBypass(player)) return false;
		return worldGuardRegions.isInAny(location, config.BLOCKED_REGIONS);
	}

	// Undisguise on the next tick, once the move or teleport into the blocked region has settled.
	// Doing it inside the event would let a player-skin undisguise teleport the player (its viewself
	// refresh) in the middle of their own move.
	private void scheduleRegionUndisguise(Player player) {
		Bukkit.getScheduler().runTask(this, () -> {
			if(!player.isOnline() || !anyDisguise(player)) return;
			if(!isDisguiseBlocked(player, player.getLocation())) return;
			undisguise0(player);
			player.sendMessage(language.UNDISGUISED_IN_BLOCKED_REGION);
		});
	}

	// Returns true when the player is allowed to disguise; false when the cooldown is active
	// (a message is sent to the player in that case). Bypassed for players holding iDisguise.*
	// so admins aren't rate-limited.
	private boolean checkDisguiseCooldown(Player player) {
		if(config.DISGUISE_COOLDOWN_SECONDS <= 0) return true;
		if(player.hasPermission("iDisguise.*")) return true;
		Long last = lastDisguiseTimestamps.get(player.getUniqueId());
		if(last == null) return true;
		long cooldownMs = config.DISGUISE_COOLDOWN_SECONDS * 1000L;
		long elapsed = System.currentTimeMillis() - last;
		if(elapsed >= cooldownMs) return true;
		long remaining = (cooldownMs - elapsed + 999) / 1000L;
		player.sendMessage(language.DISGUISE_ON_COOLDOWN.replace("%seconds%", String.valueOf(remaining)));
		return false;
	}

	/** Developer API (CombatLogX ...): player disguises only when visible-to-other-plugins is on. */
	public synchronized EntityType getDisguise(Player player) {
		EntityType type = currentDisguise(player);
		if(type == EntityType.PLAYER && (playerDisguises == null || !playerDisguises.visibleToOtherPlugins())) return null;
		return type;
	}

	/** What the player is disguised as right now (mob type, PLAYER, or null). */
	private synchronized EntityType currentDisguise(Player player) {
		if(disguiseMap.containsKey(player.getUniqueId())) {
			return disguiseMap.get(player.getUniqueId()).getType();
		}
		if(playerDisguises != null && playerDisguises.isDisguised(player)) {
			return EntityType.PLAYER;
		}
		return null;
	}
	
	/** Developer API (CombatLogX ...): player disguises only when visible-to-other-plugins is on. */
	public synchronized boolean isDisguised(Player player) {
		return getDisguise(player) != null;
	}

	private synchronized boolean anyDisguise(Player player) {
		return disguiseMap.containsKey(player.getUniqueId()) || (playerDisguises != null && playerDisguises.isDisguised(player));
	}

	/** Mob disguises only: the mob-specific rules (pearls, blocks, regions, death) skip player disguises. */
	/** For player disguises: mob disguise on right now. */
	public boolean hasMobDisguise(Player player) {
		return isMobDisguised(player);
	}

	private synchronized boolean isMobDisguised(Player player) {
		return disguiseMap.containsKey(player.getUniqueId());
	}

	/** Ends a mob disguise, if any (used before a player disguise starts). */
	public synchronized void removeMobDisguise(Player player) {
		if(disguiseMap.containsKey(player.getUniqueId())) undisguise0(player);
	}

	/** Permission check that follows commands.use-permission-nodes (off = operators only). */
	public boolean hasNode(CommandSender sender, String node) {
		if(!(sender instanceof Player)) return true;
		if(!config.USE_PERMISSION_NODES) return sender.isOp();
		return sender.hasPermission("iDisguise.*") || sender.hasPermission(node);
	}

	/** iDisguise.disguise.PLAYER (or operators without permission nodes). */
	public boolean canDisguiseAsPlayer(CommandSender sender) {
		return hasPermission(sender, EntityType.PLAYER);
	}

	public boolean isPlayerNameBlacklisted(String name) {
		return config.PLAYER_DISGUISE_BLACKLIST.contains(name.toLowerCase(Locale.ENGLISH));
	}

	public Entity disguise(Player player, EntityType type, boolean fireEvent) throws EventCancelledException {
		if(config.DISGUISE_TYPE_BLACKLIST.contains(type.name())) throw new UnsupportedOperationException("Currently not supported!");
		if(type == EntityType.PLAYER) throw new UnsupportedOperationException();
		
		if(fireEvent) {
			PlayerDisguiseEvent event = new PlayerDisguiseEvent(player, type);
			Bukkit.getPluginManager().callEvent(event);
			if(event.isCancelled()) {
				throw new EventCancelledException();
			}
		}

		return disguise0(player, type);
	}

	private synchronized Entity disguise0(Player player, EntityType type) {
		if(config.DISGUISE_TYPE_BLACKLIST.contains(type.name())) throw new UnsupportedOperationException("Currently not supported!");
		if(type == EntityType.PLAYER) throw new UnsupportedOperationException();
		
		if(anyDisguise(player)) undisguise0(player);

		Entity entity;
		spawningDisguiseType = type;
		try {
			if(type == EntityType.DROPPED_ITEM) {
				entity = player.getWorld().dropItem(player.getLocation(), new ItemStack(Material.STONE));
			} else {
				entity = player.getWorld().spawnEntity(player.getLocation(), type);
			}
		} finally {
			spawningDisguiseType = null;
		}
		// Bail if the mob still didn't make it into the world. Cancelled spawn events are overridden
		// by handleDisguiseSpawn, but a plugin can still remove the entity outright. Otherwise we'd
		// leave the player half-disguised: invisible + tracker intercepted but with no visible mob.
		if(entity == null || entity.isDead() || !entity.isValid()) {
			if(entity != null) entity.remove();
			if(debugMode) getLogger().info("Disguise spawn for " + player.getName() + " was denied in world '"
					+ player.getWorld().getName() + "' (likely a mob-spawning restriction). Aborting disguise.");
			return null;
		}
		if(entity instanceof LivingEntity) {
			LivingEntity living = (LivingEntity)entity;
			if(!LEGACY_DISABLE_AI) {
				living.setAI(false);
			} else {
				try {
					Object nmsEntity = CraftEntity_getHandle.invoke(entity);
					if(EntityInsentient.isInstance(nmsEntity)) {
						EntityInsentient_setNoAI.invoke(nmsEntity, true);
					}
				} catch(Exception e) {
					if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
				}
			}
			// Prevent natural despawn (hostile mobs despawn when no player is within ~32 blocks
			// for several ticks). Without this the disguise mob can vanish during fast movement
			// or when observers go in/out of render distance, leaving the disguised player
			// invisible to others until the next move re-syncs.
			try {
				living.setRemoveWhenFarAway(false);
			} catch(Throwable t) {
				if(debugMode) getLogger().log(Level.WARNING, "setRemoveWhenFarAway failed", t);
			}
			living.setCanPickupItems(false);
		} else if(entity instanceof Item) {
			((Item)entity).setPickupDelay(Integer.MAX_VALUE);
		} else if(entity instanceof TNTPrimed) {
			((TNTPrimed)entity).setFuseTicks(Integer.MAX_VALUE);
		}
		entity.setMetadata("iDisguise", new FixedMetadataValue(this, player.getUniqueId()));
		// FactionsKore-Stacker's MobStackingFeature looks for this exact metadata key in its
		// EntityDeathEvent listener and skips its custom-drop table when present. Without it,
		// killing a disguise mob (e.g. when damage redirect doesn't fully neutralize the hit)
		// rewards the attacker with the configured loot for that mob type -- free items.
		entity.setMetadata("nodrops", new FixedMetadataValue(this, true));
		if(LEGACY_INJECTION) {
			try {
				LegacyInjector_inject.invoke(null, entity, player);
			} catch(Exception e) {
				if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
			}
		}
		player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 1, true, false));
		if(type.name().equals("SHULKER")) {
			Location to = player.getLocation();
			entity.teleport(new Location(to.getWorld(), to.getBlockX()+0.5, to.getBlockY()+0.0, to.getBlockZ()+0.5));
		} else {
			entity.teleport(player.getLocation());
		}
		disguiseMap.put(player.getUniqueId(), entity);
		if(playerDisguises != null) playerDisguises.logMob(player, type, true);
		if(config.KEEP_TAB_LIST_WHEN_DISGUISED && LegacyInjector_injectAll != null) {
			// Hide the player entity from observers via NMS tracker injection. Skips Bukkit's
			// hidePlayer entirely so canSee, tab list, /tpa, /msg, online status etc. all stay
			// intact -- only the on-screen entity goes away.
			try {
				LegacyInjector_injectAll.invoke(null, player);
				LegacyInjector_toggleInterceptAll.invoke(null, player, true);
			} catch(Exception e) {
				if(debugMode) getLogger().log(Level.SEVERE, "Failed to inject player tracker for disguise of " + player.getName(), e);
			}
		} else {
			for(Player observer : Bukkit.getOnlinePlayers()) {
				if(observer != player) {
					observer.hidePlayer(player);
					if(config.KEEP_TAB_LIST_WHEN_DISGUISED) {
						resendTabAdd(observer, player);
					}
				}
			}
		}

		Entity finalEntity = entity;
		Bukkit.getScheduler().runTaskLater(this, () -> {
			// The disguise may have been torn down in the 5s window before this fires (undisguise,
			// quit, cross-world teleport, orphan cleanup). Bail before touching the tracker so we
			// don't spam IllegalStateException -- the cleanup paths already removed the injection.
			if(!player.isOnline() || finalEntity.isDead() || !finalEntity.isValid()) return;
			Entity current = disguiseMap.get(player.getUniqueId());
			if(current == null || !current.equals(finalEntity)) return;
			if(!LEGACY_INJECTION) {
				player.hideEntity(this, finalEntity);
			} else if(player.getWorld().equals(finalEntity.getWorld())) {
				try {
					LegacyInjector_toggleIntercept.invoke(null, finalEntity, player, true);
				} catch(InvocationTargetException ite) {
					// Tracker was uninjected between our guards above and this call (very narrow
					// race) -- nothing to do, the disguise is already gone.
					if(!(ite.getCause() instanceof IllegalStateException) && debugMode) {
						getLogger().log(Level.SEVERE, "Unexpected failure!", ite);
					}
				} catch(Exception e) {
					if(debugMode) getLogger().log(Level.SEVERE, "Unexpected failure!", e);
				}
			}
		}, 100L);

		return entity;
	}

	public void disguiseAsPlayer(Player player, String targetSkin, boolean fireEvent, @Nullable Consumer<Boolean> callback) throws EventCancelledException {
		if(playerDisguises == null || !playerDisguises.isAvailable() || config.DISGUISE_TYPE_BLACKLIST.contains("PLAYER")) throw new UnsupportedOperationException("Currently not supported!");
		if(!ACCOUNTNAME.matcher(targetSkin).matches()) throw new IllegalArgumentException("This account name is invalid.");
		// The PlayerDisguiseAsPlayerEvent is fired by the player disguise itself, right before it starts.
		playerDisguises.disguise(null, player, targetSkin, null, callback);
	}
	
	/** Developer API: only touches disguises other plugins can see (see isDisguised). */
	public EntityType undisguise(Player player, boolean fireEvent) throws EventCancelledException {
		if(!isDisguised(player)) return null;
		return undisguiseChecked(player, fireEvent);
	}

	private EntityType undisguiseChecked(Player player, boolean fireEvent) throws EventCancelledException {
		if(!anyDisguise(player)) return null;

		if(fireEvent) {
			PlayerUndisguiseEvent event = new PlayerUndisguiseEvent(player);
			Bukkit.getPluginManager().callEvent(event);
			if(event.isCancelled()) {
				throw new EventCancelledException();
			}
		}

		return undisguise0(player);
	}

	private synchronized EntityType undisguise0(Player player) {
		if(!anyDisguise(player)) return null;
		
		if(currentDisguise(player) == EntityType.PLAYER) {
			playerDisguises.undisguise(player);
			return EntityType.PLAYER;
		} else {
			Entity entity = disguiseMap.remove(player.getUniqueId());
			if(playerDisguises != null) playerDisguises.logMob(player, entity.getType(), false);
			entity.remove();
			// Clear the invisibility we applied at disguise time. Otherwise an undisguise that
			// happens during the 5s viewself grace leaves the player invisible.
			player.removePotionEffect(PotionEffectType.INVISIBILITY);
			if(LegacyInjector_toggleInterceptAll != null) {
				// Stop hiding the player from observers (no-op if no injection was active).
				try {
					LegacyInjector_toggleInterceptAll.invoke(null, player, false);
				} catch(Exception e) {
					// IllegalStateException from the impl when no injection is in place -- expected
					// for the !KEEP_TAB_LIST disguise path; nothing to do.
				}
			}
			// Force a destroy+spawn cycle per observer so the player's entity reappears immediately
			// instead of waiting up to ~3s for the next entity-tracker keepalive. Plain showPlayer
			// is insufficient in LEGACY_INJECTION mode (KEEP_TAB_LIST_WHEN_DISGUISED): observers
			// were never added to hiddenPlayers at disguise time, so showPlayer early-returns and
			// the tracker entry's updatePlayer is also a no-op (observer is still in
			// trackedPlayers). Calling hidePlayer first evicts the observer from trackedPlayers
			// and sends EntityDestroy; the following showPlayer then sends ADD_PLAYER and a fresh
			// NamedEntitySpawn. Both packets ship in the same tick, so the client renders one
			// continuous transition with no flicker. In non-legacy mode hidePlayer is a no-op
			// (observer already in hiddenPlayers from disguise time) and showPlayer behaves as
			// before -- this change is a net no-op there.
			//
			// Cross-world observers skip the hidePlayer call: they can't see the player, so the
			// destroy packet is wasted, and clearing trackedPlayers does nothing useful. We still
			// call showPlayer for them because in non-legacy mode the disguise loop called
			// hidePlayer for every online observer including cross-world ones -- if we don't clear
			// their hiddenPlayers flag here, CraftBukkit's tracker refuses to spawn the player
			// when they next teleport into range (canSee() consults hiddenPlayers regardless of
			// world), leaving the player invisibly stuck to that observer indefinitely.
			World playerWorld = player.getWorld();
			for(Player observer : Bukkit.getOnlinePlayers()) {
				if(observer != player) {
					if(observer.getWorld().equals(playerWorld)) {
						observer.hidePlayer(player);
					}
					observer.showPlayer(player);
				}
			}
			return entity.getType();
		}
	}
	
	@EventHandler
	public void handlePlayerJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		
		boolean injectorActive = config.KEEP_TAB_LIST_WHEN_DISGUISED && LegacyInjector_injectAll != null;
		for(Entry<UUID, Entity> entry : disguiseMap.entrySet()) {
			Player disguisedPlayer = Bukkit.getPlayer(entry.getKey());
			if(disguisedPlayer == null) continue;
			if(injectorActive) {
				// The tracker injector already suppresses entity updates to every observer
				// including new joiners, so there's nothing to do here.
				continue;
			}
			player.hidePlayer(disguisedPlayer);
			if(config.KEEP_TAB_LIST_WHEN_DISGUISED) {
				resendTabAdd(player, disguisedPlayer);
			}
		}

		if(config.UPDATE_CHECK && hasPermissionAdmin(player)) {
			Bukkit.getScheduler().runTaskLaterAsynchronously(this, new UpdateCheck(this, player, config.UPDATE_DOWNLOAD), 20L);
		}

		// Re-apply a disguise that was active when the player last quit (if persist-on-quit was
		// enabled at the time). Delay a few ticks so the join finishes (other login plugins,
		// teleport-on-join etc.) before we spawn the disguise mob at the wrong location.
		UUID id = player.getUniqueId();
		EntityType pendingMobType = pendingRejoinMobDisguises.remove(id);
		if(pendingMobType != null) {
			Bukkit.getScheduler().runTaskLater(this, () -> {
				if(!player.isOnline() || anyDisguise(player)) return;
				if(isDisguiseBlocked(player, player.getLocation())) {
					player.sendMessage(language.UNDISGUISED_IN_BLOCKED_REGION);
					return;
				}
				try {
					disguise0(player, pendingMobType);
					if(debugMode) getLogger().info("[persist-on-quit] re-applied " + pendingMobType.name()
							+ " disguise for " + player.getName() + " on rejoin");
				} catch(Throwable t) {
					if(debugMode) getLogger().log(Level.WARNING,
							"Failed to re-apply pending disguise for " + player.getName(), t);
				}
			}, 10L);
		}
	}
	
	@EventHandler
	public void handlePlayerQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();
		UUID id = player.getUniqueId();
		pendingCrossWorldDisguises.remove(id);
		mobSoundCooldowns.remove(id);
		lastDisguiseTimestamps.remove(id);
		lastPearlBlockNotice.remove(id);
		lastBlockPlaceNotice.remove(id);
		lastBlockBreakNotice.remove(id);
		// Player disguises stay on after quitting (they are saved); only mob disguises end here.
		if(!isMobDisguised(player)) return;

		if(config.PERSIST_DISGUISE_ON_QUIT) {
			// Capture the disguise identity BEFORE undisguising, because undisguise0 wipes the maps.
			// We tear down the mob/invisibility now (offline players don't need either) and replay
			// the disguise on rejoin in handlePlayerJoin.
			Entity mob = disguiseMap.get(id);
			if(mob != null) {
				pendingRejoinMobDisguises.put(id, mob.getType());
			}
			if(debugMode) getLogger().info("[persist-on-quit] storing pending rejoin disguise for "
					+ player.getName() + " (mob=" + (mob == null ? "null" : mob.getType().name()) + ")");
		}

		undisguise0(player);
	}

	@EventHandler
	public void handlePlayerDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		// A player disguise stays on through death.
		if(isMobDisguised(player)) {
			undisguise0(player);
		}
	}

	private void checkDisguiseHealth(Player player) {
		Entity entity = disguiseMap.get(player.getUniqueId());
		if(entity == null) return;
		// If the disguise mob got removed by something we don't control (chunk eviction, despawn,
		// other plugin's entity cleanup), the player is left invisible without a visible mob.
		// Tear down cleanly so they go back to a normal visible state.
		if(entity.isDead() || !entity.isValid()) {
			if(debugMode) getLogger().info("Disguise mob for " + player.getName()
					+ " went away unexpectedly (dead or invalid). Cleaning up.");
			undisguise0(player);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void handlePlayerMove(PlayerMoveEvent event) {
		checkDisguiseHealth(event.getPlayer());
		Player player = event.getPlayer();
		Location moveFrom = event.getFrom();
		Location moveTo = event.getTo();
		boolean changedBlock = moveFrom.getBlockX() != moveTo.getBlockX() || moveFrom.getBlockY() != moveTo.getBlockY()
				|| moveFrom.getBlockZ() != moveTo.getBlockZ() || !moveFrom.getWorld().equals(moveTo.getWorld());
		if(!event.isCancelled() && changedBlock && isMobDisguised(player) && isDisguiseBlocked(player, moveTo)) {
			scheduleRegionUndisguise(player);
		}
		if(!event.isCancelled() && disguiseMap.containsKey(player.getUniqueId())) {
			Entity entity = disguiseMap.get(player.getUniqueId());
			World worldFrom = entity.getWorld();
			if(LEGACY_INJECTION && !event.getTo().getWorld().equals(worldFrom)) {
				// PlayerMoveEvent fires before the teleport completes; spawning a new mob now
				// would orphan it in the source world. Hand off to PlayerChangedWorldEvent which
				// fires synchronously after the world transition is fully settled.
				EntityType originalType = entity.getType();
				undisguise0(player);
				pendingCrossWorldDisguises.put(player.getUniqueId(), originalType);
			} else {
				if(entity.getType().name().equals("SHULKER")) {
					Location to = event.getTo();
					entity.teleport(new Location(to.getWorld(), to.getBlockX()+0.5, to.getBlockY()+0.0, to.getBlockZ()+0.5));
				} else {
					entity.teleport(event.getTo());
				}
				entity.setVelocity(player.getVelocity());
			}
		}
	}

	// PlayerTeleportEvent has its own handler list, so handlePlayerMove never sees teleports.
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void handlePlayerTeleportRegion(PlayerTeleportEvent event) {
		Player player = event.getPlayer();
		if(isMobDisguised(player) && isDisguiseBlocked(player, event.getTo())) {
			scheduleRegionUndisguise(player);
		}
	}

	@EventHandler
	public void handlePlayerChangedWorld(PlayerChangedWorldEvent event) {
		Player player = event.getPlayer();
		EntityType pendingType = pendingCrossWorldDisguises.remove(player.getUniqueId());

		// Fallback: if handlePlayerMove didn't run (some teleport mechanisms skip PlayerMoveEvent
		// but always fire PlayerChangedWorldEvent), detect the mismatch ourselves -- the disguise
		// mob is in the old world but the player is now in the new one.
		if(pendingType == null && anyDisguise(player)) {
			Entity orphan = disguiseMap.get(player.getUniqueId());
			if(orphan != null && !player.getWorld().equals(orphan.getWorld())) {
				pendingType = orphan.getType();
				if(debugMode) getLogger().info("Detected cross-world disguise for " + player.getName()
						+ " (mob in '" + orphan.getWorld().getName() + "', player in '"
						+ player.getWorld().getName() + "'). Re-syncing.");
				undisguise0(player);
			}
		}

		if(pendingType == null) return;
		if(!player.isOnline() || anyDisguise(player)) return;
		if(isDisguiseBlocked(player, player.getLocation())) {
			player.sendMessage(language.UNDISGUISED_IN_BLOCKED_REGION);
			return;
		}
		try {
			Entity result = disguise0(player, pendingType);
			if(result == null && debugMode) {
				getLogger().info("Cross-world re-disguise for " + player.getName() + " in world '"
						+ player.getWorld().getName() + "' was rejected; leaving them undisguised.");
			}
		} catch(Exception e) {
			if(debugMode) getLogger().log(Level.SEVERE, "Failed to re-disguise " + player.getName() + " after cross-world teleport", e);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void handleEntityDamageLowest(EntityDamageEvent event) {
		if(event.getEntity().hasMetadata("iDisguise")) {
			// Fire causes too: the player stands in the same fire/lava and takes that damage
			// themselves. Relaying the mob's copy arrives as generic damage, which Fire Resistance
			// doesn't block. Burning from attacks still reaches the player through
			// handleEntityCombustMonitor, which sets the player on fire directly.
			if(StringUtil.equals(event.getCause().name(), "DROWNING", "DRYOUT", "FLY_INTO_WALL", "SUFFOCATION",
					"FIRE", "FIRE_TICK", "LAVA", "HOT_FLOOR")) {
				event.setCancelled(true);
			}
		}
		if(event instanceof EntityDamageByEntityEvent) {
			EntityDamageByEntityEvent event2 = (EntityDamageByEntityEvent)event;
			if(event2.getDamager().hasMetadata("iDisguise")) {
				event.setCancelled(true);
				return;
			}
			if(event.getEntity().hasMetadata("iDisguise") && !event.isCancelled()) {
				UUID disguiseOwnerId = (UUID)event.getEntity().getMetadata("iDisguise").get(0).value();
				Entity damager = event2.getDamager();
				Player attackerPlayer = null;
				if(damager instanceof Player) {
					attackerPlayer = (Player)damager;
				} else if(damager instanceof Projectile) {
					ProjectileSource shooter = ((Projectile)damager).getShooter();
					if(shooter instanceof Player) {
						attackerPlayer = (Player)shooter;
					}
				}
				if(attackerPlayer != null) {
					// Block self-damage when a player hits their own disguise mob (the mob lags
					// behind during movement and is reachable). Without this, the MONITOR handler
					// forwards the damage back to the player.
					if(attackerPlayer.getUniqueId().equals(disguiseOwnerId)) {
						event.setCancelled(true);
						return;
					}
					// Re-dispatch the hit as attacker -> real player so PvP-protection plugins
					// (Factions friendly-fire, towny, teams, region flags, ...) can inspect the
					// matchup as if the disguise mob weren't sitting between them. They cancel
					// and print their own "can't hit ally" message during this synchronous call;
					// we mirror the cancellation back so the original mob hit shows no knockback
					// or damage tick. The MONITOR handler then bails (event.isCancelled), leaving
					// the disguised player untouched. Re-entrance is impossible: the synthetic
					// event's victim is the bare Player (no "iDisguise" metadata), so neither
					// this handler nor the MONITOR redirect re-fires.
					Player disguisedPlayer = Bukkit.getPlayer(disguiseOwnerId);
					if(disguisedPlayer != null && disguisedPlayer.isOnline()) {
						EntityDamageByEntityEvent forward = new EntityDamageByEntityEvent(
								damager, disguisedPlayer, event.getCause(), event.getDamage());
						Bukkit.getPluginManager().callEvent(forward);
						if(forward.isCancelled()) {
							event.setCancelled(true);
							return;
						}
					}
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void handleEntityDeath(EntityDeathEvent event) {
		// Belt-and-suspenders against any mob-loot / stacker plugin that scans EntityDeathEvent
		// for the disguise mob's type. The "nodrops" metadata set at spawn already covers
		// FactionsKore-Stacker, but other plugins use their own conventions; clearing the vanilla
		// drop list + xp here neutralizes the generic case. MONITOR-priority plugins that build
		// their own drop containers (like FactionsKore-Stacker) won't honor this -- they need the
		// metadata bypass -- but anything reading e.getDrops()/setDroppedExp() will be empty.
		if(event.getEntity().hasMetadata("iDisguise")) {
			event.getDrops().clear();
			event.setDroppedExp(0);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void handleDisguiseSpawn(EntitySpawnEvent event) {
		// Spawn, the End and other protected areas deny mob spawning (WorldGuard mob-spawning flag,
		// Factions zone rules, ...), and that also cancelled the disguise mob, so nobody could
		// disguise there. The disguise mob isn't a real mob spawn, so let it through. MONITOR
		// because the cancelling plugin can sit at HIGHEST and run after any earlier priority.
		// On 1.8 CreatureSpawnEvent and ItemSpawnEvent share this handler list, so this covers both.
		if(spawningDisguiseType != null && event.isCancelled() && event.getEntityType() == spawningDisguiseType) {
			event.setCancelled(false);
			if(debugMode) getLogger().info("Let disguise spawn (" + spawningDisguiseType.name() + ") through at "
					+ event.getLocation() + " after another plugin cancelled it");
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void handleDisguiseEggLay(ItemSpawnEvent event) {
		// A chicken disguise mob keeps laying eggs (NoAI doesn't stop it) right at the player's feet,
		// so disguised players farm eggs and throw them to hatch chickens. EntityDropItemEvent would
		// name the chicken but doesn't exist on 1.8, so match on position instead: the egg spawns at
		// the chicken's exact location. LOWEST so item-stacker plugins see the spawn already cancelled.
		Item item = event.getEntity();
		String material = item.getItemStack().getType().name();
		if(!material.equals("EGG") && !material.equals("BROWN_EGG") && !material.equals("BLUE_EGG")) return;
		Location location = item.getLocation();
		for(Entity entity : disguiseMap.values()) {
			if(entity.getType() == EntityType.CHICKEN && entity.getWorld().equals(location.getWorld())
					&& entity.getLocation().distanceSquared(location) < 0.01) {
				event.setCancelled(true);
				return;
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void handleEntityDamageMonitor(EntityDamageEvent event) {
		Entity entity = event.getEntity();
		if(!entity.hasMetadata("iDisguise") || event.isCancelled()) return;

		UUID playerId = (UUID)entity.getMetadata("iDisguise").get(0).value();
		Player player = Bukkit.getPlayer(playerId);
		Entity registeredDisguise = disguiseMap.get(playerId);

		// Drop orphan disguise mobs (player offline / dead / re-disguised as something else / left
		// behind in another world after a cross-world teleport). Without this, a leftover mob
		// keeps damaging the player long after they're gone from the area.
		if(player == null || !player.isOnline() || registeredDisguise == null
				|| !registeredDisguise.equals(entity)
				|| !player.getWorld().equals(entity.getWorld())) {
			entity.remove();
			if(debugMode) getLogger().info("Removed orphan disguise entity for UUID " + playerId);
			return;
		}

		player.damage(event.getDamage());
		if(debugMode) getLogger().info("Dealt damage (" + event.getCause().name() + "," + event.getDamage() + ") to " + player.getName());
		event.setDamage(Double.MIN_VALUE);
	}

	// Resolved once at first call. Stays null on 1.8 (no off-hand → method doesn't exist).
	// Reflection avoids a direct EquipmentSlot reference, which would NoClassDefFoundError on 1.8.
	private static java.lang.reflect.Method PlayerInteractEvent_getHand;
	private static boolean PlayerInteractEvent_getHand_resolved;

	// Block ender-pearl throws while disguised. The disguise mob sits at the player's location and
	// otherwise either eats the pearl (looks like the pearl returns to hand) or, with any
	// projectile-displacement workaround, would risk a wall-glitch teleport.
	// Secondary catch at the projectile-spawn point. Some server forks (WineSpigot at least) don't
	// deliver PlayerInteractEvent to plugins for pearl right-clicks, so we listen at
	// ProjectileLaunchEvent too -- this fires whenever the pearl entity actually spawns. The cost
	// is that the pearl item was already consumed by the time we get here, so we refund one on
	// the next tick.
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void handleEnderpearlLaunchBlock(ProjectileLaunchEvent event) {
		if(!config.DISABLE_ENDERPEARLS_WHILE_DISGUISED) return;
		if(event.getEntityType() != EntityType.ENDER_PEARL) return;
		ProjectileSource source = event.getEntity().getShooter();
		if(!(source instanceof Player)) return;
		Player player = (Player)source;
		if(!isMobDisguised(player)) return;
		event.setCancelled(true);
		notifyPearlBlocked(player);
		// The pearl item was already removed from the inventory before ProjectileLaunchEvent fired.
		// Hand it back on the next tick so it doesn't end up consumed for no reason.
		Bukkit.getScheduler().runTask(this, () -> {
			if(!player.isOnline()) return;
			Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
			for(ItemStack drop : leftover.values()) {
				player.getWorld().dropItemNaturally(player.getLocation(), drop);
			}
		});
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void handleEnderpearlBlock(PlayerInteractEvent event) {
		if(!config.DISABLE_ENDERPEARLS_WHILE_DISGUISED) return;
		Action action = event.getAction();
		if(action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
		if(isOffHandPass(event)) return;
		ItemStack item = event.getItem();
		if(item == null || item.getType() != Material.ENDER_PEARL) return;
		Player player = event.getPlayer();
		if(!isMobDisguised(player)) return;
		event.setCancelled(true);
		notifyPearlBlocked(player);
	}

	// Rate-limit the deny notification to once per second per player. Spam-clicking a pearl can
	// fire several block events in a tick; we still cancel each one, but only message once.
	private void notifyPearlBlocked(Player player) {
		long now = System.currentTimeMillis();
		Long last = lastPearlBlockNotice.get(player.getUniqueId());
		if(last != null && now - last < 1000L) return;
		lastPearlBlockNotice.put(player.getUniqueId(), now);
		player.sendMessage(language.ENDERPEARL_BLOCKED_WHILE_DISGUISED);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void handleBlockPlaceBlock(BlockPlaceEvent event) {
		if(!config.DISALLOW_BLOCK_PLACE_WHILE_DISGUISED) return;
		Player player = event.getPlayer();
		if(!isMobDisguised(player)) return;
		event.setCancelled(true);
		notifyRateLimited(player, lastBlockPlaceNotice, language.BLOCK_PLACE_BLOCKED_WHILE_DISGUISED);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void handleBlockBreakBlock(BlockBreakEvent event) {
		if(!config.DISALLOW_BLOCK_BREAK_WHILE_DISGUISED) return;
		Player player = event.getPlayer();
		if(!isMobDisguised(player)) return;
		event.setCancelled(true);
		notifyRateLimited(player, lastBlockBreakNotice, language.BLOCK_BREAK_BLOCKED_WHILE_DISGUISED);
	}

	// Same 1s per-player rate-limit pattern as notifyPearlBlocked. Holding right-click on a stack
	// or rapid-mining can fire many block events per second; we cancel every one but only emit
	// the chat message at most once per second per player to avoid flooding their chat.
	private void notifyRateLimited(Player player, Map<UUID, Long> bucket, String message) {
		long now = System.currentTimeMillis();
		Long last = bucket.get(player.getUniqueId());
		if(last != null && now - last < 1000L) return;
		bucket.put(player.getUniqueId(), now);
		player.sendMessage(message);
	}

	@EventHandler(ignoreCancelled = true)
	public void handleMobSoundTrigger(PlayerInteractEvent event) {
		if(!config.MOB_SOUND_ENABLED) return;

		Action action = event.getAction();
		if(action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

		if(isOffHandPass(event)) return;

		Player player = event.getPlayer();
		if(!player.isSneaking()) {
			if(debugMode) getLogger().info("[mob-sound] " + player.getName() + " not sneaking, skipping");
			return;
		}

		ItemStack item = event.getItem();
		if(item == null || !item.getType().name().endsWith("_SWORD")) {
			if(debugMode) getLogger().info("[mob-sound] " + player.getName() + " not holding a sword (item="
					+ (item == null ? "null" : item.getType().name()) + "), skipping");
			return;
		}

		Entity disguise = disguiseMap.get(player.getUniqueId());
		if(disguise == null) {
			if(debugMode) getLogger().info("[mob-sound] " + player.getName() + " not disguised, skipping");
			return;
		}

		if(config.USE_PERMISSION_NODES && !player.hasPermission("iDisguise.sound")
				&& !player.hasPermission("iDisguise.*")) return;

		long now = System.currentTimeMillis();
		long cooldownMs = Math.max(0, config.MOB_SOUND_COOLDOWN_SECONDS) * 1000L;
		Long last = mobSoundCooldowns.get(player.getUniqueId());
		if(last != null && now - last < cooldownMs) return;

		XSound sound = resolveMobSound(disguise.getType());
		if(sound == null) {
			if(debugMode) getLogger().info("[mob-sound] no sound mapping for " + disguise.getType().name());
			return;
		}

		mobSoundCooldowns.put(player.getUniqueId(), now);
		if(debugMode) getLogger().info("[mob-sound] playing " + sound.name() + " for "
				+ player.getName() + " (disguise=" + disguise.getType().name() + ")");
		sound.play(player.getLocation(), 1.0f, 1.0f);
		ActionBar.sendActionBar(player, language.MOB_SOUND_PLAYED.replace("%entityType%",
				disguise.getType().name()));
	}

	// True iff this PlayerInteractEvent is the off-hand pass on 1.9+. Goes through full reflection
	// so the bytecode of this class never references EquipmentSlot, which doesn't exist on 1.8.
	private static boolean isOffHandPass(PlayerInteractEvent event) {
		if(!PlayerInteractEvent_getHand_resolved) {
			try {
				PlayerInteractEvent_getHand = PlayerInteractEvent.class.getMethod("getHand");
			} catch(NoSuchMethodException ignored) {
			}
			PlayerInteractEvent_getHand_resolved = true;
		}
		if(PlayerInteractEvent_getHand == null) return false;
		try {
			Object hand = PlayerInteractEvent_getHand.invoke(event);
			if(hand == null) return false;
			return "OFF_HAND".equals(((Enum<?>)hand).name());
		} catch(Throwable t) {
			return false;
		}
	}

	// Resolve a Bukkit Sound for the disguised mob via XSeries, which papers over the
	// 1.8 ("ZOMBIE_IDLE") vs 1.9+ ("ENTITY_ZOMBIE_AMBIENT") enum renaming. Returns null
	// if no plausible sound exists for this entity type on the running server version.
	private static XSound resolveMobSound(EntityType type) {
		String name = type.name();
		Optional<XSound> result = XSound.matchXSound("ENTITY_" + name + "_AMBIENT");
		if(!result.isPresent()) result = XSound.matchXSound("ENTITY_" + name + "_IDLE");
		if(!result.isPresent()) result = XSound.matchXSound("ENTITY_" + name + "_HURT");
		if(!result.isPresent()) result = XSound.matchXSound("ENTITY_" + name + "_DEATH");
		return result.orElse(null);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void handlePlayerInteractEntity(PlayerInteractEntityEvent event) {
		if(event.getRightClicked().hasMetadata("iDisguise")) {
			event.setCancelled(true);
			// Staff right-click a disguise mob to see who's in it; everyone else gets no hint.
			if(hasPermissionSee(event.getPlayer())) {
				Player disguisedPlayer = Bukkit.getPlayer((UUID)event.getRightClicked().getMetadata("iDisguise").get(0).value());
				if(disguisedPlayer != null) {
					event.getPlayer().sendMessage(language.DISGUISE_REVEAL
							.replace("%player%", disguisedPlayer.getName())
							.replace("%entityType%", event.getRightClicked().getType().name()));
				}
			}
			Bukkit.getScheduler().runTaskLater(this, () -> {
				Bukkit.getPluginManager().callEvent(new PlayerInteractDisguisedPlayerEvent(
					event.getPlayer(),
					Bukkit.getPlayer((UUID)event.getRightClicked().getMetadata("iDisguise").get(0).value())
				));
			}, 1L);
		} else if(event.getRightClicked() instanceof Player && playerDisguises.isDisguised((Player)event.getRightClicked())) {
			Bukkit.getScheduler().runTaskLater(this, () -> {
				Bukkit.getPluginManager().callEvent(new PlayerInteractDisguisedPlayerEvent(
					event.getPlayer(),
					(Player)event.getRightClicked()
				));
			}, 1L);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void handleEntityCombustLowest(EntityCombustEvent event) {
		if(event.getEntity().hasMetadata("iDisguise")) {
			if(!(event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent)) {
				event.setCancelled(true);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void handleEntityCombustMonitor(EntityCombustEvent event) {
		if(event.getEntity().hasMetadata("iDisguise")) {
			if(!event.isCancelled()) {
				Bukkit.getPlayer((UUID)event.getEntity().getMetadata("iDisguise").get(0).value()).setFireTicks(event.getDuration());
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void handleEntityTarget(EntityTargetEvent event) {
		if(event.getTarget() != null && event.getTarget().hasMetadata("iDisguise")) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void handleEntityBlockForm(EntityBlockFormEvent event) {
		if(event.getEntity().hasMetadata("iDisguise")) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void handleEntityPortalEvent(EntityPortalEvent event) {
		if(event.getEntity().hasMetadata("iDisguise")) {
			event.setCancelled(true);
		}
	}

	public Language getLanguage() {
		return language;
	}
	
	public String getVersion() {
		return getDescription().getVersion();
	}
	
	public String getNameAndVersion() {
		getFile();
		return getName() + " " + getVersion();
	}

	public File getFile() {
		return super.getFile();
	}
	
	public boolean debugMode() {
		return debugMode;
	}
	
	public static iDisguise getInstance() {
		return INSTANCE;
	}

	// Re-add `target` to `observer`'s tab list after hidePlayer() removed them. Bukkit's hidePlayer
	// on 1.8 sends both an entity-destroy and a player-info-remove packet; we send a player-info-add
	// back so the disguise stays visible in the world (still hidden) without dropping out of tab.
	private void resendTabAdd(Player observer, Player target) {
		if(!PLAYER_DISGUISE_VIEWSELF) return;
		try {
			Object targetHandle = CraftPlayer_getHandle.invoke(target);
			Object observerHandle = CraftPlayer_getHandle.invoke(observer);
			Object addPacket;
			if(!LEGACY_PLAYER_DISGUISE_VIEWSELF) {
				addPacket = PacketUpdatePlayerInfo_new.newInstance(UpdatePlayerInfo_ADD_PLAYER, targetHandle);
			} else {
				Object handleArray = Array.newInstance(EntityPlayer, 1);
				Array.set(handleArray, 0, targetHandle);
				addPacket = PacketUpdatePlayerInfo_new.newInstance(UpdatePlayerInfo_ADD_PLAYER, handleArray);
			}
			Object observerConnection = EntityPlayer_playerConnection.get(observerHandle);
			PlayerConnection_sendPacket.invoke(observerConnection, addPacket);
		} catch(Exception e) {
			if(debugMode) getLogger().log(Level.WARNING, "Failed to resend tab info for " + target.getName() + " to " + observer.getName(), e);
		}
	}

}
