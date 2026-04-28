package com.ron.lobby.queue;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

class PrivateLobby {
    final String code;
    final UUID host;
    final Set<UUID> players = new LinkedHashSet<>();
    boolean countdownActive = false;
    int countdownTask = -1;
    int countdownSeconds;

    PrivateLobby(String code, UUID host, int initialCountdownSeconds) {
        this.code = code;
        this.host = host;
        this.countdownSeconds = initialCountdownSeconds;
    }
}
