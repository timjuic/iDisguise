package de.luisagrether.idisguise.player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * DeluxeTags through reflection (iDisguise runs without it): the list of tags a disguise can show.
 * Newer DeluxeTags: plugin.getTagsHandler().getAllTags(); older ones: a static getLoadedTags() on the
 * tag class. Both are tried.
 */
final class DeluxeTagsHook {

	/** One tag: identifier (as in /tags), display text with & codes, description, permission. */
	static final class Tag {
		final String id;
		final String display;
		final String description;
		final String permission;

		Tag(String id, String display, String description, String permission) {
			this.id = id;
			this.display = display;
			this.description = description;
			this.permission = permission;
		}

		/** The tag as it shows in chat, colours applied (with its spaces, which separate it from the name). */
		String shown() {
			return ChatColor.translateAlternateColorCodes('&', display);
		}

		/** The tag without spaces at the start and end (colours kept), for messages and menus. */
		String label() {
			return shown().replaceFirst("^((?:§.)*)\s+", "$1").replaceFirst("\s+((?:§.)*)$", "$1");
		}
	}

	private final Plugin plugin;
	private final Logger logger;
	private boolean warned;

	private DeluxeTagsHook(Plugin plugin, Logger logger) {
		this.plugin = plugin;
		this.logger = logger;
	}

	static DeluxeTagsHook create(Logger logger) {
		Plugin plugin = Bukkit.getPluginManager().getPlugin("DeluxeTags");
		if(plugin == null || !plugin.isEnabled()) return null;
		DeluxeTagsHook hook = new DeluxeTagsHook(plugin, logger);
		if(hook.tags().isEmpty()) {
			logger.warning("DeluxeTags found, but its tags could not be read. Disguises can't pick a tag.");
		}
		return hook;
	}

	/** Every loaded tag, in DeluxeTags' order (read fresh, so /tags reload is picked up). */
	List<Tag> tags() {
		try {
			Collection<?> raw = rawTags();
			if(raw == null) return Collections.emptyList();
			List<Tag> result = new ArrayList<>();
			for(Object tag : raw) {
				String id = string(tag, "getIdentifier");
				if(id == null || id.isEmpty()) continue;
				String display = string(tag, "getDisplayTag");
				String description = string(tag, "getDescription");
				String permission = string(tag, "getPermission");
				result.add(new Tag(id, display == null ? "" : display, description == null ? "" : description,
						permission == null || permission.isEmpty() ? "deluxetags.tag." + id : permission));
			}
			return result;
		} catch(Throwable t) {
			if(!warned) {
				warned = true;
				logger.warning("Could not read the DeluxeTags tags: " + t);
			}
			return Collections.emptyList();
		}
	}

	/** A tag by its identifier (any capitalisation), or null. */
	Tag find(String id) {
		if(id == null) return null;
		for(Tag tag : tags()) {
			if(tag.id.equalsIgnoreCase(id)) return tag;
		}
		return null;
	}

	boolean owns(Player player, Tag tag) {
		return player.hasPermission(tag.permission);
	}

	private Collection<?> rawTags() throws ReflectiveOperationException {
		// Newer: DeluxeTags#getTagsHandler().getAllTags()
		try {
			Object handler = plugin.getClass().getMethod("getTagsHandler").invoke(plugin);
			if(handler != null) {
				Object all = handler.getClass().getMethod("getAllTags").invoke(handler);
				if(all instanceof Collection) return (Collection<?>)all;
			}
		} catch(NoSuchMethodException e) {
			// older DeluxeTags
		}
		// Older: a static method on the tag class that returns all loaded tags.
		ClassLoader loader = plugin.getClass().getClassLoader();
		for(String name : new String[] {"me.clip.deluxetags.tags.DeluxeTag", "me.clip.deluxetags.objects.DeluxeTag", "me.clip.deluxetags.DeluxeTag"}) {
			Class<?> type;
			try {
				type = Class.forName(name, true, loader);
			} catch(ClassNotFoundException e) {
				continue;
			}
			for(Method method : type.getMethods()) {
				if(!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) continue;
				String lower = method.getName().toLowerCase(Locale.ENGLISH);
				if(!lower.contains("tags") || !lower.startsWith("get")) continue;
				Object value = method.invoke(null);
				if(value instanceof Map) value = ((Map<?, ?>)value).values();
				if(value instanceof Collection && !((Collection<?>)value).isEmpty()
						&& type.isInstance(((Collection<?>)value).iterator().next())) {
					return (Collection<?>)value;
				}
			}
		}
		return null;
	}

	private static String string(Object target, String method) {
		try {
			Object value = target.getClass().getMethod(method).invoke(target);
			return value == null ? null : String.valueOf(value);
		} catch(ReflectiveOperationException e) {
			return null;
		}
	}

}
