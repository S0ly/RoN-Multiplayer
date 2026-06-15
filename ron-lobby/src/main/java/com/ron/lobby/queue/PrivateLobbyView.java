package com.ron.lobby.queue;

import java.util.List;
import java.util.UUID;

/** Read-only snapshot of a private lobby's state for UI consumption. */
public record PrivateLobbyView(
        String code,
        UUID host,
        List<UUID> members,
        String selectedMapFolder,
        String selectedMapName,
        String selectedMode,
        int selectedModePlayers,
        boolean allianceLock,
        boolean fogOfWar,
        List<MatchQueue.MapOption> mapOptions) {}
