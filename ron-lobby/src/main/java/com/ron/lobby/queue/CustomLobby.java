package com.ron.lobby.queue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class CustomLobby {
    final String code;
    final UUID host;
    final Set<UUID> players = new LinkedHashSet<>();

    // Visibility: private (join by code only) or public (also joinable by clicking
    // the host's head in the Custom Lobby menu). Private by default.
    boolean isPublic = false;

    // Host-controlled match setup (chosen via the lobby menu, no voting).
    String selectedMapFolder;
    String selectedMapName;
    String selectedMode;
    int selectedModePlayers;
    boolean allianceLock = true; // locked by default, host may unlock for FFA
    boolean fogOfWar = false;    // disabled by default, host may enable
    // Maps + compatible modes fetched for the current lobby size (host browsing).
    List<MatchQueue.MapOption> cachedMapOptions = List.of();

    CustomLobby(String code, UUID host) {
        this.code = code;
        this.host = host;
    }

    void clearSelection() {
        selectedMapFolder = null;
        selectedMapName = null;
        selectedMode = null;
        selectedModePlayers = 0;
        allianceLock = true;
        fogOfWar = false;
    }
}
