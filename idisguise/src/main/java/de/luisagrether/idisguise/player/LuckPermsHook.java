package de.luisagrether.idisguise.player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.PermissionNode;

/** LuckPerms: who has played here, real ranks and rank prefixes. Only loaded when LuckPerms is installed. */
final class LuckPermsHook {

	private final LuckPerms api;

	private LuckPermsHook(LuckPerms api) {
		this.api = api;
	}

	static LuckPermsHook create(Logger logger) {
		if(Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
		try {
			LuckPerms api = Bukkit.getServicesManager().load(LuckPerms.class);
			if(api == null) {
				logger.warning("LuckPerms is installed but its API isn't available: fake ranks won't show in chat.");
				return null;
			}
			return new LuckPermsHook(api);
		} catch(Throwable t) {
			logger.warning("Could not connect to LuckPerms (" + t + "): fake ranks won't show in chat.");
			return null;
		}
	}

	/** UUID of a player who has played here before, or null. */
	CompletableFuture<UUID> lookupUniqueId(String name) {
		return api.getUserManager().lookupUniqueId(name);
	}

	/** Primary group of a player who has played here before, or null. */
	CompletableFuture<String> lookupPrimaryGroup(String name) {
		return lookupUniqueId(name).thenCompose(uuid -> {
			if(uuid == null) return CompletableFuture.completedFuture(null);
			return api.getUserManager().loadUser(uuid).thenApply(user -> user == null ? null : user.getPrimaryGroup());
		});
	}

	String primaryGroup(Player player) {
		User user = api.getUserManager().getUser(player.getUniqueId());
		return user == null ? null : user.getPrimaryGroup();
	}

	String prefix(Player player) {
		User user = api.getUserManager().getUser(player.getUniqueId());
		return user == null ? null : user.getCachedData().getMetaData().getPrefix();
	}

	/**
	 * Takes these permissions away for now: LuckPerms "transient" nodes set to false, which are never saved
	 * and are gone after a logout or restart, so a player can't be left without them.
	 */
	void denyForNow(Player player, java.util.Collection<String> nodes) {
		User user = api.getUserManager().getUser(player.getUniqueId());
		if(user == null) return;
		for(String node : nodes) user.transientData().add(PermissionNode.builder(node).value(false).build());
	}

	/** Gives back what denyForNow took away. */
	void undeny(Player player, java.util.Collection<String> nodes) {
		User user = api.getUserManager().getUser(player.getUniqueId());
		if(user == null) return;
		for(String node : nodes) user.transientData().remove(PermissionNode.builder(node).value(false).build());
	}

	String groupPrefix(String groupName) {
		Group group = api.getGroupManager().getGroup(groupName);
		return group == null ? null : group.getCachedData().getMetaData().getPrefix();
	}

}
