package com.ron.proxy;

import com.google.gson.*;
import com.ron.common.db.PlayerStats;
import com.ron.common.db.PlayerStatsDAO;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MessageHandler {

    private final ProxyServer server;
    private final Logger logger;
    private final InstanceTracker instanceTracker;
    private final PlayerRouter playerRouter;
    private final ActiveMatchTracker activeMatchTracker;
    private final Gson gson = RonProxy.GSON;
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> transfersInProgress = new ConcurrentHashMap<>();
    private PlayerStatsDAO statsDAO;
    private RonProxy plugin;

    public MessageHandler(ProxyServer server, Logger logger, InstanceTracker instanceTracker,
                          PlayerRouter playerRouter, ActiveMatchTracker activeMatchTracker) {
        this.server = server;
        this.logger = logger;
        this.instanceTracker = instanceTracker;
        this.playerRouter = playerRouter;
        this.activeMatchTracker = activeMatchTracker;
    }

    public void setPlugin(RonProxy plugin) {
        this.plugin = plugin;
    }

    public void setStatsDAO(PlayerStatsDAO statsDAO) {
        this.statsDAO = statsDAO;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection source)) return;

        String channel = event.getIdentifier().getId();
        String data = new String(event.getData(), StandardCharsets.UTF_8);
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        switch (channel) {
            case "ron:transfer" -> handleTransfer(data, source);
            case "ron:match" -> handleMatchRequest(data);
        }
    }

    private void handleTransfer(String data, ServerConnection source) {
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);
            String target = json.get("target").getAsString();

            if (json.has("players")) {
                JsonArray playersArr = json.getAsJsonArray("players");
                int playerCount = playersArr.size();
                // Prevent duplicate transfers to the same instance
                if (transfersInProgress.putIfAbsent(target, new java.util.concurrent.atomic.AtomicInteger(playerCount)) != null) {
                    logger.warn("Duplicate transfer to {} — skipping", target);
                    return;
                }

                // Register players in active match tracker first
                Set<UUID> playerUuids = new HashSet<>();
                for (var element : playersArr) {
                    playerUuids.add(UUID.fromString(element.getAsString()));
                }
                activeMatchTracker.registerMatch(target, playerUuids);

                // Queue private match flag if set
                if (json.has("privateMatch") && json.get("privateMatch").getAsBoolean()) {
                    instanceTracker.queuePrivateFlag(target, true);
                }

                if (statsDAO != null) {
                    Map<String, Integer> scores = new HashMap<>();
                    for (var element : playersArr) {
                        String uuid = element.getAsString();
                        try {
                            Player player = server.getPlayer(UUID.fromString(uuid)).orElse(null);
                            String name = player != null ? player.getUsername() : "unknown";
                            PlayerStats stats = statsDAO.getOrCreate(uuid, name);
                            scores.put(uuid, stats.points);
                        } catch (Exception e) {
                            logger.warn("Failed to look up score for {}", uuid);
                        }
                    }
                    if (!scores.isEmpty()) {
                        instanceTracker.queuePlayerScores(target, scores);
                    }
                }

                int i = 0;
                for (UUID uuid : playerUuids) {
                    long delayMs = i * 1000L;
                    if (delayMs == 0) {
                        playerRouter.transferPlayer(uuid, target);
                    } else {
                        server.getScheduler().buildTask(plugin, () ->
                            playerRouter.transferPlayer(uuid, target)
                        ).delay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
                    }
                    i++;
                }

                // Safety fallback: clear transfer lock if not all players landed (handled normally by ServerConnectedEvent)
                long fallbackMs = (long) playerUuids.size() * 1000L + 30_000L;
                server.getScheduler().buildTask(plugin, () -> transfersInProgress.remove(target))
                        .delay(fallbackMs, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();

            } else if (json.has("all") && json.get("all").getAsBoolean()) {
                String sourceServer = source.getServerInfo().getName();
                playerRouter.transferAllFromServer(sourceServer, target);
            }
        } catch (Exception e) {
            logger.error("Failed to handle transfer: {}", data, e);
        }
    }

    private void handleMatchRequest(String data) {
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);
            String action = json.get("action").getAsString();

            switch (action) {
                case "find_match" -> {
                    int playerCount = json.get("playerCount").getAsInt();
                    String chosenMap = json.has("chosenMap") ? json.get("chosenMap").getAsString() : null;
                    String chosenMode = json.has("chosenMode") ? json.get("chosenMode").getAsString() : null;

                    Optional<InstanceTracker.MatchResult> result = instanceTracker.findMatchForMap(playerCount, chosenMap, chosenMode);

                    JsonObject response = new JsonObject();
                    if (result.isPresent()) {
                        String instance = result.get().instanceName();
                        String map = result.get().mapFolder();
                        String mode = result.get().mode();

                        boolean sent = instanceTracker.loadMap(instance, map);
                        if (sent) {
                            instanceTracker.setPendingMode(instance, mode);
                            response.addProperty("found", true);
                            response.addProperty("instance", instance);
                            response.addProperty("map", map);
                            if (mode != null) {
                                response.addProperty("mode", mode);
                            }
                            logger.info("Match: {} players -> {} ({}, mode={})", playerCount, instance, map, mode);
                        } else {
                            response.addProperty("found", false);
                            logger.warn("Found match but RCON failed for {}", instance);
                        }
                    } else {
                        response.addProperty("found", false);
                        logger.info("No match for {} players", playerCount);
                    }
                    sendToLobby(gson.toJson(response));
                }
                case "get_maps" -> {
                    int playerCount = json.get("playerCount").getAsInt();
                    List<InstanceTracker.MapWithModes> maps = instanceTracker.findCompatibleMaps(playerCount, 5);

                    JsonObject response = new JsonObject();
                    response.addProperty("type", "map_options");
                    JsonArray mapsArr = new JsonArray();
                    for (InstanceTracker.MapWithModes m : maps) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("folder", m.folder());
                        obj.addProperty("name", m.name());

                        JsonArray modesArr = new JsonArray();
                        for (InstanceTracker.ModeInfo mode : m.compatibleModes()) {
                            JsonObject modeObj = new JsonObject();
                            modeObj.addProperty("name", mode.name());
                            modeObj.addProperty("minPlayers", mode.minPlayers());
                            modeObj.addProperty("maxPlayers", mode.maxPlayers());
                            modesArr.add(modeObj);
                        }
                        obj.add("modes", modesArr);
                        mapsArr.add(obj);
                    }
                    response.add("maps", mapsArr);
                    sendToLobby(gson.toJson(response));
                    logger.info("Sent {} compatible maps to lobby", maps.size());
                }
                case "confirm_match" -> {
                    String instance = json.get("instance").getAsString();
                    instanceTracker.confirmMatch(instance);
                    logger.info("Confirmed match on {}, waiting for READY", instance);
                }
                case "get_info" -> {
                    sendToLobby(gson.toJson(instanceTracker.buildLobbyInfo()));
                }
                case "get_rank" -> {
                    String uuid = json.get("uuid").getAsString();
                    String name = json.get("name").getAsString();
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "rank_response");
                    if (statsDAO != null) {
                        try {
                            PlayerStats stats = statsDAO.getOrCreate(uuid, name);
                            response.addProperty("uuid", uuid);
                            response.addProperty("points", stats.points);
                            response.addProperty("wins", stats.wins);
                            response.addProperty("losses", stats.losses);
                        } catch (Exception e) {
                            response.addProperty("error", true);
                            logger.error("Failed to get rank for {}", name, e);
                        }
                    } else {
                        response.addProperty("error", true);
                    }
                    sendToLobby(gson.toJson(response));
                }
                case "get_leaderboard" -> {
                    int limit = json.has("limit") ? json.get("limit").getAsInt() : 10;
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "leaderboard_response");
                    if (statsDAO != null) {
                        try {
                            List<PlayerStats> top = statsDAO.getTopPlayers(limit);
                            JsonArray arr = new JsonArray();
                            for (PlayerStats s : top) {
                                JsonObject p = new JsonObject();
                                p.addProperty("name", s.name);
                                p.addProperty("points", s.points);
                                p.addProperty("wins", s.wins);
                                p.addProperty("losses", s.losses);
                                arr.add(p);
                            }
                            response.add("players", arr);
                        } catch (Exception e) {
                            response.addProperty("error", true);
                            logger.error("Failed to get leaderboard", e);
                        }
                    } else {
                        response.addProperty("error", true);
                    }
                    sendToLobby(gson.toJson(response));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to handle match request: {}", data, e);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String target = event.getServer().getServerInfo().getName();
        var pending = transfersInProgress.get(target);
        if (pending != null && pending.decrementAndGet() <= 0) {
            transfersInProgress.remove(target);
        }
    }

    public void sendInstanceReady(String instanceName) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "instance_ready");
        json.addProperty("instance", instanceName);
        sendToLobby(gson.toJson(json));
        logger.info("Sent instance_ready to lobby for {}", instanceName);
    }

    private void sendToLobby(String jsonData) {
        Optional<RegisteredServer> lobby = server.getServer("lobby");
        if (lobby.isEmpty()) return;

        byte[] payload = jsonData.getBytes(StandardCharsets.UTF_8);
        MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from("ron:match");

        for (Player player : lobby.get().getPlayersConnected()) {
            if (player.getCurrentServer().isPresent()) {
                player.getCurrentServer().get().sendPluginMessage(channel, payload);
                break;
            }
        }
    }
}
