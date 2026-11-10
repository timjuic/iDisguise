package de.luisagrether.idisguise.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * BungeeCord puts the skin it has for a player (SkinsRestorer's, or the Mojang one) into every tab entry of
 * that player, also the player's own. So a disguised player always sees their real skin on themselves.
 *
 * iDisguise on a game server asks this helper to use the disguise skin for that player's own entry. The
 * skin is only changed in the proxy's memory, never saved, and the old skin data is put back exactly
 * (also "none") on undisguise, on every server switch and on logout.
 *
 * Only messages from game servers are used; the same channel from a player's game is dropped. When
 * anything inside this proxy is not where it is expected, the helper turns itself off with one console
 * line, and iDisguise simply gets no answer (everything stays as without the helper).
 */
public final class IDisguiseProxy extends Plugin implements Listener {

	static final String CHANNEL = "idisguise:skin";
	private static final byte VERSION = 1;
	private static final byte SET = 1;
	private static final byte RESET = 2;
	private static final byte SET_DONE = 1;
	private static final byte RESET_DONE = 2;
	private static final int MAX_TEXT = 32767;

	/** What the player had before the disguise skin: put back exactly. */
	private static final class Saved {
		final Object profile;      // the LoginResult the player had (null = none)
		final Object properties;   // its property array then (may be null)
		Object ourProfile;         // the LoginResult in use while disguised
		Object ourProperties;      // the property array set by this helper

		Saved(Object profile, Object properties) {
			this.profile = profile;
			this.properties = properties;
		}
	}

	private final Map<UUID, Saved> saved = new ConcurrentHashMap<>();
	// Players on their way to another server (since when): a late request from the old server is ignored,
	// so the next server never gets the disguise skin.
	private final Map<UUID, Long> switching = new ConcurrentHashMap<>();

	private Method getLoginProfile;
	private Field loginProfileField;
	private Method getProperties;
	private Method setProperties;
	private Constructor<?> newLoginResult;
	private Class<?> propertyClass;
	private Constructor<?> newProperty;
	private Method propertyName;

	@Override
	public void onEnable() {
		try {
			findInternals();
		} catch(Throwable t) {
			getLogger().warning("This proxy works differently inside than expected, so disguised players keep seeing "
					+ "their real skin on themselves (nothing else changes): " + t);
			return;
		}
		getProxy().registerChannel(CHANNEL);
		getProxy().getPluginManager().registerListener(this, this);
	}

	@Override
	public void onDisable() {
		for(ProxiedPlayer player : getProxy().getPlayers()) {
			try {
				restore(player);
			} catch(Throwable ignored) {
			}
		}
		saved.clear();
	}

	/** BungeeCord's own classes (InitialHandler, LoginResult, Property), looked up once. */
	private void findInternals() throws Exception {
		Class<?> handler = Class.forName("net.md_5.bungee.connection.InitialHandler");
		getLoginProfile = handler.getMethod("getLoginProfile");
		Class<?> loginResult = getLoginProfile.getReturnType();
		loginProfileField = null;
		for(Class<?> type = handler; type != null && loginProfileField == null; type = type.getSuperclass()) {
			for(Field field : type.getDeclaredFields()) {
				if(field.getType() == loginResult && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					loginProfileField = field;
					break;
				}
			}
		}
		if(loginProfileField == null) throw new NoSuchFieldException("InitialHandler.loginProfile");
		loginProfileField.setAccessible(true);
		getProperties = loginResult.getMethod("getProperties");
		Class<?> propertyArray = getProperties.getReturnType();
		if(!propertyArray.isArray()) throw new NoSuchMethodException("LoginResult.getProperties() is no array");
		propertyClass = propertyArray.getComponentType();
		setProperties = loginResult.getMethod("setProperties", propertyArray);
		newLoginResult = loginResult.getConstructor(String.class, String.class, propertyArray);
		newProperty = propertyClass.getConstructor(String.class, String.class, String.class);
		propertyName = propertyClass.getMethod("getName");
	}

	@EventHandler
	public void onMessage(PluginMessageEvent event) {
		if(!CHANNEL.equals(event.getTag())) return;
		// Never passed on: not to the player's game, and never from a player's game to a server.
		event.setCancelled(true);
		if(!(event.getSender() instanceof Server) || !(event.getReceiver() instanceof ProxiedPlayer)) return;
		Server server = (Server) event.getSender();
		ProxiedPlayer player = (ProxiedPlayer) event.getReceiver();
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
			if(in.readByte() != VERSION) return;
			byte action = in.readByte();
			if(action == SET) {
				// Only from the server the player is on, and not while moving to another one.
				if(player.getServer() == null || !server.getInfo().equals(player.getServer().getInfo())) return;
				Long since = switching.get(player.getUniqueId());
				if(since != null && System.currentTimeMillis() - since < 15000L) return;
				String value = in.readUTF();
				String signature = in.readUTF();
				if(value.isEmpty() || value.length() > MAX_TEXT || signature.length() > MAX_TEXT) return;
				if(useSkin(player, value, signature.isEmpty() ? null : signature)) reply(server, SET_DONE);
			} else if(action == RESET) {
				restore(player);
				reply(server, RESET_DONE);
			}
		} catch(Throwable t) {
			getLogger().warning("Could not change the own skin of " + player.getName() + " (nothing else changes): " + t);
		}
	}

	/** Leaving the server (or joining the first one): the real skin data is back before the next server gets it. */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onConnect(ServerConnectEvent event) {
		ProxiedPlayer player = event.getPlayer();
		// Already on that server (e.g. /server factions while there): nothing changes.
		if(player.getServer() != null && event.getTarget() != null && event.getTarget().equals(player.getServer().getInfo())) return;
		switching.put(player.getUniqueId(), System.currentTimeMillis());
		restoreSafely(player);
	}

	@EventHandler
	public void onConnected(ServerConnectedEvent event) {
		switching.remove(event.getPlayer().getUniqueId());
		restoreSafely(event.getPlayer());
	}

	@EventHandler
	public void onSwitch(ServerSwitchEvent event) {
		switching.remove(event.getPlayer().getUniqueId());
		restoreSafely(event.getPlayer());
	}

	@EventHandler
	public void onDisconnect(PlayerDisconnectEvent event) {
		saved.remove(event.getPlayer().getUniqueId());
		switching.remove(event.getPlayer().getUniqueId());
	}

	private void restoreSafely(ProxiedPlayer player) {
		try {
			restore(player);
		} catch(Throwable t) {
			getLogger().warning("Could not give " + player.getName() + " their own skin data back: " + t);
		}
	}

	private void reply(Server server, byte action) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(VERSION);
		out.writeByte(action);
		server.sendData(CHANNEL, bytes.toByteArray());
	}

	private boolean useSkin(ProxiedPlayer player, String value, String signature) throws Exception {
		PendingConnection connection = player.getPendingConnection();
		if(connection == null || !loginProfileField.getDeclaringClass().isInstance(connection)) return false;
		Object profile = getLoginProfile.invoke(connection);
		Saved before = saved.get(player.getUniqueId());
		// Something else (e.g. SkinsRestorer) changed the skin data since: that is the "before" now.
		if(before != null && (profile != before.ourProfile || (profile != null && getProperties.invoke(profile) != before.ourProperties))) {
			before = null;
		}
		if(before == null) {
			before = new Saved(profile, profile == null ? null : getProperties.invoke(profile));
		}

		// Every other property stays; only "textures" becomes the disguise skin.
		List<Object> properties = new ArrayList<>();
		Object old = before.properties;
		if(old != null) {
			for(int i = 0; i < Array.getLength(old); i++) {
				Object property = Array.get(old, i);
				if(property != null && !"textures".equals(propertyName.invoke(property))) properties.add(property);
			}
		}
		properties.add(newProperty.newInstance("textures", value, signature));
		Object array = Array.newInstance(propertyClass, properties.size());
		for(int i = 0; i < properties.size(); i++) Array.set(array, i, properties.get(i));

		if(profile == null) {
			// No skin data at all (e.g. a cracked player without a SkinsRestorer skin): a temporary one.
			profile = newLoginResult.newInstance(player.getUniqueId().toString().replace("-", ""), player.getName(), array);
			loginProfileField.set(connection, profile);
		} else {
			setProperties.invoke(profile, array);
		}
		before.ourProfile = profile;
		before.ourProperties = array;
		saved.put(player.getUniqueId(), before);
		return true;
	}

	/** Puts back exactly what the player had, unless something else (e.g. /skin) changed it since. */
	private void restore(ProxiedPlayer player) throws Exception {
		Saved before = saved.remove(player.getUniqueId());
		if(before == null) return;
		PendingConnection connection = player.getPendingConnection();
		if(connection == null || !loginProfileField.getDeclaringClass().isInstance(connection)) return;
		Object profile = getLoginProfile.invoke(connection);
		if(profile != before.ourProfile) return;
		if(getProperties.invoke(profile) != before.ourProperties) return;
		if(before.profile == null) {
			loginProfileField.set(connection, null);
		} else {
			setProperties.invoke(before.profile, before.properties);
		}
	}
}
