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
			Entry realRank, Entry noRank, Entry cancel, Consumer<String> choose) {
		this.choose = choose;
		List<List<Entry>> rankRows = new ArrayList<>();
		addRows(rankRows, playerRanks);
		int firstStaffRow = -1;
		if(!staffRanks.isEmpty()) {
			rankRows.add(new ArrayList<>()); // the gap
			firstStaffRow = rankRows.size();
			addRows(rankRows, staffRanks);
		}
		int rows = Math.min(6, 1 + rankRows.size() + 1);
		// 1.8 menu titles are at most 32 characters.
		this.inventory = Bukkit.createInventory(this, rows * 9, title.length() > 32 ? title.substring(0, 32) : title);

		for(int slot = 0; slot < 9; slot++) inventory.setItem(slot, frame);
		inventory.setItem(4, header);

		for(int row = 0; row < rankRows.size() && row + 1 < rows - 1; row++) {
			List<Entry> entries = rankRows.get(row);
			int start = (row + 1) * 9 + (9 - entries.size()) / 2;
			for(int i = 0; i < entries.size(); i++) {
				put(start + i, entries.get(i));
			}
		}

		int bottom = (rows - 1) * 9;
		if(realRank != null) put(bottom + 2, realRank);
		put(bottom + 4, noRank);
		put(bottom + 6, cancel);
	}

	private static void addRows(List<List<Entry>> rows, List<Entry> entries) {
		for(int i = 0; i < entries.size(); i += 9) {
			rows.add(new ArrayList<>(entries.subList(i, Math.min(entries.size(), i + 9))));
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
