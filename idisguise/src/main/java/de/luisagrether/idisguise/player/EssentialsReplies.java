package de.luisagrether.idisguise.player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * EssentialsX /r: when a player disguises or undisguises, nobody's /r points at them any more (and theirs
 * at nobody). A reply across that moment would show the real name and the fake name in one conversation.
 * /r then says Essentials' usual "nobody to reply to". Does nothing without EssentialsX.
 */
final class EssentialsReplies {

	private final Logger logger;
	private boolean read;
	private boolean broken;
	private Method getUser;

	EssentialsReplies(Logger logger) {
		this.logger = logger;
	}

	/** Main thread. */
	void clear(Player player) {
		if(broken) return;
		try {
			Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
			if(essentials == null || !essentials.isEnabled()) return;
			if(!read) {
				read = true;
				getUser = essentials.getClass().getMethod("getUser", Player.class);
			}
			Object own = getUser.invoke(essentials, player);
			if(own != null) setReply(own, null);
			for(Player other : Bukkit.getOnlinePlayers()) {
				if(other == player) continue;
				Object user = getUser.invoke(essentials, other);
				if(user == null) continue;
				Object reply = method(user, "getReplyRecipient", 0).invoke(user);
				if(reply == null) continue;
				Object name = method(reply, "getName", 0).invoke(reply);
				if(reply == own || player.getName().equals(name)) setReply(user, null);
			}
		} catch(Throwable t) {
			broken = true;
			logger.warning("Could not reset EssentialsX /r on disguise changes (not done again until a restart): " + t);
		}
	}

	private static void setReply(Object user, Object recipient) throws ReflectiveOperationException {
		method(user, "setReplyRecipient", 1).invoke(user, recipient);
	}

	private static Method method(Object target, String name, int parameters) throws NoSuchMethodException {
		for(Method method : target.getClass().getMethods()) {
			if(method.getName().equals(name) && method.getParameterTypes().length == parameters) return method;
		}
		throw new NoSuchMethodException(target.getClass().getName() + "." + name);
	}

}
