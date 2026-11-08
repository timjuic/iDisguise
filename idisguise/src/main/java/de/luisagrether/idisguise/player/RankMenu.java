package de.luisagrether.idisguise.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
 * The rank menu after /disguise <name>: one dye per rank, coloured like the rank.
 * Every click is cancelled; the choice runs on the next tick, only while this menu is still open.
 */
final class RankMenu implements InventoryHolder {

	static final class Option {
		// what is passed on as the rank (null = the player's real rank, NO_RANK = no rank)
		final String value;
		final String prefix;
		final String title;
		final String lore;

		Option(String value, String prefix, String title, String lore) {
			this.value = value;
			this.prefix = prefix;
			this.title = title;
			this.lore = lore;
		}
	}

	private final Inventory inventory;
	private final List<Option> options;
	private final Consumer<String> choose;

	RankMenu(String title, List<Option> options, Consumer<String> choose) {
		this.options = options;
		this.choose = choose;
		int rows = Math.min(6, Math.max(1, (options.size() + 8) / 9));
		// 1.8 menu titles are at most 32 characters.
		this.inventory = Bukkit.createInventory(this, rows * 9, title.length() > 32 ? title.substring(0, 32) : title);
		for(int i = 0; i < options.size() && i < rows * 9; i++) {
			inventory.setItem(i, item(options.get(i)));
		}
	}

	void open(Player player) {
		player.openInventory(inventory);
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}

	@SuppressWarnings("deprecation")
	private static ItemStack item(Option option) {
		Material dye = Material.matchMaterial("INK_SACK");
		if(dye == null) dye = Material.PAPER;
		ItemStack item = new ItemStack(dye, 1, dyeColor(option.prefix));
		ItemMeta meta = item.getItemMeta();
		meta.setDisplayName(option.title);
		meta.setLore(Collections.singletonList(option.lore));
		item.setItemMeta(meta);
		return item;
	}

	/**
	 * Dye colour from the rank's prefix: the first real colour in it (grey brackets like "&8[" are
	 * skipped), e.g. "&8[&e&lGold&8]" -> yellow.
	 */
	static short dyeColor(String prefix) {
		char found = 0;
		char fallback = 0;
		if(prefix != null) {
			for(int i = 0; i + 1 < prefix.length(); i++) {
				char marker = prefix.charAt(i);
				if(marker != '&' && marker != ChatColor.COLOR_CHAR) continue;
				char code = Character.toLowerCase(prefix.charAt(i + 1));
				if("0123456789abcdef".indexOf(code) < 0) continue;
				if(fallback == 0) fallback = code;
				if("078f".indexOf(code) < 0) {
					found = code;
					break;
				}
			}
		}
		char code = found != 0 ? found : fallback;
		switch(code) {
			case '0': return 0;   // ink sac
			case '1': return 4;   // lapis
			case '2': return 2;   // green
			case '3': return 6;   // cyan
			case '4': return 1;   // red
			case '5': return 5;   // purple
			case '6': return 14;  // orange
			case '7': return 7;   // light gray
			case '8': return 8;   // gray
			case '9': return 4;   // lapis (blue)
			case 'a': return 10;  // lime
			case 'b': return 12;  // light blue
			case 'c': return 1;   // red
			case 'd': return 9;   // pink
			case 'e': return 11;  // yellow
			case 'f': return 15;  // white
			default: return 8;
		}
	}

	/** Cancels every click in the menu and runs the chosen option. Registered once. */
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
			if(slot < 0 || slot >= menu.inventory.getSize() || slot >= menu.options.size()) return;
			if(!(event.getWhoClicked() instanceof Player)) return;
			Player player = (Player)event.getWhoClicked();
			String value = menu.options.get(slot).value;
			Bukkit.getScheduler().runTask(plugin, () -> {
				if(!player.isOnline() || !(player.getOpenInventory().getTopInventory().getHolder() == menu)) return;
				player.closeInventory();
				menu.choose.accept(value);
			});
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onDrag(InventoryDragEvent event) {
			if(event.getView().getTopInventory().getHolder() instanceof RankMenu) event.setCancelled(true);
		}

	}

	static List<Option> copy(List<Option> options) {
		return new ArrayList<>(options);
	}

}
