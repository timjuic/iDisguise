package de.luisagrether.idisguise;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

// Region lookups against WorldGuard 6 (the 1.8 line), done by reflection so iDisguise builds without
// WorldGuard and runs fine when it isn't installed.
public class WorldGuardRegions {

	private final iDisguise plugin;
	private final Method WGBukkit_getRegionManager;
	private final Method RegionManager_getApplicableRegions;
	private final Method ProtectedRegion_getId;

	private WorldGuardRegions(iDisguise plugin, Method getRegionManager, Method getApplicableRegions, Method getId) {
		this.plugin = plugin;
		this.WGBukkit_getRegionManager = getRegionManager;
		this.RegionManager_getApplicableRegions = getApplicableRegions;
		this.ProtectedRegion_getId = getId;
	}

	// Returns null when WorldGuard isn't installed or isn't a version with the WorldGuard 6 API.
	public static WorldGuardRegions detect(iDisguise plugin) {
		Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
		if(worldGuard == null) return null;
		try {
			ClassLoader loader = worldGuard.getClass().getClassLoader();
			Class<?> WGBukkit = Class.forName("com.sk89q.worldguard.bukkit.WGBukkit", true, loader);
			Class<?> RegionManager = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", true, loader);
			Class<?> ProtectedRegion = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, loader);
			return new WorldGuardRegions(plugin,
					WGBukkit.getMethod("getRegionManager", org.bukkit.World.class),
					RegionManager.getMethod("getApplicableRegions", Location.class),
					ProtectedRegion.getMethod("getId"));
		} catch(ReflectiveOperationException e) {
			return null;
		}
	}

	// True when any region at the location has one of the given ids (case-insensitive).
	public boolean isInAny(Location location, Collection<String> regionIds) {
		if(regionIds.isEmpty()) return false;
		try {
			Object regionManager = WGBukkit_getRegionManager.invoke(null, location.getWorld());
			// null when region protection is turned off for that world
			if(regionManager == null) return false;
			for(Object region : (Iterable<?>)RegionManager_getApplicableRegions.invoke(regionManager, location)) {
				String id = (String)ProtectedRegion_getId.invoke(region);
				for(String regionId : regionIds) {
					if(regionId.equalsIgnoreCase(id)) return true;
				}
			}
		} catch(Exception e) {
			if(plugin.debugMode()) plugin.getLogger().log(Level.WARNING, "WorldGuard region lookup failed", e);
		}
		return false;
	}
}
