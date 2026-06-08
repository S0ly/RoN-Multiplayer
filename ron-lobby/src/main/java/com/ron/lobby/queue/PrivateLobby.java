package com.ron.lobby.queue;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

class PrivateLobby {
    final String code;
    final UUID host;
    final Set<UUID> players = new LinkedHashSet<>();

    PrivateLobby(String code, UUID host) {
        this.code = code;
        this.host = host;
    }
}
