package de.luisagrether.idisguise.player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * One player disguise: who the player really is and who other players see.
 * Saved in player-disguises.yml so it survives quitting and restarts.
 * Read from packet threads, so the runtime fields are volatile / concurrent.
 */
public final class PlayerDisguise {

	private final UUID uuid;
	private final String realName;
	private final String fakeName;
	// null = default rank from player-disguise.yml
	private final String rank;
	// null when no skin was found (players then see a default skin, never the real one)
	private final String skinValue;
	private final String skinSignature;

	private final Pattern realNamePattern;

	// True while a staff member is disguised as a player who is online right now: the staff
	// member's tab entry is then hidden so the name is only once in the tab list.
	private volatile boolean hiddenFromTab;
	// Viewers whose client currently has no tab entry for this player (see hiddenFromTab).
	private final Set<UUID> removedFor = ConcurrentHashMap.newKeySet();
	// Display name the player had before the disguise (EssentialsX nickname etc.).
	private volatile String previousDisplayName;
	// Set while the player is online: staff or player settings from player-disguise.yml.
	private volatile PlayerDisguiseSettings.Profile profile;
	private volatile boolean staff;
	// Real rank of the player whose name this is, while that player is online (then both must
	// look the same: 1.8 clients pick the nametag rank by name).
	private volatile String ownerRank;

	public PlayerDisguise(UUID uuid, String realName, String fakeName, String rank, String skinValue, String skinSignature) {
		this.uuid = uuid;
		this.realName = realName;
		this.fakeName = fakeName;
		this.rank = rank;
		this.skinValue = skinValue;
		this.skinSignature = skinSignature;
		this.realNamePattern = NameReplacer.wordPattern(realName);
	}

	public UUID getUniqueId() { return uuid; }
	public String getRealName() { return realName; }
	public String getFakeName() { return fakeName; }
	public String getRank() { return rank; }
	public String getSkinValue() { return skinValue; }
	public String getSkinSignature() { return skinSignature; }
	public boolean hasSkin() { return skinValue != null && !skinValue.isEmpty(); }
	Pattern getRealNamePattern() { return realNamePattern; }

	boolean isHiddenFromTab() { return hiddenFromTab; }
	void setHiddenFromTab(boolean hiddenFromTab) { this.hiddenFromTab = hiddenFromTab; }
	Set<UUID> getRemovedFor() { return removedFor; }

	String getPreviousDisplayName() { return previousDisplayName; }
	void setPreviousDisplayName(String previousDisplayName) { this.previousDisplayName = previousDisplayName; }

	/** null while the player is offline. */
	PlayerDisguiseSettings.Profile getProfile() { return profile; }
	boolean isStaff() { return staff; }
	void setOnline(PlayerDisguiseSettings.Profile profile, boolean staff) {
		this.profile = profile;
		this.staff = staff;
	}
	String getOwnerRank() { return ownerRank; }
	void setOwnerRank(String ownerRank) { this.ownerRank = ownerRank; }

	void setOffline() {
		this.profile = null;
		this.hiddenFromTab = false;
		this.removedFor.clear();
	}

}
