package de.luisagrether.idisguise.player;

import java.util.List;

import org.bukkit.inventory.ItemStack;

/**
 * The tag list: same top and bottom rows as the rank menu (see DisguiseMenu), "no tag" and the tags
 * in rows 2-5 (36 per page), page arrows in the bottom row.
 */
final class TagMenu extends DisguiseMenu {

	static final String BACK = "\u0000back";
	static final String NONE = "\u0000none";
	static final String PREVIOUS_ACTION = "\u0000previous";
	static final String NEXT_ACTION = "\u0000next";

	/** back: "back to ranks" (null when only tags can be picked). previous / next / invis may be null. */
	TagMenu(String title, ItemStack header, ItemStack frame, List<RankMenu.Entry> tags, ItemStack back, ItemStack cancel,
			ItemStack previous, ItemStack next, ItemStack invis, ItemStack perms, Choice choice) {
		super(title, header, frame, choice);
		for(int i = 0; i < tags.size() && i < PER_PAGE; i++) put(FIRST + i, tags.get(i));
		put(INVIS, invis, RankMenu.INVIS_ACTION);
		put(PLAYER_PERMS, perms, RankMenu.PERMS_ACTION);
		put(PREVIOUS, previous, PREVIOUS_ACTION);
		put(CANCEL, cancel, RankMenu.CANCEL_ACTION);
		put(NEXT, next, NEXT_ACTION);
		put(SWITCH, back, BACK);
	}

}
