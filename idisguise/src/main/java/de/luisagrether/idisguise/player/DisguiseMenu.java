package de.luisagrether.idisguise.player;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Base of the rank and tag menus: both are 6 rows with the same top row (frame, head in the middle)
 * and the same bottom row, so their buttons sit in exactly the same places.
 *   row 1     frame, head (slot 4)
 *   rows 2-5  ranks / tags (36 slots)
 *   row 6     invisibility (45), real rank / previous page (48), cancel (49), next page (50),
 *             tags / back to ranks (53)
 * Every click is cancelled; the choice runs on the next tick, only while this menu is still open.
 * The next menu opens straight over this one (never closed first): the 1.8 client keeps the cursor.
 */
abstract class DisguiseMenu implements InventoryHolder {

	/** What a click does. Returns true when the menu stays open (it changed in place). */
	interface Choice {
		boolean choose(DisguiseMenu menu, String action);
	}

	static final int SIZE = 54;
	static final int FIRST = 9;
	static final int PER_PAGE = 36;
	static final int HEADER = 4;
	static final int INVIS = 45;
	static final int PREVIOUS = 48;
	static final int REAL_RANK = 48;
	static final int CANCEL = 49;
	static final int NEXT = 50;
	static final int SWITCH = 53;

	// Marker for "real rank": the map can't tell a null value from a missing slot.
	private static final String REAL_RANK_ACTION = "\u0000real-rank";

	private final Inventory inventory;
	private final Map<Integer, String> actions = new HashMap<>();
	private final Choice choice;

	DisguiseMenu(String title, ItemStack header, ItemStack frame, Choice choice) {
		this.choice = choice;
		// 1.8 menu titles are at most 32 characters.
		this.inventory = Bukkit.createInventory(this, SIZE, title.length() > 32 ? title.substring(0, 32) : title);
		for(int slot = 0; slot < 9; slot++) inventory.setItem(slot, frame);
		inventory.setItem(HEADER, header);
	}

	/** A clickable item. action null = the real rank. */
	final void put(int slot, ItemStack item, String action) {
		if(item == null) return;
		inventory.setItem(slot, item);
		actions.put(slot, action == null ? REAL_RANK_ACTION : action);
	}

	final void put(int slot, RankMenu.Entry entry) {
		if(entry != null) put(slot, entry.item, entry.value);
	}

	/** Changes an item of the open menu in place (the action of that slot stays). */
	final void replace(int slot, ItemStack item) {
		inventory.setItem(slot, item);
	}

	final void open(Player player) {
		player.openInventory(inventory);
	}

	@Override
	public final Inventory getInventory() {
		return inventory;
	}

	/** Cancels every click in the menus and runs the chosen entry. Registered once. */
	static final class Clicks implements Listener {

		private final Plugin plugin;

		Clicks(Plugin plugin) {
			this.plugin = plugin;
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onClick(InventoryClickEvent event) {
			if(!(event.getView().getTopInventory().getHolder() instanceof DisguiseMenu)) return;
			event.setCancelled(true);
			DisguiseMenu menu = (DisguiseMenu)event.getView().getTopInventory().getHolder();
			int slot = event.getRawSlot();
			if(!menu.actions.containsKey(slot) || !(event.getWhoClicked() instanceof Player)) return;
			Player player = (Player)event.getWhoClicked();
			String action = menu.actions.get(slot);
			// Changes in place (the invisibility switch): right now, so the item changes without a delay.
			if(RankMenu.INVIS_ACTION.equals(action)) {
				menu.choice.choose(menu, action);
				return;
			}
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) return;
				boolean stays = menu.choice.choose(menu, REAL_RANK_ACTION.equals(action) ? null : action);
				// Closed only when the choice is done (disguise, cancel) and no other menu opened over it.
				if(!stays && player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == menu) {
					player.closeInventory();
				}
			});
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onDrag(InventoryDragEvent event) {
			if(event.getView().getTopInventory().getHolder() instanceof DisguiseMenu) event.setCancelled(true);
		}

	}

}
