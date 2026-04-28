package com.ron.instance;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks match participants by UUID and name.
 * Populated when players join during MatchLifecycle setup.
 */
public class PlayerTracker {

    private static final Map<UUID, String> participants = new ConcurrentHashMap<>();

    public static void addParticipant(UUID uuid, String name) {
        participants.put(uuid, name);
    }

    public static boolean isParticipant(UUID uuid) {
        return participants.containsKey(uuid);
    }

    public static String getName(UUID uuid) {
        return participants.get(uuid);
    }

    public static UUID getUuidByName(String name) {
        for (var entry : participants.entrySet()) {
            if (name.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static Set<UUID> getAllParticipants() {
        return Set.copyOf(participants.keySet());
    }

    public static int getParticipantCount() {
        return participants.size();
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            // Update name mapping for known participants
            if (participants.containsKey(sp.getUUID())) {
                participants.put(sp.getUUID(), sp.getName().getString());
            }
        }
    }

    public static void reset() {
        participants.clear();
    }
}
