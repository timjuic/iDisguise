package de.luisagrether.idisguise.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;

/**
 * Swaps a disguised player's real name for the fake one outside chat too (Minecraft 1.8 packets):
 *   menus      window title, item names and lore, player heads (with the disguise skin)
 *   titles     big text in the middle of the screen
 *   holograms  floating names (armor stands, mob names)
 *   signs      sign text
 *   scoreboard sidebar line texts (team prefix / suffix). Never the score entries or the objective:
 *              listening to those made ProtocolLib deliver them out of order, and the 1.8 client then
 *              dropped the sidebar (missing after joining, gone after combat).
 * Only the copy a player's game receives changes; the server's items, entities and data never do.
 * Each part has its own switch in player-disguise.yml (name-swap).
 */
final class TextSwap {

	private TextSwap() {}

	// Armor stands seen being spawned (holograms): their names are always plugin text, coloured or not.
	private static final java.util.Set<Integer> ARMOR_STANDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	static void rewrite(PacketEvent event, PlayerDisguiseManager manager) {
		Collection<PlayerDisguise> disguises = manager.replacingNames();
		if(disguises.isEmpty()) return;
		PlayerDisguiseSettings settings = manager.settings();
		PacketType type = event.getPacketType();
		if(type == PacketType.Play.Server.OPEN_WINDOW) {
			if(settings.swapInMenus) chatComponent(event, 0, disguises);
		} else if(type == PacketType.Play.Server.WINDOW_ITEMS) {
			if(settings.swapInMenus) windowItems(event, disguises);
		} else if(type == PacketType.Play.Server.SET_SLOT) {
			if(settings.swapInMenus) setSlot(event, disguises);
		} else if(type == PacketType.Play.Server.TITLE) {
			if(settings.swapInTitles) chatComponent(event, 0, disguises);
		} else if(type == PacketType.Play.Server.ENTITY_METADATA) {
			if(settings.swapInHolograms) metadata(event, disguises);
		} else if(type == PacketType.Play.Server.SPAWN_ENTITY_LIVING) {
			if(settings.swapInHolograms) spawnLiving(event, disguises);
		} else if(type == PacketType.Play.Server.UPDATE_SIGN) {
			if(settings.swapInSigns) sign(event, disguises);
		}
	}

	// ---------------------------------------------------------------- texts

	private static void chatComponent(PacketEvent event, int index, Collection<PlayerDisguise> disguises) {
		WrappedChatComponent component = event.getPacket().getChatComponents().readSafely(index);
		if(component == null) return;
		String replaced = NameReplacer.replaceJson(component.getJson(), disguises);
		if(replaced == null) return;
		PacketContainer copy = event.getPacket().shallowClone();
		copy.getChatComponents().write(index, WrappedChatComponent.fromJson(replaced));
		event.setPacket(copy);
	}

	private static void strings(PacketEvent event, int[] indexes, int maxLength, Collection<PlayerDisguise> disguises) {
		PacketContainer copy = null;
		for(int index : indexes) {
			String value = event.getPacket().getStrings().readSafely(index);
			if(value == null) continue;
			String replaced = NameReplacer.replace(value, disguises);
			if(replaced.equals(value)) continue;
			// Longer than the client allows would disconnect the player: cut it.
			if(replaced.length() > maxLength) replaced = replaced.substring(0, maxLength);
			if(replaced.endsWith(String.valueOf(org.bukkit.ChatColor.COLOR_CHAR))) replaced = replaced.substring(0, replaced.length() - 1);
			if(copy == null) copy = event.getPacket().shallowClone();
			copy.getStrings().write(index, replaced);
		}
		if(copy != null) event.setPacket(copy);
	}

	private static void sign(PacketEvent event, Collection<PlayerDisguise> disguises) {
		WrappedChatComponent[] lines = event.getPacket().getChatComponentArrays().readSafely(0);
		if(lines == null) return;
		WrappedChatComponent[] result = new WrappedChatComponent[lines.length];
		boolean changed = false;
		for(int i = 0; i < lines.length; i++) {
			result[i] = lines[i];
			if(lines[i] == null) continue;
			String replaced = NameReplacer.replaceJson(lines[i].getJson(), disguises);
			if(replaced != null) {
				result[i] = WrappedChatComponent.fromJson(replaced);
				changed = true;
			}
		}
		if(!changed) return;
		PacketContainer copy = event.getPacket().shallowClone();
		copy.getChatComponentArrays().write(0, result);
		event.setPacket(copy);
	}

	// ---------------------------------------------------------------- holograms (entity names)

	private static void metadata(PacketEvent event, Collection<PlayerDisguise> disguises) {
		List<WrappedWatchableObject> values = event.getPacket().getWatchableCollectionModifier().readSafely(0);
		if(values == null || values.isEmpty()) return;
		Integer entity = event.getPacket().getIntegers().readSafely(0);
		boolean hologram = entity != null && ARMOR_STANDS.contains(entity);
		List<WrappedWatchableObject> result = new ArrayList<>(values.size());
		boolean changed = false;
		for(WrappedWatchableObject value : values) {
			// Index 2 is the custom name. A new object: the old one belongs to the server's entity.
			if(value != null && value.getIndex() == 2 && value.getValue() instanceof String) {
				String name = (String)value.getValue();
				String replaced = hologram || pluginText(name) ? NameReplacer.replace(name, disguises) : stripMarker(name);
				if(!replaced.equals(name)) {
					result.add(new WrappedWatchableObject(2, replaced));
					changed = true;
					continue;
				}
			}
			result.add(value);
		}
		if(!changed) return;
		PacketContainer copy = event.getPacket().shallowClone();
		copy.getWatchableCollectionModifier().write(0, result);
		event.setPacket(copy);
	}

	private static void spawnLiving(PacketEvent event, Collection<PlayerDisguise> disguises) {
		// 1.8: the second number is the mob type, 30 = armor stand (holograms; a name tag can't go on one).
		Integer entity = event.getPacket().getIntegers().readSafely(0);
		Integer mobType = event.getPacket().getIntegers().readSafely(1);
		boolean hologram = mobType != null && mobType == 30;
		if(hologram && entity != null) {
			if(ARMOR_STANDS.size() > 20000) ARMOR_STANDS.clear();
			ARMOR_STANDS.add(entity);
		}
		WrappedDataWatcher watcher = event.getPacket().getDataWatcherModifier().readSafely(0);
		if(watcher == null) return;
		Object name = watcher.getObject(2);
		if(!(name instanceof String)) return;
		// A mob a player named with a name tag keeps the typed name (only plugin text has colour codes).
		String replaced = hologram || pluginText((String)name) ? NameReplacer.replace((String)name, disguises) : stripMarker((String)name);
		if(replaced.equals(name)) return;
		// A brand-new watcher with copied values: a clone shares its values with the real entity on 1.8,
		// and changing it would rename the mob / armor stand on the server for real.
		WrappedDataWatcher copyWatcher = new WrappedDataWatcher();
		for(WrappedWatchableObject object : watcher.getWatchableObjects()) {
			copyWatcher.setObject(object.getIndex(), object.getIndex() == 2 ? replaced : object.getValue());
		}
		PacketContainer copy = event.getPacket().shallowClone();
		copy.getDataWatcherModifier().write(0, copyWatcher);
		event.setPacket(copy);
	}

	// ---------------------------------------------------------------- menus

	private static void windowItems(PacketEvent event, Collection<PlayerDisguise> disguises) {
		// Creative players send items back to the server: never change what they hold.
		if(event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
		// The player's own inventory: items they renamed themselves stay as they are (an item named
		// after a disguised player would otherwise show the fake name and tell who it is).
		Integer window = event.getPacket().getIntegers().readSafely(0);
		if(window == null) return;
		ItemStack[] items = event.getPacket().getItemArrayModifier().readSafely(0);
		if(items == null) return;
		ItemStack[] result = new ItemStack[items.length];
		boolean changed = false;
		for(int i = 0; i < items.length; i++) {
			// The own inventory (window 0): only the invisible marker comes out, names stay as they are.
			ItemStack replaced = window <= 0 ? markerFree(items[i]) : item(items[i], disguises);
			result[i] = replaced != null ? replaced : items[i];
			if(replaced != null) changed = true;
		}
		if(!changed) return;
		PacketContainer copy = event.getPacket().shallowClone();
		copy.getItemArrayModifier().write(0, result);
		event.setPacket(copy);
	}

	private static void setSlot(PacketEvent event, Collection<PlayerDisguise> disguises) {
		if(event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
		Integer window = event.getPacket().getIntegers().readSafely(0);
		if(window == null) return;
		ItemStack item = event.getPacket().getItemModifier().readSafely(0);
		ItemStack replaced = window <= 0 ? markerFree(item) : item(item, disguises);
		if(replaced == null) return;
		PacketContainer copy = event.getPacket().shallowClone();
		copy.getItemModifier().write(0, replaced);
		event.setPacket(copy);
	}

	/**
	 * A mob's name a plugin gave it: those carry colour codes. A name tag a player typed never can, and
	 * stays as typed: otherwise naming a mob after a suspected real name would show the fake name.
	 */
	static boolean pluginText(String text) {
		return text != null && text.indexOf('§') >= 0;
	}

	/** The invisible marker never stays in text a player sees, even where no name is swapped. */
	static String stripMarker(String text) {
		return text == null ? null : text.replace(NameReplacer.MARKER, "");
	}

	/** A copy without the invisible marker in name and lore, or null when it has none. */
	private static ItemStack markerFree(ItemStack item) {
		if(item == null || !item.hasItemMeta()) return null;
		ItemMeta meta = item.getItemMeta();
		boolean changed = false;
		if(meta.hasDisplayName() && meta.getDisplayName().contains(NameReplacer.MARKER)) {
			meta.setDisplayName(stripMarker(meta.getDisplayName()));
			changed = true;
		}
		if(meta.hasLore()) {
			List<String> lore = new ArrayList<>();
			for(String line : meta.getLore()) {
				if(line != null && line.contains(NameReplacer.MARKER)) changed = true;
				lore.add(stripMarker(line));
			}
			if(changed) meta.setLore(lore);
		}
		if(!changed) return null;
		ItemStack copy = item.clone();
		copy.setItemMeta(meta);
		return copy;
	}

	/** Renamed in an anvil (a player typed the name, colour perks included): the name stays as typed. */
	private static boolean anvilRenamed(ItemMeta meta) {
		return meta instanceof org.bukkit.inventory.meta.Repairable && ((org.bukkit.inventory.meta.Repairable)meta).hasRepairCost()
				&& ((org.bukkit.inventory.meta.Repairable)meta).getRepairCost() > 0;
	}

	/** A changed copy of the item, or null when nothing needs to change. */
	@SuppressWarnings("deprecation")
	private static ItemStack item(ItemStack item, Collection<PlayerDisguise> disguises) {
		if(item == null || !item.hasItemMeta()) return null;
		ItemMeta meta = item.getItemMeta();
		boolean changed = false;
		if(meta.hasDisplayName()) {
			String replaced = anvilRenamed(meta) ? stripMarker(meta.getDisplayName()) : NameReplacer.replace(meta.getDisplayName(), disguises);
			if(!replaced.equals(meta.getDisplayName())) {
				meta.setDisplayName(replaced);
				changed = true;
			}
		}
		if(meta.hasLore()) {
			List<String> lore = new ArrayList<>();
			boolean loreChanged = false;
			for(String line : meta.getLore()) {
				String replaced = NameReplacer.replace(line, disguises);
				lore.add(replaced);
				if(!replaced.equals(line)) loreChanged = true;
			}
			if(loreChanged) {
				meta.setLore(lore);
				changed = true;
			}
		}
		PlayerDisguise headOwner = null;
		if(meta instanceof org.bukkit.inventory.meta.SkullMeta && ((org.bukkit.inventory.meta.SkullMeta)meta).hasOwner()) {
			String owner = ((org.bukkit.inventory.meta.SkullMeta)meta).getOwner();
			for(PlayerDisguise disguise : disguises) {
				if(disguise.getRealName().equalsIgnoreCase(owner)) headOwner = disguise;
			}
		}
		if(!changed && headOwner == null) return null;
		ItemStack copy = item.clone();
		copy.setItemMeta(meta);
		if(headOwner != null) {
			copy = head(copy, headOwner);
		}
		return copy;
	}

	/** A player head of the disguised player shows the fake name and the disguise skin. */
	private static ItemStack head(ItemStack item, PlayerDisguise disguise) {
		try {
			ItemStack craft = MinecraftReflection.getBukkitItemStack(MinecraftReflection.getMinecraftItemStack(item));
			NbtCompound tag = NbtFactory.asCompound(NbtFactory.fromItemTag(craft));
			NbtCompound owner = NbtFactory.ofCompound("SkullOwner");
			owner.put("Name", disguise.getFakeName());
			owner.put("Id", disguise.getFakeUniqueId().toString());
			if(disguise.hasSkin()) {
				NbtCompound texture = NbtFactory.ofCompound("");
				texture.put("Value", disguise.getSkinValue());
				if(disguise.getSkinSignature() != null) texture.put("Signature", disguise.getSkinSignature());
				NbtCompound properties = NbtFactory.ofCompound("Properties");
				properties.put(NbtFactory.ofList("textures", texture));
				owner.put(properties);
			}
			tag.put(owner);
			return craft;
		} catch(RuntimeException e) {
			return item;
		}
	}

}
