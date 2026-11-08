package de.luisagrether.idisguise.player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PrefixNode;

/** LuckPerms: who has played here, real ranks and rank prefixes. Only loaded when LuckPerms is installed. */
final class LuckPermsHook {

	private final LuckPerms api;

	private LuckPermsHook(LuckPerms api) {
		this.api = api;
	}

	static LuckPermsHook create() {
		if(Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
		try {
			LuckPerms api = Bukkit.getServicesManager().load(LuckPerms.class);
			return api == null ? null : new LuckPermsHook(api);
		} catch(Throwable t) {
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

	// Priority of the disguise prefix: above every real prefix, and how we find it again.
	private static final int FAKE_PREFIX_PRIORITY = 987654;

	/**
	 * Shows a fake rank prefix (prefix == null removes it). Temporary ("transient") LuckPerms data:
	 * never saved, gone after logout or a restart, and it gives no permissions. Every plugin that reads
	 * the prefix through Vault (EssentialsX chat ...) then shows the fake rank.
	 */
	void setFakePrefix(Player player, String prefix) {
		User user = api.getUserManager().getUser(player.getUniqueId());
		if(user == null) return;
		user.transientData().clear(NodeType.PREFIX.predicate(node -> node.getPriority() == FAKE_PREFIX_PRIORITY));
		if(prefix != null) {
			user.transientData().add(PrefixNode.builder(prefix, FAKE_PREFIX_PRIORITY).build());
		}
	}

	String groupPrefix(String groupName) {
		Group group = api.getGroupManager().getGroup(groupName);
		return group == null ? null : group.getCachedData().getMetaData().getPrefix();
	}

}
