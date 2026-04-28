package com.ron.instance;

import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.startpos.StartPos;
import com.solegendary.reignofnether.startpos.StartPosClientboundPacket;
import com.solegendary.reignofnether.startpos.StartPosServerEvents;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists each participant's StartPos slot + faction so it can be restored
 * if they disconnect and reconnect during the readying or running phase.
 * RoN clears the slot on logout, so we re-apply on login.
 */
final class SelectionStore {

    private record SavedSelection(int startPosIndex, Faction faction) {}

    private final Map<UUID, SavedSelection> saved = new ConcurrentHashMap<>();

    void capture(UUID uuid, String name) {
        for (int i = 0; i < StartPosServerEvents.startPoses.size(); i++) {
            StartPos pos = StartPosServerEvents.startPoses.get(i);
            if (name.equals(pos.playerName) && pos.faction != Faction.NONE) {
                saved.put(uuid, new SavedSelection(i, pos.faction));
                RonInstance.LOGGER.info("Saved selection for {}: pos={}, faction={}", name, i, pos.faction);
                return;
            }
        }
    }

    void restore(UUID uuid, String name) {
        SavedSelection s = saved.get(uuid);
        if (s == null) return;
        if (s.startPosIndex >= StartPosServerEvents.startPoses.size()) return;

        StartPos pos = StartPosServerEvents.startPoses.get(s.startPosIndex);
        if (pos.playerName.isEmpty() || pos.playerName.equals(name)) {
            pos.playerName = name;
            pos.faction = s.faction;
            StartPosClientboundPacket.reservePos(pos.pos, s.faction, name);
            RonInstance.LOGGER.info("Restored selection for {}: pos={}, faction={}", name, s.startPosIndex, s.faction);
        }
    }

    /**
     * Refresh the saved selection for every known participant. Cheap to call
     * each tick — only writes when a slot is occupied with a faction.
     */
    void refreshAll() {
        for (UUID uuid : PlayerTracker.getAllParticipants()) {
            String name = PlayerTracker.getName(uuid);
            if (name == null) continue;
            for (int i = 0; i < StartPosServerEvents.startPoses.size(); i++) {
                StartPos pos = StartPosServerEvents.startPoses.get(i);
                if (name.equals(pos.playerName) && pos.faction != Faction.NONE) {
                    saved.put(uuid, new SavedSelection(i, pos.faction));
                    break;
                }
            }
        }
    }

    void clear() {
        saved.clear();
    }
}
