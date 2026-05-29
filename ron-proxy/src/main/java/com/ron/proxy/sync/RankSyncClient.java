package com.ron.proxy.sync;

import com.google.gson.Gson;
import com.ron.common.db.PlayerStats;
import com.ron.common.sync.PlayerSyncRecord;
import com.ron.proxy.RonProxy;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class RankSyncClient {

    private static final Gson GSON = RonProxy.GSON;

    private final RankSyncConfig.Peer peer;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public RankSyncClient(RankSyncConfig.Peer peer, Logger logger) {
        this.peer = peer;
        this.logger = logger;
    }

    public boolean push(Collection<PlayerStats> rows) {
        if (rows.isEmpty()) return true;
        List<PlayerSyncRecord> payload = new ArrayList<>(rows.size());
        for (PlayerStats s : rows) {
            payload.add(new PlayerSyncRecord(s.uuid, s.name, s.points, s.wins, s.losses, s.lastPlayed));
        }
        String body = GSON.toJson(payload);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(peer.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + peer.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                logger.info("RankSync pushed {} rows to peer '{}' ({})", rows.size(), peer.name(), code);
                return true;
            }
            logger.warn("RankSync push to peer '{}' returned {}: {}", peer.name(), code, resp.body());
            return false;
        } catch (Exception e) {
            logger.warn("RankSync push to peer '{}' failed: {}", peer.name(), e.getMessage());
            return false;
        }
    }
}
