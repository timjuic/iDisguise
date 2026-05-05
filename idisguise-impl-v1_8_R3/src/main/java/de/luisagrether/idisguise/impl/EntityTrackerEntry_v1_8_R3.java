package de.luisagrether.idisguise.impl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Set;

import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EntityTracker;
import net.minecraft.server.v1_8_R3.EntityTrackerEntry;
import net.minecraft.server.v1_8_R3.WorldServer;

public class EntityTrackerEntry_v1_8_R3 extends EntityTrackerEntry {
    
    private static Field EntityTrackerEntry_u = null;
    private static Field EntityTracker_trackerSet = null;
    static {
        try {
            EntityTrackerEntry_u = EntityTrackerEntry.class.getDeclaredField("u");
            EntityTrackerEntry_u.setAccessible(true);
            EntityTracker_trackerSet = EntityTracker.class.getDeclaredField("c");
            EntityTracker_trackerSet.setAccessible(true);
        } catch(Exception e) {}
    }

    // targetId == -1 means "intercept updates to ALL observers" (used to hide a player entity
    // from everyone else while keeping their canSee/online state intact).
    private static final int TARGET_ALL = -1;

    private final int targetId;
    private boolean intercept;

    EntityTrackerEntry_v1_8_R3(EntityTrackerEntry original, int targetId) throws IllegalAccessException {
        super(
            original.tracker,
            original.b,
            original.c,
            EntityTrackerEntry_u.getBoolean(original)
        );
        this.targetId = targetId;
        this.intercept = false;
    }

    public void toggleIntercept(boolean intercept) {
        this.intercept = intercept;
    }

    @Override
    public void updatePlayer(EntityPlayer entityPlayer) {
        if(intercept && (targetId == TARGET_ALL || entityPlayer.getId() == targetId)) {
            return;
        }
        super.updatePlayer(entityPlayer);
    }

    public static void inject(Entity entity, Player player) throws IllegalAccessException {
        EntityTracker tracker = ((WorldServer)((CraftEntity)entity).getHandle().world).tracker;
        EntityTrackerEntry original = tracker.trackedEntities.get(entity.getEntityId());
        if(original instanceof EntityTrackerEntry_v1_8_R3) {
            return;
        } else {
            tracker.trackedEntities.d(entity.getEntityId());
            Set<EntityTrackerEntry> entrySet = (Set<EntityTrackerEntry>)EntityTracker_trackerSet.get(tracker);
            entrySet.remove(original);
            EntityTrackerEntry_v1_8_R3 intruder = new EntityTrackerEntry_v1_8_R3(original, player.getEntityId());
            tracker.trackedEntities.a(entity.getEntityId(), intruder);
            entrySet.add(intruder);
            for(EntityPlayer observer : new ArrayList<>(original.trackedPlayers)) {
                original.clear(observer);
                intruder.updatePlayer(observer);
            }
        }
    }

    public static void toggleIntercept(Entity entity, Player player, boolean intercept) {
        EntityTracker tracker = ((WorldServer)((CraftEntity)entity).getHandle().world).tracker;
        EntityTrackerEntry entry = tracker.trackedEntities.get(entity.getEntityId());
        if(!(entry instanceof EntityTrackerEntry_v1_8_R3)) {
            throw new IllegalStateException();
        } else {
            EntityTrackerEntry_v1_8_R3 intruder = (EntityTrackerEntry_v1_8_R3)entry;
            if(intruder.intercept == intercept) {
                return;
            } else {
                intruder.toggleIntercept(intercept);
                if(intercept) {
                    intruder.clear(((CraftPlayer)player).getHandle());
                } else {
                    intruder.updatePlayer(((CraftPlayer)player).getHandle());
                }
            }
        }
    }

    // Inject with targetId = -1 so updatePlayer is suppressed for every observer when intercept is on.
    // Used for hiding a player entity from all other players without flipping canSee.
    public static void injectAll(Entity entity) throws IllegalAccessException {
        EntityTracker tracker = ((WorldServer)((CraftEntity)entity).getHandle().world).tracker;
        EntityTrackerEntry original = tracker.trackedEntities.get(entity.getEntityId());
        if(original instanceof EntityTrackerEntry_v1_8_R3) {
            return;
        } else {
            tracker.trackedEntities.d(entity.getEntityId());
            Set<EntityTrackerEntry> entrySet = (Set<EntityTrackerEntry>)EntityTracker_trackerSet.get(tracker);
            entrySet.remove(original);
            EntityTrackerEntry_v1_8_R3 intruder = new EntityTrackerEntry_v1_8_R3(original, TARGET_ALL);
            tracker.trackedEntities.a(entity.getEntityId(), intruder);
            entrySet.add(intruder);
            for(EntityPlayer observer : new ArrayList<>(original.trackedPlayers)) {
                original.clear(observer);
                intruder.updatePlayer(observer);
            }
        }
    }

    public static void toggleInterceptAll(Entity entity, boolean intercept) {
        EntityTracker tracker = ((WorldServer)((CraftEntity)entity).getHandle().world).tracker;
        EntityTrackerEntry entry = tracker.trackedEntities.get(entity.getEntityId());
        if(!(entry instanceof EntityTrackerEntry_v1_8_R3)) {
            throw new IllegalStateException();
        }
        EntityTrackerEntry_v1_8_R3 intruder = (EntityTrackerEntry_v1_8_R3)entry;
        if(intruder.intercept == intercept) {
            return;
        }
        intruder.toggleIntercept(intercept);
        for(EntityPlayer observer : new ArrayList<>(intruder.trackedPlayers)) {
            if(intercept) {
                intruder.clear(observer);
            } else {
                intruder.updatePlayer(observer);
            }
        }
    }

}
