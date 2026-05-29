package com.ron.proxy.sync;

import com.ron.common.db.PlayerStats;
import com.ron.common.db.PlayerStatsDAO;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RankSyncService {

    private final RankSyncConfig config;
    private final PlayerStatsDAO statsDAO;
    private final Logger logger;
    private final RankSyncServer server;
    private final Map<String, RankSyncClient> clients = new HashMap<>();
    private final Map<String, Long> peerCursors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rank-sync");
        t.setDaemon(true);
        return t;
    });

    public RankSyncService(RankSyncConfig config, PlayerStatsDAO statsDAO, Logger logger) {
        this.config = config;
        this.statsDAO = statsDAO;
        this.logger = logger;
        this.server = new RankSyncServer(config.listenPort, config.listenToken, statsDAO, logger);
        long floor = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(config.syncIntervalMinutes);
        for (RankSyncConfig.Peer peer : config.peers) {
            clients.put(peer.name(), new RankSyncClient(peer, logger));
            peerCursors.put(peer.name(), floor);
        }
    }

    public void start() {
        try {
            server.start();
        } catch (Exception e) {
            logger.error("RankSync: failed to start HTTP listener — sync disabled", e);
            return;
        }
        long interval = TimeUnit.MINUTES.toSeconds(config.syncIntervalMinutes);
        scheduler.scheduleAtFixedRate(this::pushAll, interval, interval, TimeUnit.SECONDS);
        logger.info("RankSync started: {} peers, interval {}m", clients.size(), config.syncIntervalMinutes);
    }

    public void shutdown() {
        scheduler.shutdownNow();
        server.stop();
    }

    public void markDirty(Collection<String> uuids) {
        if (uuids.isEmpty() || clients.isEmpty()) return;
        scheduler.execute(() -> pushUuids(uuids));
    }

    private void pushUuids(Collection<String> uuids) {
        try {
            List<PlayerStats> rows = statsDAO.getByUuids(uuids);
            if (rows.isEmpty()) return;
            for (var entry : clients.entrySet()) {
                RankSyncClient client = entry.getValue();
                if (client.push(rows)) {
                    long maxTs = 0;
                    for (PlayerStats r : rows) if (r.lastPlayed > maxTs) maxTs = r.lastPlayed;
                    peerCursors.merge(entry.getKey(), maxTs, Math::max);
                }
            }
        } catch (Exception e) {
            logger.error("RankSync: immediate push failed", e);
        }
    }

    private void pushAll() {
        if (clients.isEmpty()) return;
        for (var entry : clients.entrySet()) {
            String peerName = entry.getKey();
            RankSyncClient client = entry.getValue();
            long cursor = peerCursors.getOrDefault(peerName, 0L);
            try {
                List<PlayerStats> rows = statsDAO.getUpdatedSince(cursor);
                if (rows.isEmpty()) continue;
                if (client.push(rows)) {
                    long maxTs = cursor;
                    for (PlayerStats r : rows) if (r.lastPlayed > maxTs) maxTs = r.lastPlayed;
                    peerCursors.put(peerName, maxTs);
                }
            } catch (Exception e) {
                logger.warn("RankSync: periodic push to '{}' failed: {}", peerName, e.getMessage());
            }
        }
    }
}
