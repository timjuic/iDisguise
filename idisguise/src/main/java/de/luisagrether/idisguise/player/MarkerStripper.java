package de.luisagrether.idisguise.player;

import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;

/**
 * Text a player typed while someone was disguised can keep the invisible marker (NameReplacer.MARKER) when a
 * plugin saved it (a faction description, mail ...). While someone is disguised the name swap removes it;
 * this removes it from chat when nobody is, so it never shows as boxes. Always on, only for chat, and it
 * only looks whether the marker is in the message.
 */
final class MarkerStripper extends PacketAdapter {

	private static final String ESCAPED = "\\" + "u200b" + "\\" + "u2060";

	private final PlayerDisguiseManager manager;

	MarkerStripper(Plugin plugin, PlayerDisguiseManager manager) {
		super(PacketAdapter.params(plugin, PacketType.Play.Server.CHAT).listenerPriority(ListenerPriority.HIGHEST).optionAsync());
		this.manager = manager;
	}

	void register() {
		try {
			ProtocolLibrary.getProtocolManager().addPacketListener(this);
		} catch(Throwable t) {
			plugin.getLogger().warning("Could not start removing the invisible marker from chat: " + t);
		}
	}

	void unregister() {
		try {
			ProtocolLibrary.getProtocolManager().removePacketListener(this);
		} catch(Throwable ignored) {
		}
	}

	@Override
	public void onPacketSending(PacketEvent event) {
		// While names are swapped, the name swap does this itself (it needs the marker first).
		if(event.isCancelled() || !manager.replacingNames().isEmpty()) return;
		try {
			PacketContainer packet = event.getPacket();
			WrappedChatComponent component = packet.getChatComponents().readSafely(0);
			String json;
			boolean spigot = false;
			if(component != null) {
				json = component.getJson();
			} else {
				BaseComponent[] components = packet.getSpecificModifier(BaseComponent[].class).readSafely(0);
				if(components == null) return;
				json = ComponentSerializer.toString(components);
				spigot = true;
			}
			if(json == null || !(json.contains(NameReplacer.MARKER) || json.contains(ESCAPED))) return;
			PacketContainer copy = packet.shallowClone();
			copy.getChatComponents().write(0, WrappedChatComponent.fromJson(json.replace(NameReplacer.MARKER, "").replace(ESCAPED, "")));
			if(spigot) copy.getSpecificModifier(BaseComponent[].class).write(0, null);
			event.setPacket(copy);
		} catch(Throwable ignored) {
			// The message goes out as it was.
		}
	}
}
