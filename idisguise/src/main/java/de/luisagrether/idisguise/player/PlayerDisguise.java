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
	// The ID other players see the disguise under (never the ID of a real player).
	private final UUID fakeUuid;

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
	// Put on by someone else (/odisguise): no own permission needed, ends when the player leaves.
	private volatile boolean givenByStaff;
	// DeluxeTags tag of the player whose name this is, shown in chat instead of the own tag.
	private volatile String chatTag;
	// DeluxeTags tag picked for the disguise (identifier, "" = no tag), or null = the default above.
	private volatile String chosenTag;
	// The real player with this name (has played here), or null for a made-up name.
	private volatile UUID ownerUuid;

	public PlayerDisguise(UUID uuid, String realName, String fakeName, String rank, String skinValue, String skinSignature) {
		this.uuid = uuid;
		this.realName = realName;
		this.fakeName = fakeName;
		this.rank = rank;
		this.skinValue = skinValue;
		this.skinSignature = skinSignature;
		this.realNamePattern = NameReplacer.wordPattern(realName);
		this.fakeUuid = UUID.nameUUIDFromBytes(("iDisguise:" + uuid + ":" + fakeName.toLowerCase(java.util.Locale.ENGLISH))
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	public UUID getUniqueId() { return uuid; }
	public UUID getFakeUniqueId() { return fakeUuid; }
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
	public String getChatTag() { return chatTag; }
	String getChosenTag() { return chosenTag; }
	void setChosenTag(String chosenTag) { this.chosenTag = chosenTag; }
	UUID getOwnerUuid() { return ownerUuid; }
	void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }
	void setChatTag(String chatTag) { this.chatTag = chatTag; }
	boolean isGivenByStaff() { return givenByStaff; }
	void setGivenByStaff(boolean givenByStaff) { this.givenByStaff = givenByStaff; }
	String getOwnerRank() { return ownerRank; }
	void setOwnerRank(String ownerRank) { this.ownerRank = ownerRank; }

	void setOffline() {
		this.profile = null;
		this.hiddenFromTab = false;
		this.removedFor.clear();
	}

}
