package de.luisagrether.idisguise.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * The rank menu after /disguise <name>:
 *   row 1  frame with a name tag in the middle (who you disguise as)
 *   row 2  player ranks
 *   row 3  empty (only when staff ranks follow)
 *   row 4  staff ranks
 *   last   real rank of that player / no rank / cancel
 * Every click is cancelled; the choice runs on the next tick, only while this menu is still open.
 */
final class RankMenu implements InventoryHolder {

	/** One clickable entry. value: the rank passed on (null = real rank, NO_RANK = no rank). */
	static final class Entry {
		final String value;
		final ItemStack item;

		Entry(String value, ItemStack item) {
			this.value = value;
			this.item = item;
		}
	}

	static final String CANCEL = "\u0000cancel";

	private final Inventory inventory;
	private final Map<Integer, String> actions = new HashMap<>();
	private final Consumer<String> choose;

	/**
	 * header: the name tag on top. playerRanks / staffRanks: one entry per rank (staff may be empty).
	 * realRank (may be null), noRank, cancel: the bottom row.
	 */
	RankMenu(String title, ItemStack header, ItemStack frame, List<Entry> playerRanks, List<Entry> staffRanks,
			Entry realRank, Entry cancel, Consumer<String> choose) {
		this.choose = choose;
		// Player ranks (with "no rank" first): rows of 5 with a free slot between them.
		// Staff ranks: rows of up to 9 side by side, after an empty row.
		List<int[]> layout = new ArrayList<>(); // {row, column, index in all}
		List<Entry> all = new ArrayList<>();
		int row = 1;
		for(int i = 0; i < playerRanks.size(); i += 5, row++) {
			int count = Math.min(5, playerRanks.size() - i);
			int start = (9 - (count * 2 - 1)) / 2;
			for(int k = 0; k < count; k++) {
				layout.add(new int[] {row, start + k * 2, all.size()});
				all.add(playerRanks.get(i + k));
			}
		}
		if(!staffRanks.isEmpty()) {
			row++; // the gap
			for(int i = 0; i < staffRanks.size(); i += 9, row++) {
				int count = Math.min(9, staffRanks.size() - i);
				int start = (9 - count) / 2;
				for(int k = 0; k < count; k++) {
					layout.add(new int[] {row, start + k, all.size()});
					all.add(staffRanks.get(i + k));
				}
			}
		}
		int rows = Math.min(6, row + 1);
		// 1.8 menu titles are at most 32 characters.
		this.inventory = Bukkit.createInventory(this, rows * 9, title.length() > 32 ? title.substring(0, 32) : title);

		for(int slot = 0; slot < 9; slot++) inventory.setItem(slot, frame);
		inventory.setItem(4, header);
		for(int[] place : layout) {
			if(place[0] < rows - 1) put(place[0] * 9 + place[1], all.get(place[2]));
		}
		int bottom = (rows - 1) * 9;
		if(realRank != null) {
			put(bottom + 3, realRank);
			put(bottom + 5, cancel);
		} else {
			put(bottom + 4, cancel);
		}
	}

	private void put(int slot, Entry entry) {
		inventory.setItem(slot, entry.item);
		actions.put(slot, entry.value == null ? REAL_RANK : entry.value);
	}

	// Marker for "real rank": the map can't tell a null value from a missing slot.
	private static final String REAL_RANK = "\u0000real-rank";

	void open(Player player) {
		player.openInventory(inventory);
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	// ---------------------------------------------------------------- items

	/**
	 * An item from "MATERIAL" or "MATERIAL:data" (1.8 names, e.g. GOLD_NUGGET or STAINED_CLAY:4).
	 * Unknown names fall back to paper, so a typo in the config never breaks the menu.
	 */
	@SuppressWarnings("deprecation")
	static ItemStack item(String spec, String name, List<String> lore) {
		Material material = null;
		short data = 0;
		if(spec != null) {
			String[] parts = spec.trim().split(":");
			material = Material.matchMaterial(parts[0]);
			if(parts.length > 1) {
				try {
					data = Short.parseShort(parts[1]);
				} catch(NumberFormatException e) {
					data = 0;
				}
			}
		}
		if(material == null) {
			material = Material.PAPER;
			data = 0;
		}
		ItemStack item = new ItemStack(material, 1, data);
		ItemMeta meta = item.getItemMeta();
		if(meta != null) {
			meta.setDisplayName(name);
			if(lore != null) meta.setLore(lore);
			item.setItemMeta(meta);
		}
		return item;
	}

	static List<String> lines(String text) {
		return new ArrayList<>(Arrays.asList(text.split("\n")));
	}

	// ---------------------------------------------------------------- clicks

	/** Cancels every click in the menu and runs the chosen entry. Registered once. */
	static final class Clicks implements Listener {

		private final Plugin plugin;

		Clicks(Plugin plugin) {
			this.plugin = plugin;
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onClick(InventoryClickEvent event) {
			if(!(event.getView().getTopInventory().getHolder() instanceof RankMenu)) return;
			event.setCancelled(true);
			RankMenu menu = (RankMenu)event.getView().getTopInventory().getHolder();
			int slot = event.getRawSlot();
			if(!menu.actions.containsKey(slot) || !(event.getWhoClicked() instanceof Player)) return;
			Player player = (Player)event.getWhoClicked();
			String action = menu.actions.get(slot);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) return;
				player.closeInventory();
				if(CANCEL.equals(action)) return;
				menu.choose.accept(REAL_RANK.equals(action) ? null : action);
			});
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onDrag(InventoryDragEvent event) {
			if(event.getView().getTopInventory().getHolder() instanceof RankMenu) event.setCancelled(true);
		}

	}

}
