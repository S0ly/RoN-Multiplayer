package com.ron.lobby.queue;

import java.util.List;
import java.util.UUID;

/** Read-only snapshot of a custom lobby's state for UI consumption. */
public record CustomLobbyView(
        String code,
        UUID host,
        List<UUID> members,
        boolean isPublic,
        String selectedMapFolder,
        String selectedMapName,
        String selectedMode,
        int selectedModePlayers,
        boolean allianceLock,
        boolean fogOfWar,
        List<MatchQueue.MapOption> mapOptions) {}
