package com.ron.proxy.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.ron.common.db.PlayerStatsDAO;
import com.ron.common.sync.PlayerSyncRecord;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class RankSyncServer {

    private final int port;
    private final String token;
    private final PlayerStatsDAO statsDAO;
    private final Logger logger;
    private HttpServer http;

    public RankSyncServer(int port, String token, PlayerStatsDAO statsDAO, Logger logger) {
        this.port = port;
        this.token = token;
        this.statsDAO = statsDAO;
        this.logger = logger;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/sync/players", this::handle);
        http.setExecutor(null);
        http.start();
        logger.info("RankSync HTTP listener started on port {}", port);
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            logger.info("RankSync HTTP listener stopped");
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "");
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + token)) {
                respond(ex, 401, "");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (Exception e) {
                respond(ex, 400, "");
                return;
            }
            if (!parsed.isJsonArray()) {
                respond(ex, 400, "");
                return;
            }
            JsonArray arr = parsed.getAsJsonArray();
            int applied = 0;
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                var obj = el.getAsJsonObject();
                try {
                    PlayerSyncRecord r = new PlayerSyncRecord(
                        obj.get("uuid").getAsString(),
                        obj.get("name").getAsString(),
                        obj.get("points").getAsInt(),
                        obj.get("wins").getAsInt(),
                        obj.get("losses").getAsInt(),
                        obj.get("updatedAt").getAsLong()
                    );
                    if (statsDAO.upsertFromSync(r)) applied++;
                } catch (Exception e) {
                    logger.warn("RankSync: skipped malformed record: {}", e.getMessage());
                }
            }
            logger.info("RankSync received {} records, applied {}", arr.size(), applied);
            respond(ex, 204, "");
        } catch (Exception e) {
            logger.error("RankSync server error", e);
            respond(ex, 500, "");
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}
