package com.ron.lobby.messaging;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ron.common.messaging.MessageProtocol.Action;
import com.ron.common.messaging.MessageProtocol.Channels;
import com.ron.common.messaging.MessageProtocol.Type;
import com.ron.lobby.RonLobby;
import com.ron.lobby.queue.MatchQueue;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class LobbyMessaging implements PluginMessageListener {

    private static final Gson gson = new Gson();

    // Stored server info for /status command
    private static JsonObject lastServerInfo = null;

    private static final long CALLBACK_TTL_MILLIS = 30_000;

    private record TimedCallback(Consumer<JsonObject> callback, long deadline) {}

    // Async callbacks for proxy responses (each carries its own deadline)
    private static final Map<String, TimedCallback> pendingRankCallbacks = new ConcurrentHashMap<>();
    private static final AtomicReference<TimedCallback> pendingLeaderboardCallback = new AtomicReference<>();
    private static final AtomicReference<TimedCallback> pendingServerInfoCallback = new AtomicReference<>();

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        String data = new String(message, StandardCharsets.UTF_8);

        if (Channels.MATCH.equals(channel)) {
            handleMatchResponse(data);
        }
    }

    private void handleMatchResponse(String data) {
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);

            if (json.has("type")) {
                String type = json.get("type").getAsString();
                switch (type) {
                    case Type.INFO -> {
                        lastServerInfo = json;
                        RonLobby.matchQueue.updateServerInfo(
                            json.get("minPlayers").getAsInt(),
                            json.get("availableInstances").getAsInt()
                        );
                        // Status row in any open Hub now has fresh data.
                        Bukkit.getScheduler().runTask(RonLobby.INSTANCE,
                                com.ron.lobby.ui.menu.MenuService::refreshLobbyMenus);
                        TimedCallback infoCb = pendingServerInfoCallback.getAndSet(null);
                        if (infoCb != null) {
                            Bukkit.getScheduler().runTask(RonLobby.INSTANCE, () -> infoCb.callback().accept(json));
                        }
                    }
                    case Type.MAP_OPTIONS -> handleMapOptions(json);
                    case Type.RANK_RESPONSE -> {
                        String uuid = json.has("uuid") ? json.get("uuid").getAsString() : null;
                        if (uuid != null) {
                            TimedCallback cb = pendingRankCallbacks.remove(uuid);
                            if (cb != null) {
                                Bukkit.getScheduler().runTask(RonLobby.INSTANCE, () -> cb.callback().accept(json));
                            }
                        }
                    }
                    case Type.LEADERBOARD_RESPONSE -> {
                        TimedCallback cb = pendingLeaderboardCallback.getAndSet(null);
                        if (cb != null) {
                            Bukkit.getScheduler().runTask(RonLobby.INSTANCE, () -> cb.callback().accept(json));
                        }
                    }
                    case Type.INSTANCE_READY -> {
                        String instance = json.get("instance").getAsString();
                        RonLobby.matchQueue.onInstanceReady(instance);
                    }
                }
            } else if (json.has("found")) {
                // Match search result
                if (json.get("found").getAsBoolean()) {
                    String instance = json.get("instance").getAsString();
                    RonLobby.matchQueue.onMatchFound(instance);
                } else {
                    RonLobby.matchQueue.onNoMatchFound();
                }
            }
        } catch (Exception e) {
            RonLobby.INSTANCE.getLogger().severe("Failed to handle match response: " + e.getMessage());
        }
    }

    private void handleMapOptions(JsonObject json) {
        List<MatchQueue.MapOption> options = new ArrayList<>();
        if (json.has("maps")) {
            for (var el : json.getAsJsonArray("maps")) {
                JsonObject m = el.getAsJsonObject();

                List<MatchQueue.ModeOption> modes = new ArrayList<>();
                if (m.has("modes")) {
                    for (var modeEl : m.getAsJsonArray("modes")) {
                        JsonObject modeObj = modeEl.getAsJsonObject();
                        modes.add(new MatchQueue.ModeOption(
                                modeObj.get("name").getAsString(),
                                modeObj.get("minPlayers").getAsInt(),
                                modeObj.get("maxPlayers").getAsInt()
                        ));
                    }
                }

                options.add(new MatchQueue.MapOption(
                        m.get("folder").getAsString(),
                        m.get("name").getAsString(),
                        modes
                ));
            }
        }
        RonLobby.matchQueue.onMapOptions(options);
    }

    public static JsonObject getLastServerInfo() {
        return lastServerInfo;
    }

    /**
     * Looks up the cached spectator load for an instance. Returns [count, max].
     * Returns null when the instance name is unknown or no info has arrived yet.
     */
    public static int[] getSpectatorLoad(String instanceName) {
        JsonObject info = lastServerInfo;
        if (info == null || !info.has("instances")) return null;
        JsonObject instances = info.getAsJsonObject("instances");
        if (!instances.has(instanceName)) return null;
        JsonObject inst = instances.getAsJsonObject(instanceName);
        if (!inst.has("spectatorCount") || !inst.has("maxSpectators")) return null;
        return new int[]{inst.get("spectatorCount").getAsInt(), inst.get("maxSpectators").getAsInt()};
    }

    // --- Outgoing messages ---

    public static void sendTransfer(java.util.List<String> playerUuids, String target) {
        sendTransfer(playerUuids, target, false);
    }

    public static void sendTransfer(java.util.List<String> playerUuids, String target, boolean privateMatch) {
        JsonObject json = new JsonObject();
        json.add("players", gson.toJsonTree(playerUuids));
        json.addProperty("target", target);
        if (privateMatch) {
            json.addProperty("privateMatch", true);
        }
        sendPluginMessage(Channels.TRANSFER, gson.toJson(json));
    }

    public static void sendSpectatorTransfer(java.util.List<String> playerUuids, String target) {
        JsonObject json = new JsonObject();
        json.add("players", gson.toJsonTree(playerUuids));
        json.addProperty("target", target);
        json.addProperty("spectator", true);
        sendPluginMessage(Channels.TRANSFER, gson.toJson(json));
    }

    public static void sendGetMaps(int playerCount) {
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.GET_MAPS);
        json.addProperty("playerCount", playerCount);
        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    public static void sendFindMatch(int playerCount, String chosenMap, String chosenMode) {
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.FIND_MATCH);
        json.addProperty("playerCount", playerCount);

        if (chosenMap != null) {
            json.addProperty("chosenMap", chosenMap);
        }
        if (chosenMode != null) {
            json.addProperty("chosenMode", chosenMode);
        }

        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    public static void sendConfirmMatch(String instanceName) {
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.CONFIRM_MATCH);
        json.addProperty("instance", instanceName);
        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    public static void sendCancelMatch(String instanceName) {
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.CANCEL_MATCH);
        json.addProperty("instance", instanceName);
        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    public static void requestServerInfo() {
        // Only send the request, don't overwrite an existing callback
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.GET_INFO);
        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    /**
     * Push the current queue snapshot to the proxy so it can mirror state for /ronstatus.
     * Call after any queue mutation.
     */
    public static void sendQueueUpdate(JsonObject snapshot) {
        snapshot.addProperty("action", Action.QUEUE_UPDATE);
        sendPluginMessage(Channels.MATCH, gson.toJson(snapshot));
    }

    public static void requestServerInfo(Consumer<JsonObject> callback) {
        pendingServerInfoCallback.set(new TimedCallback(callback, deadline()));
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.GET_INFO);
        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    public static void requestRank(String uuid, String name, Consumer<JsonObject> callback) {
        pendingRankCallbacks.put(uuid, new TimedCallback(callback, deadline()));
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.GET_RANK);
        json.addProperty("uuid", uuid);
        json.addProperty("name", name);
        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    public static void requestLeaderboard(int limit, Consumer<JsonObject> callback) {
        pendingLeaderboardCallback.set(new TimedCallback(callback, deadline()));
        JsonObject json = new JsonObject();
        json.addProperty("action", Action.GET_LEADERBOARD);
        json.addProperty("limit", limit);
        sendPluginMessage(Channels.MATCH, gson.toJson(json));
    }

    private static long deadline() {
        return System.currentTimeMillis() + CALLBACK_TTL_MILLIS;
    }

    /**
     * Drop callbacks whose deadline has passed. Call periodically from a scheduled task.
     */
    public static void cleanupStaleCallbacks() {
        long now = System.currentTimeMillis();
        pendingRankCallbacks.entrySet().removeIf(e -> e.getValue().deadline() < now);
        expireIfStale(pendingServerInfoCallback, now);
        expireIfStale(pendingLeaderboardCallback, now);
    }

    private static void expireIfStale(AtomicReference<TimedCallback> ref, long now) {
        TimedCallback current = ref.get();
        if (current != null && current.deadline() < now) {
            ref.compareAndSet(current, null);
        }
    }

    private static void sendPluginMessage(String channel, String jsonData) {
        Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (player == null) {
            RonLobby.INSTANCE.getLogger().warning("No players online — cannot send plugin message");
            return;
        }
        player.sendPluginMessage(RonLobby.INSTANCE, channel, jsonData.getBytes(StandardCharsets.UTF_8));
    }
}
