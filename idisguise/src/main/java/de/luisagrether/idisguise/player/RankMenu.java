package de.luisagrether.idisguise.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The rank menu after /disguise <name>: "no rank", the player ranks and then the staff ranks in one list
 * (rows 2-5), the bottom row as in every disguise menu (see DisguiseMenu).
 */
final class RankMenu extends DisguiseMenu {

	/** One clickable entry. value: the action passed on (null = real rank, NO_RANK = no rank). */
	static final class Entry {
		final String value;
		final ItemStack item;

		Entry(String value, ItemStack item) {
			this.value = value;
			this.item = item;
		}
	}

	static final String CANCEL_ACTION = "\u0000cancel";
	/** The tag button: opens the tag list. */
	static final String TAG = "\u0000tag";
	/** The invisibility button (staff): switches "also turn invisible" on / off. */
	static final String INVIS_ACTION = "\u0000invis";
	/** The "player permissions" button (staff): staff extras off while disguised, on / off. */
	static final String PERMS_ACTION = "\u0000player-permissions";

	/** ranks: in menu order. realRank, tag, invis may be null. */
	RankMenu(String title, ItemStack header, ItemStack frame, List<Entry> ranks, Entry realRank, Entry cancel, Entry tag,
			ItemStack invis, ItemStack perms, Choice choice) {
		super(title, header, frame, choice);
		if(ranks.size() > PER_PAGE) {
			org.bukkit.Bukkit.getLogger().warning("[iDisguise] The rank menu has more than " + PER_PAGE + " ranks; some are left out."
					+ " Use fewer ranks in player-disguise.yml.");
		}
		for(int i = 0; i < ranks.size() && i < PER_PAGE; i++) put(FIRST + i, ranks.get(i));
		put(INVIS, invis, INVIS_ACTION);
		put(PLAYER_PERMS, perms, PERMS_ACTION);
		put(REAL_RANK, realRank);
		put(CANCEL, cancel);
		put(SWITCH, tag);
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

}
