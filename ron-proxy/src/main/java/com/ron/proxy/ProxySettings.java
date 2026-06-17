package com.ron.proxy;

import com.google.gson.JsonObject;

/**
 * Network-wide proxy settings read from the {@code network} and {@code timings}
 * blocks of config.yml. Held statically because these values are read across
 * several unrelated classes (tracker, RCON client, transfer handler) and never
 * change after startup. Defaults match the historical hardcoded constants, so an
 * absent block behaves exactly as before.
 */
public final class ProxySettings {

    private ProxySettings() {}

    // network.*
    public static volatile String lobbyServerName = "lobby";
    public static volatile int maxSpectatorsPerInstance = 4;

    // timings.*
    public static volatile int activePollSeconds = 5;
    public static volatile int idlePollSeconds = 30;
    public static volatile int rconConnectMs = 3000;
    public static volatile int rconSocketMs = 5000;
    public static volatile long transferStaggerMs = 1000L;

    public static void load(JsonObject root) {
        if (root != null && root.has("network")) {
            JsonObject net = root.getAsJsonObject("network");
            if (net.has("lobby-server-name")) lobbyServerName = net.get("lobby-server-name").getAsString();
            if (net.has("max-spectators-per-instance")) maxSpectatorsPerInstance = net.get("max-spectators-per-instance").getAsInt();
        }
        if (root != null && root.has("timings")) {
            JsonObject t = root.getAsJsonObject("timings");
            if (t.has("active-poll-seconds")) activePollSeconds = t.get("active-poll-seconds").getAsInt();
            if (t.has("idle-poll-seconds")) idlePollSeconds = t.get("idle-poll-seconds").getAsInt();
            if (t.has("rcon-connect-ms")) rconConnectMs = t.get("rcon-connect-ms").getAsInt();
            if (t.has("rcon-socket-ms")) rconSocketMs = t.get("rcon-socket-ms").getAsInt();
            if (t.has("transfer-stagger-ms")) transferStaggerMs = t.get("transfer-stagger-ms").getAsLong();
        }
    }
}
