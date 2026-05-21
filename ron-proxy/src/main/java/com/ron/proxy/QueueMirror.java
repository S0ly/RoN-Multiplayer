package com.ron.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-side snapshot of the lobby's queue state. The lobby is still authoritative
 * for the queue lifecycle; this just gives the proxy visibility (used by /ronstatus
 * and any future MatchService consumers).
 *
 * Future work: flip authority — proxy owns the queue, lobby becomes a UI worker.
 */
public class QueueMirror {

    public record PrivateLobby(String code, String hostName, List<String> playerNames) {}
    public record Snapshot(
            String phase,
            List<String> publicQueue,
            List<String> nextQueue,
            List<PrivateLobby> privateLobbies,
            long updatedAt
    ) {}

    private volatile Snapshot latest = new Snapshot("OPEN", List.of(), List.of(), List.of(), 0L);

    public void update(JsonObject json) {
        String phase = json.has("phase") ? json.get("phase").getAsString() : "OPEN";
        List<String> publicQueue = readStringArray(json, "publicQueue");
        List<String> nextQueue = readStringArray(json, "nextQueue");

        List<PrivateLobby> privateLobbies = new ArrayList<>();
        if (json.has("privateLobbies")) {
            for (var el : json.getAsJsonArray("privateLobbies")) {
                JsonObject lobby = el.getAsJsonObject();
                privateLobbies.add(new PrivateLobby(
                        lobby.get("code").getAsString(),
                        lobby.has("hostName") ? lobby.get("hostName").getAsString() : "?",
                        readStringArray(lobby, "playerNames")
                ));
            }
        }

        latest = new Snapshot(phase, publicQueue, nextQueue, privateLobbies, System.currentTimeMillis());
    }

    public Snapshot snapshot() {
        return latest;
    }

    private static List<String> readStringArray(JsonObject json, String key) {
        if (!json.has(key)) return List.of();
        List<String> out = new ArrayList<>();
        JsonArray arr = json.getAsJsonArray(key);
        for (var el : arr) out.add(el.getAsString());
        return out;
    }
}
