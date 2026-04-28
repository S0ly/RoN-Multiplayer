package com.ron.proxy;

import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveMatchTracker {

    private final Logger logger;

    // playerUUID -> instanceName
    private final Map<UUID, String> activePlayers = new ConcurrentHashMap<>();
    // instanceName -> set of player UUIDs
    private final Map<String, Set<UUID>> matchPlayers = new ConcurrentHashMap<>();

    public ActiveMatchTracker(Logger logger) {
        this.logger = logger;
    }

    public void registerMatch(String instance, Set<UUID> players) {
        Set<UUID> set = ConcurrentHashMap.newKeySet();
        set.addAll(players);
        matchPlayers.put(instance, set);
        for (UUID uuid : players) {
            activePlayers.put(uuid, instance);
        }
        logger.info("Registered {} players in match on {}", players.size(), instance);
    }

    public void clearMatch(String instance) {
        Set<UUID> players = matchPlayers.remove(instance);
        if (players != null) {
            for (UUID uuid : players) {
                activePlayers.remove(uuid);
            }
            logger.info("Cleared match on {} ({} players)", instance, players.size());
        }
    }

    public void removePlayer(UUID player) {
        String instance = activePlayers.remove(player);
        if (instance != null) {
            Set<UUID> players = matchPlayers.get(instance);
            if (players != null) {
                players.remove(player);
                if (players.isEmpty()) {
                    matchPlayers.remove(instance);
                }
            }
            logger.info("Removed player {} from match on {}", player, instance);
        }
    }

    public String getActiveInstance(UUID player) {
        return activePlayers.get(player);
    }

    public boolean isInMatch(UUID player) {
        return activePlayers.containsKey(player);
    }
}
