package com.ron.proxy.sync;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public final class RankSyncConfig {

    public record Peer(String name, String url, String token) {}

    public final boolean enabled;
    public final int listenPort;
    public final String listenToken;
    public final int syncIntervalMinutes;
    public final List<Peer> peers;

    private RankSyncConfig(boolean enabled, int listenPort, String listenToken, int syncIntervalMinutes, List<Peer> peers) {
        this.enabled = enabled;
        this.listenPort = listenPort;
        this.listenToken = listenToken;
        this.syncIntervalMinutes = syncIntervalMinutes;
        this.peers = peers;
    }

    public static RankSyncConfig disabled() {
        return new RankSyncConfig(false, 0, "", 10, List.of());
    }

    public static RankSyncConfig fromJson(JsonObject root) {
        if (root == null || !root.has("rankSync")) return disabled();
        JsonObject obj = root.getAsJsonObject("rankSync");
        boolean enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
        int port = obj.has("listenPort") ? obj.get("listenPort").getAsInt() : 25580;
        String token = obj.has("listenToken") ? obj.get("listenToken").getAsString() : "";
        int interval = obj.has("syncIntervalMinutes") ? obj.get("syncIntervalMinutes").getAsInt() : 10;
        if (interval < 1) interval = 1;
        List<Peer> peers = new ArrayList<>();
        if (obj.has("peers")) {
            for (var el : obj.getAsJsonArray("peers")) {
                JsonObject p = el.getAsJsonObject();
                String name = p.has("name") ? p.get("name").getAsString() : p.get("url").getAsString();
                String url = p.get("url").getAsString();
                String peerToken = p.has("token") ? p.get("token").getAsString() : "";
                peers.add(new Peer(name, url, peerToken));
            }
        }
        return new RankSyncConfig(enabled, port, token, interval, peers);
    }

    public static JsonObject defaultStub() {
        JsonObject stub = new JsonObject();
        stub.addProperty("enabled", false);
        stub.addProperty("listenPort", 25580);
        stub.addProperty("listenToken", "change-me-share-with-trusted-peers");
        stub.addProperty("syncIntervalMinutes", 10);
        stub.add("peers", new com.google.gson.JsonArray());
        return stub;
    }
}
