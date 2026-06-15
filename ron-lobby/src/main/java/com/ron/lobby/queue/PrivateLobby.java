package com.ron.lobby.queue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class PrivateLobby {
    final String code;
    final UUID host;
    final Set<UUID> players = new LinkedHashSet<>();

    // Host-controlled match setup (chosen via the lobby menu, no voting).
    String selectedMapFolder;
    String selectedMapName;
    String selectedMode;
    int selectedModePlayers;
    Boolean allianceLock = Boolean.TRUE; // locked by default, host may unlock for FFA
    Boolean fogOfWar = Boolean.FALSE;    // disabled by default, host may enable
    // Maps + compatible modes fetched for the current lobby size (host browsing).
    List<MatchQueue.MapOption> cachedMapOptions = List.of();

    PrivateLobby(String code, UUID host) {
        this.code = code;
        this.host = host;
    }

    void clearSelection() {
        selectedMapFolder = null;
        selectedMapName = null;
        selectedMode = null;
        selectedModePlayers = 0;
        allianceLock = Boolean.TRUE;
        fogOfWar = Boolean.FALSE;
    }
}
