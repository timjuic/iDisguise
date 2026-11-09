package de.luisagrether.idisguise.player;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

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
 * The tag list: "no tag" and the tags, 45 per page; bottom row with previous page, back (or cancel)
 * and next page. Every click is cancelled; the choice runs on the next tick, only while this
 * menu is still open.
 */
final class TagMenu implements InventoryHolder {

	static final String BACK = "\u0000back";
	static final String NONE = "\u0000none";
	static final String PREVIOUS = "\u0000previous";
	static final String NEXT = "\u0000next";
	static final int PER_PAGE = 45;

	private final Inventory inventory;
	private final Map<Integer, String> actions = new HashMap<>();
	private final Consumer<String> choose;

	TagMenu(String title, java.util.List<RankMenu.Entry> tags, ItemStack back, ItemStack previous, ItemStack next,
			Consumer<String> choose) {
		this.choose = choose;
		this.inventory = Bukkit.createInventory(this, 54, title.length() > 32 ? title.substring(0, 32) : title);
		int slot = 0;
		for(RankMenu.Entry entry : tags) {
			if(slot >= PER_PAGE) break;
			put(slot++, entry.item, entry.value);
		}
		if(previous != null) put(45, previous, PREVIOUS);
		put(49, back, BACK);
		if(next != null) put(53, next, NEXT);
	}

	private void put(int slot, ItemStack item, String action) {
		inventory.setItem(slot, item);
		actions.put(slot, action);
	}

	void open(Player player) {
		player.openInventory(inventory);
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	static final class Clicks implements Listener {

		private final Plugin plugin;

		Clicks(Plugin plugin) {
			this.plugin = plugin;
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onClick(InventoryClickEvent event) {
			if(!(event.getView().getTopInventory().getHolder() instanceof TagMenu)) return;
			event.setCancelled(true);
			TagMenu menu = (TagMenu)event.getView().getTopInventory().getHolder();
			int slot = event.getRawSlot();
			if(!menu.actions.containsKey(slot) || !(event.getWhoClicked() instanceof Player)) return;
			Player player = (Player)event.getWhoClicked();
			String action = menu.actions.get(slot);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) return;
				player.closeInventory();
				menu.choose.accept(action);
			});
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onDrag(InventoryDragEvent event) {
			if(event.getView().getTopInventory().getHolder() instanceof TagMenu) event.setCancelled(true);
		}

	}

}
