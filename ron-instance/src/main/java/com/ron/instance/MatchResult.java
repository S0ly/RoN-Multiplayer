package com.ron.instance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MatchResult {

    public record PlayerResult(String uuid, String name, int pointsBefore, int pointsChange) {}

    private static MatchResult current = null;

    // Player scores forwarded from lobby via RCON, keyed by UUID
    private static final Map<String, Integer> playerScores = new ConcurrentHashMap<>();

    private final List<PlayerResult> winners;
    private final List<PlayerResult> losers;

    public MatchResult(List<PlayerResult> winners, List<PlayerResult> losers) {
        this.winners = winners;
        this.losers = losers;
    }

    public List<PlayerResult> getWinners() { return winners; }
    public List<PlayerResult> getLosers() { return losers; }

    public static MatchResult getCurrent() { return current; }
    public static void setCurrent(MatchResult result) { current = result; }

    public static Map<String, Integer> getPlayerScores() { return playerScores; }

    public static int getPlayerScore(String uuid) {
        return playerScores.getOrDefault(uuid, 0);
    }

    public static void reset() {
        current = null;
        playerScores.clear();
    }
}
