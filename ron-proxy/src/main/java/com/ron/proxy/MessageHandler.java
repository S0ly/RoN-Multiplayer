package com.ron.proxy;

import com.google.gson.*;
import com.ron.common.db.MatchPlayer;
import com.ron.common.db.PlayerStats;
import com.ron.common.db.PlayerStatsDAO;
import com.ron.common.messaging.MessageProtocol;
import com.ron.common.messaging.MessageProtocol.Action;
import com.ron.common.messaging.MessageProtocol.Channels;
import com.ron.common.messaging.MessageProtocol.Type;
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

    /** Stagger between successive player transfers, so an instance isn't hit by a thundering herd. */
    private static final long PLAYER_TRANSFER_STAGGER_MS = 1000L;
    /** Extra grace beyond the staggered transfers before force-clearing the transfer lock. */
    private static final long TRANSFER_FALLBACK_GRACE_MS = 30_000L;

    private final ProxyServer server;
    private final Logger logger;
    private final InstanceTracker instanceTracker;
    private final PlayerRouter playerRouter;
    private final ActiveMatchTracker activeMatchTracker;
    private final Gson gson = RonProxy.GSON;
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> transfersInProgress = new ConcurrentHashMap<>();
    private PlayerStatsDAO statsDAO;
    private RonProxy plugin;
    private MatchService matchService;
    private QueueMirror queueMirror;

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

    public void setMatchService(MatchService matchService) {
        this.matchService = matchService;
    }

    public void setQueueMirror(QueueMirror queueMirror) {
        this.queueMirror = queueMirror;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection source)) return;

        String channel = event.getIdentifier().getId();
        String data = new String(event.getData(), StandardCharsets.UTF_8);
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        switch (channel) {
            case Channels.TRANSFER -> handleTransfer(data, source);
            case Channels.MATCH -> handleMatchRequest(data);
        }
    }

    private void handleTransfer(String data, ServerConnection source) {
        try {
            JsonObject json = gson.fromJson(data, JsonObject.class);
            String target = json.get("target").getAsString();

            if (json.has("players")) {
                JsonArray playersArr = json.getAsJsonArray("players");
                int playerCount = playersArr.size();
                boolean isSpectator = json.has("spectator") && json.get("spectator").getAsBoolean();
                // Prevent duplicate transfers to the same instance (skipped for spectators — they trickle in independently)
                if (!isSpectator && transfersInProgress.putIfAbsent(target, new java.util.concurrent.atomic.AtomicInteger(playerCount)) != null) {
                    logger.warn("Duplicate transfer to {} — skipping", target);
                    return;
                }

                Set<UUID> playerUuids = new HashSet<>();
                for (var element : playersArr) {
                    playerUuids.add(UUID.fromString(element.getAsString()));
                }

                if (!isSpectator) {
                    activeMatchTracker.registerMatch(target, playerUuids);

                    boolean isPrivate = json.has("privateMatch") && json.get("privateMatch").getAsBoolean();

                    Map<String, Integer> scores = new HashMap<>();
                    List<MatchPlayer> matchPlayers = new ArrayList<>();
                    String matchId = matchService != null
                            ? matchService.matchOn(target).map(m -> m.id()).orElse(target)
                            : target;
                    if (statsDAO != null) {
                        for (var element : playersArr) {
                            String uuid = element.getAsString();
                            try {
                                Player player = server.getPlayer(UUID.fromString(uuid)).orElse(null);
                                String name = player != null ? player.getUsername() : "unknown";
                                PlayerStats stats = statsDAO.getOrCreate(uuid, name);
                                scores.put(uuid, stats.points);
                                matchPlayers.add(new MatchPlayer(matchId, uuid, name, false, 0));
                            } catch (Exception e) {
                                logger.warn("Failed to look up score for {}", uuid, e);
                            }
                        }
                    }
                    if (matchService != null) {
                        matchService.attachPlayers(target, matchPlayers, scores, isPrivate);
                    }
                }

                int i = 0;
                for (UUID uuid : playerUuids) {
                    long delayMs = i * PLAYER_TRANSFER_STAGGER_MS;
                    if (delayMs == 0) {
                        playerRouter.transferPlayer(uuid, target);
                    } else {
                        server.getScheduler().buildTask(plugin, () ->
                            playerRouter.transferPlayer(uuid, target)
                        ).delay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
                    }
                    i++;
                }

                if (!isSpectator) {
                    // Safety fallback: clear transfer lock if not all players landed (handled normally by ServerConnectedEvent)
                    long fallbackMs = playerUuids.size() * PLAYER_TRANSFER_STAGGER_MS + TRANSFER_FALLBACK_GRACE_MS;
                    server.getScheduler().buildTask(plugin, () -> transfersInProgress.remove(target))
                            .delay(fallbackMs, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
                }

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
                case Action.FIND_MATCH -> handleFindMatch(json);
                case Action.GET_MAPS -> handleGetMaps(json);
                case Action.CONFIRM_MATCH -> handleConfirmMatch(json);
                case Action.CANCEL_MATCH -> handleCancelMatch(json);
                case Action.GET_INFO -> sendToLobby(gson.toJson(instanceTracker.buildLobbyInfo()));
                case Action.QUEUE_UPDATE -> { if (queueMirror != null) queueMirror.update(json); }
                case Action.GET_RANK -> handleGetRank(json);
                case Action.GET_LEADERBOARD -> handleGetLeaderboard(json);
            }
        } catch (Exception e) {
            logger.error("Failed to handle match request: {}", data, e);
        }
    }

    private void handleFindMatch(JsonObject json) {
        int playerCount = json.get("playerCount").getAsInt();
        String chosenMap = json.has("chosenMap") ? json.get("chosenMap").getAsString() : null;
        String chosenMode = json.has("chosenMode") ? json.get("chosenMode").getAsString() : null;
        // Absent ⇒ defaults: alliances locked, fog disabled (covers the public path).
        boolean lockAlliances = !json.has("lockAlliances") || json.get("lockAlliances").getAsBoolean();
        boolean fogOfWar = json.has("fogOfWar") && json.get("fogOfWar").getAsBoolean();

        Optional<InstanceTracker.MatchResult> result = instanceTracker.findMatchForMap(playerCount, chosenMap, chosenMode);

        JsonObject response = new JsonObject();
        if (result.isPresent()) {
            String instance = result.get().instanceName();
            String map = result.get().mapFolder();
            String mode = result.get().mode();

            boolean sent = instanceTracker.loadMap(instance, map);
            if (sent) {
                if (matchService != null) {
                    matchService.prepareMatch(instance, map, mode, lockAlliances, fogOfWar);
                }
                response.addProperty("found", true);
                response.addProperty("instance", instance);
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

    private void handleGetMaps(JsonObject json) {
        int playerCount = json.get("playerCount").getAsInt();
        List<InstanceTracker.MapWithModes> maps = instanceTracker.findCompatibleMaps(playerCount, Integer.MAX_VALUE);

        JsonObject response = new JsonObject();
        response.addProperty("type", Type.MAP_OPTIONS);
        JsonArray mapsArr = new JsonArray();
        for (InstanceTracker.MapWithModes m : maps) {
            JsonObject obj = new JsonObject();
            obj.addProperty("folder", m.folder());
            obj.addProperty("name", m.name());

            JsonArray modesArr = new JsonArray();
            for (InstanceTracker.ModeInfo mode : m.compatibleModes()) {
                JsonObject modeObj = new JsonObject();
                modeObj.addProperty("name", mode.name());
                modeObj.addProperty("players", mode.players());
                modesArr.add(modeObj);
            }
            obj.add("modes", modesArr);
            mapsArr.add(obj);
        }
        response.add("maps", mapsArr);
        sendToLobby(gson.toJson(response));
        logger.info("Sent {} compatible maps to lobby", maps.size());
    }

    private void handleConfirmMatch(JsonObject json) {
        String instance = json.get("instance").getAsString();
        if (matchService != null) {
            matchService.awaitReady(instance);
        }
        logger.info("Confirmed match on {}, waiting for READY", instance);
    }

    private void handleCancelMatch(JsonObject json) {
        String instance = json.get("instance").getAsString();
        instanceTracker.cancelPendingMatch(instance);
        logger.info("Cancelled pending match on {} (lobby request)", instance);
    }

    private void handleGetRank(JsonObject json) {
        String uuid = json.get("uuid").getAsString();
        String name = json.get("name").getAsString();
        JsonObject response = new JsonObject();
        response.addProperty("type", Type.RANK_RESPONSE);
        response.addProperty("uuid", uuid);
        if (statsDAO != null) {
            try {
                PlayerStats stats = statsDAO.getOrCreate(uuid, name);
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

    private void handleGetLeaderboard(JsonObject json) {
        int limit = json.has("limit") ? json.get("limit").getAsInt() : 10;
        JsonObject response = new JsonObject();
        response.addProperty("type", Type.LEADERBOARD_RESPONSE);
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

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String target = event.getServer().getServerInfo().getName();
        var pending = transfersInProgress.get(target);
        if (pending != null && pending.decrementAndGet() <= 0) {
            transfersInProgress.remove(target);
        }
    }

    public void pushLobbyInfo() {
        if (instanceTracker == null) return;
        sendToLobby(gson.toJson(instanceTracker.buildLobbyInfo()));
    }

    public void sendInstanceReady(String instanceName) {
        JsonObject json = new JsonObject();
        json.addProperty("type", Type.INSTANCE_READY);
        json.addProperty("instance", instanceName);
        sendToLobby(gson.toJson(json));
        logger.info("Sent instance_ready to lobby for {}", instanceName);
    }

    private void sendToLobby(String jsonData) {
        Optional<RegisteredServer> lobby = server.getServer("lobby");
        if (lobby.isEmpty()) return;

        byte[] payload = jsonData.getBytes(StandardCharsets.UTF_8);
        MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(MessageProtocol.Channels.MATCH);

        for (Player player : lobby.get().getPlayersConnected()) {
            if (player.getCurrentServer().isPresent()) {
                player.getCurrentServer().get().sendPluginMessage(channel, payload);
                break;
            }
        }
    }
}
