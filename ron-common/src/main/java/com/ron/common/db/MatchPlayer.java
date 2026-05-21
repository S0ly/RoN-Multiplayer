package com.ron.common.db;

public record MatchPlayer(
        String matchId,
        String uuid,
        String name,
        boolean wasWinner,
        int pointDelta
) {}
