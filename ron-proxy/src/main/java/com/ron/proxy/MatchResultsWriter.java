package com.ron.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ron.common.db.PlayerStats;
import com.ron.common.db.PlayerStatsDAO;
import com.ron.proxy.InstanceTracker.MatchResultData;
import com.ron.proxy.InstanceTracker.PlayerResultData;
import com.ron.proxy.sync.RankSyncService;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless helpers for parsing match-result JSON from RoN's RCON status payload
 * and writing the resulting wins/losses to the player stats DB.
 */
final class MatchResultsWriter {

    private MatchResultsWriter() {}

    static MatchResultData parse(JsonObject json) {
        List<PlayerResultData> winners = new ArrayList<>();
        List<PlayerResultData> losers = new ArrayList<>();

        if (json.has("winners")) {
            for (JsonElement el : json.getAsJsonArray("winners")) {
                JsonObject p = el.getAsJsonObject();
                winners.add(new PlayerResultData(
                    p.get("uuid").getAsString(), p.get("name").getAsString()
                ));
            }
        }
        if (json.has("losers")) {
            for (JsonElement el : json.getAsJsonArray("losers")) {
                JsonObject p = el.getAsJsonObject();
                losers.add(new PlayerResultData(
                    p.get("uuid").getAsString(), p.get("name").getAsString()
                ));
            }
        }
        return new MatchResultData(winners, losers);
    }

    static void write(PlayerStatsDAO statsDAO, MatchResultData matchResult, RankSyncService syncService, Logger logger) {
        try {
            int winnerTotal = 0, winnerCount = 0;
            int loserTotal = 0, loserCount = 0;

            for (PlayerResultData p : matchResult.winners()) {
                PlayerStats stats = statsDAO.getOrCreate(p.uuid(), p.name());
                winnerTotal += stats.points;
                winnerCount++;
            }
            for (PlayerResultData p : matchResult.losers()) {
                PlayerStats stats = statsDAO.getOrCreate(p.uuid(), p.name());
                loserTotal += stats.points;
                loserCount++;
            }

            int winnerAvg = winnerCount > 0 ? winnerTotal / winnerCount : 0;
            int loserAvg = loserCount > 0 ? loserTotal / loserCount : 0;

            Set<String> touched = new HashSet<>();
            for (PlayerResultData p : matchResult.winners()) {
                statsDAO.recordWin(p.uuid(), p.name(), loserAvg);
                touched.add(p.uuid());
            }
            for (PlayerResultData p : matchResult.losers()) {
                statsDAO.recordLoss(p.uuid(), p.name(), winnerAvg);
                touched.add(p.uuid());
            }

            logger.info("Scores updated: {} winners, {} losers", winnerCount, loserCount);
            if (syncService != null && !touched.isEmpty()) {
                syncService.markDirty(touched);
            }
        } catch (Exception e) {
            logger.error("Failed to write match results to database", e);
        }
    }
}
