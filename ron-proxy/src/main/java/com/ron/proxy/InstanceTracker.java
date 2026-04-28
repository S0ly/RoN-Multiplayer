package com.ron.proxy;

import com.google.gson.*;
import com.ron.common.db.PlayerStats;
import com.ron.common.db.PlayerStatsDAO;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class InstanceTracker {

    public record InstanceConfig(String name, String rconHost, int rconPort, String rconPassword) {}
    public record ModeInfo(String name, int minPlayers, int maxPlayers, int teamCount) {}
    public record MapInfo(String folder, String name, List<ModeInfo> modes) {}
    public record MapWithModes(String folder, String name, List<ModeInfo> compatibleModes) {}
    public record MatchResult(String instanceName, String mapFolder, String mode) {}

    public record MatchResultData(List<PlayerResultData> winners, List<PlayerResultData> losers) {}
    public record PlayerResultData(String uuid, String name) {}

    public record InstanceInfo(
        String state,
        String currentMap,
        int rtsPlayers,
        List<String> playerNames,
        long gameSeconds,
        List<MapInfo> maps,
        MatchResultData matchResult
    ) {}

    private static final int ACTIVE_POLL_SECONDS = 5;
    private static final int IDLE_POLL_SECONDS = 30;
    private static final long RCON_LOCK_TIMEOUT_SECONDS = 10;

    private final ProxyServer server;
    private final Logger logger;
    private final Gson gson = RonProxy.GSON;
    private final Map<String, InstanceConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();
    private final Map<String, List<MapInfo>> cachedMaps = new ConcurrentHashMap<>();
    private final Map<String, InstanceInfo> instances = new ConcurrentHashMap<>();
    private final Set<String> handledFinished = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingReady = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Integer>> pendingScores = new ConcurrentHashMap<>();
    private final Map<String, String> pendingModes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pendingPrivateFlags = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Long> lastPollTime = new ConcurrentHashMap<>();

    private MessageHandler messageHandler;
    private PlayerRouter playerRouter;
    private ActiveMatchTracker activeMatchTracker;
    private PlayerStatsDAO statsDAO;

    public InstanceTracker(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    public void setMessageHandler(MessageHandler messageHandler) { this.messageHandler = messageHandler; }
    public void setPlayerRouter(PlayerRouter playerRouter) { this.playerRouter = playerRouter; }
    public void setActiveMatchTracker(ActiveMatchTracker activeMatchTracker) { this.activeMatchTracker = activeMatchTracker; }
    public void setStatsDAO(PlayerStatsDAO statsDAO) { this.statsDAO = statsDAO; }

    public void addInstance(String name, String rconHost, int rconPort, String rconPassword) {
        configs.put(name, new InstanceConfig(name, rconHost, rconPort, rconPassword));
        instanceLocks.put(name, new ReentrantLock());
        instances.put(name, new InstanceInfo("OFFLINE", "", 0, List.of(), 0, List.of(), null));
    }

    public void startPolling() {
        poller.scheduleAtFixedRate(this::pollAll, ACTIVE_POLL_SECONDS, ACTIVE_POLL_SECONDS, TimeUnit.SECONDS);
        logger.info("Started polling {} instances ({}s active, {}s idle)",
            configs.size(), ACTIVE_POLL_SECONDS, IDLE_POLL_SECONDS);
    }

    public void shutdown() {
        poller.shutdownNow();
        try {
            if (!poller.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Poller did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollAll() {
        long now = System.currentTimeMillis();
        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            InstanceConfig config = entry.getValue();

            InstanceInfo current = instances.get(name);
            if (current != null && isLowActivity(current.state)) {
                long lastPoll = lastPollTime.getOrDefault(name, 0L);
                if (now - lastPoll < IDLE_POLL_SECONDS * 1000L) continue;
            }

            lastPollTime.put(name, now);

            try {
                pollInstance(name, config);
            } catch (Exception e) {
                String prevState = instances.containsKey(name) ? instances.get(name).state : "UNKNOWN";
                instances.put(name, new InstanceInfo("OFFLINE", "", 0, List.of(), 0,
                    cachedMaps.getOrDefault(name, List.of()), null));
                handledFinished.remove(name);
                if (!"OFFLINE".equals(prevState)) {
                    logger.warn("[{}] Went offline: {}", name, e.getMessage());
                }
            }
        }
    }

    private boolean isLowActivity(String state) {
        return "IDLE".equals(state) || "OFFLINE".equals(state);
    }

    private void pollInstance(String name, InstanceConfig config) throws Exception {
        ReentrantLock lock = instanceLocks.get(name);
        if (!lock.tryLock()) return;
        try (RconClient rcon = new RconClient(config.rconHost, config.rconPort, config.rconPassword)) {
            String statusJson = rcon.sendCommand("ron-status");
            JsonObject status = gson.fromJson(statusJson, JsonObject.class);

            String state = status.get("state").getAsString();
            String currentMap = status.has("map") ? status.get("map").getAsString() : "";
            int rtsPlayers = status.has("rtsPlayers") ? status.get("rtsPlayers").getAsInt() : 0;
            long gameSeconds = status.has("gameSeconds") ? status.get("gameSeconds").getAsLong() : 0;

            List<String> playerNames = new ArrayList<>();
            if (status.has("playerNames")) {
                for (JsonElement el : status.getAsJsonArray("playerNames")) {
                    playerNames.add(el.getAsString());
                }
            }

            MatchResultData matchResult = null;
            if (status.has("matchResult")) {
                matchResult = MatchResultsWriter.parse(status.getAsJsonObject("matchResult"));
            }

            boolean ranked = status.has("ranked") && status.get("ranked").getAsBoolean();

            List<MapInfo> maps = cachedMaps.get(name);
            if (maps == null) {
                maps = fetchMaps(rcon);
                cachedMaps.put(name, maps);
                logger.info("[{}] Cached {} maps", name, maps.size());
            }

            String prevState = instances.containsKey(name) ? instances.get(name).state : "UNKNOWN";
            instances.put(name, new InstanceInfo(state, currentMap, rtsPlayers, playerNames, gameSeconds, maps, matchResult));

            if (!state.equals(prevState)) {
                logger.info("[{}] {} -> {}", name, prevState, state);
            }

            if ("READY".equals(state) && pendingReady.remove(name)) {
                handleInstanceReady(name, config);
            }

            if ("FINISHED".equals(state) && !handledFinished.contains(name)) {
                handledFinished.add(name);
                handleFinished(name, matchResult, ranked);
            }

            if (!"FINISHED".equals(state)) {
                handledFinished.remove(name);
            }

            if ("IDLE".equals(state) && !"IDLE".equals(prevState) && !"OFFLINE".equals(prevState)) {
                cachedMaps.remove(name);
            }
        } finally {
            lock.unlock();
        }
    }

    private List<MapInfo> fetchMaps(RconClient rcon) throws Exception {
        String mapsJson = rcon.sendCommand("ron-maps");
        JsonArray mapsArray = gson.fromJson(mapsJson, JsonArray.class);
        List<MapInfo> maps = new ArrayList<>();
        for (JsonElement el : mapsArray) {
            JsonObject m = el.getAsJsonObject();
            String folder = m.get("folder").getAsString();
            String name = m.get("name").getAsString();
            List<ModeInfo> modes = new ArrayList<>();
            if (m.has("modes")) {
                for (JsonElement modeEl : m.getAsJsonArray("modes")) {
                    JsonObject modeObj = modeEl.getAsJsonObject();
                    modes.add(new ModeInfo(
                        modeObj.get("name").getAsString(),
                        modeObj.get("minPlayers").getAsInt(),
                        modeObj.get("maxPlayers").getAsInt(),
                        modeObj.get("teamCount").getAsInt()
                    ));
                }
            }

            maps.add(new MapInfo(folder, name, modes));
        }
        return maps;
    }

    private void handleFinished(String instanceName, MatchResultData matchResult, boolean ranked) {
        logger.info("[{}] Match finished (ranked={}), processing results", instanceName, ranked);

        if (ranked && matchResult != null && statsDAO != null) {
            MatchResultsWriter.write(statsDAO, matchResult, logger);
        } else if (!ranked) {
            logger.info("[{}] Unranked match — skipping score updates", instanceName);
        }

        if (playerRouter != null) {
            playerRouter.transferAllFromServer(instanceName, "lobby");
        }

        if (activeMatchTracker != null) {
            activeMatchTracker.clearMatch(instanceName);
        }

        pendingModes.remove(instanceName);
        resetInstance(instanceName);
    }

    private void resetInstance(String instanceName) {
        resetInstance(instanceName, 1);
    }

    private void resetInstance(String instanceName, int attempt) {
        InstanceConfig config = configs.get(instanceName);
        if (config == null) return;

        int maxAttempts = 3;
        poller.schedule(() -> {
            ReentrantLock lock = instanceLocks.get(instanceName);
            boolean held = false;
            try {
                held = lock.tryLock(RCON_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!held) {
                    logger.warn("[{}] Reset skipped — could not acquire RCON lock within {}s",
                            instanceName, RCON_LOCK_TIMEOUT_SECONDS);
                    if (attempt < maxAttempts) resetInstance(instanceName, attempt + 1);
                    return;
                }
                try (RconClient rcon = new RconClient(config.rconHost, config.rconPort, config.rconPassword)) {
                    rcon.sendCommand("ron-reset");
                    logger.info("[{}] Reset to IDLE", instanceName);
                } catch (Exception e) {
                    if (attempt < maxAttempts) {
                        logger.warn("[{}] Reset attempt {}/{} failed, retrying in 10s", instanceName, attempt, maxAttempts);
                        resetInstance(instanceName, attempt + 1);
                    } else {
                        logger.error("[{}] Reset failed after {} attempts", instanceName, maxAttempts, e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (held) lock.unlock();
            }
        }, attempt == 1 ? 5 : 10, TimeUnit.SECONDS);
    }

    private void handleInstanceReady(String name, InstanceConfig config) {
        logger.info("[{}] Ready, sending deferred data", name);

        Map<String, Integer> scores = pendingScores.remove(name);
        if (scores != null) {
            sendPlayerScores(name, scores);
        }

        // Send mode command if one is pending
        String mode = pendingModes.get(name);
        if (mode != null) {
            try {
                sendRconCommand(name, "ron-setmode " + mode);
                logger.info("[{}] Set mode to: {}", name, mode);
            } catch (Exception e) {
                logger.error("[{}] Failed to set mode: {}", name, mode, e);
            }
        }

        // Send private match flag if queued
        Boolean privateFlag = pendingPrivateFlags.remove(name);
        if (privateFlag != null && privateFlag) {
            try {
                sendRconCommand(name, "ron-setprivate true");
                logger.info("[{}] Marked as private match", name);
            } catch (Exception e) {
                logger.error("[{}] Failed to set private flag", name, e);
            }
        }

        if (messageHandler != null) {
            messageHandler.sendInstanceReady(name);
        }
    }

    public void confirmMatch(String instanceName) {
        pendingReady.add(instanceName);
        logger.info("[{}] Waiting for READY", instanceName);
    }

    public void setPendingMode(String instanceName, String mode) {
        if (mode != null) {
            pendingModes.put(instanceName, mode);
        }
    }

    public void queuePlayerScores(String instanceName, Map<String, Integer> scores) {
        pendingScores.put(instanceName, scores);
    }

    public void queuePrivateFlag(String instanceName, boolean isPrivate) {
        pendingPrivateFlags.put(instanceName, isPrivate);
    }

    private String sendRconCommand(String instanceName, String command) throws Exception {
        InstanceConfig config = configs.get(instanceName);
        if (config == null) throw new IllegalArgumentException("Unknown instance: " + instanceName);

        ReentrantLock lock = instanceLocks.get(instanceName);
        if (!lock.tryLock(RCON_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("Timed out acquiring RCON lock for " + instanceName);
        }
        try (RconClient rcon = new RconClient(config.rconHost, config.rconPort, config.rconPassword)) {
            return rcon.sendCommand(command);
        } finally {
            lock.unlock();
        }
    }

    public boolean sendPlayerScores(String instanceName, Map<String, Integer> scores) {
        try {
            String scoresJson = gson.toJson(scores);
            sendRconCommand(instanceName, "ron-playerscores " + scoresJson);
            logger.info("[{}] Sent player scores ({} players)", instanceName, scores.size());
            return true;
        } catch (Exception e) {
            logger.error("[{}] Failed to send player scores", instanceName, e);
            return false;
        }
    }

    /**
     * Find compatible maps across all available instances.
     * Returns maps that have at least one mode supporting the given player count.
     */
    public List<MapWithModes> findCompatibleMaps(int playerCount, int limit) {
        Map<String, MapWithModes> seen = new LinkedHashMap<>();
        Map<String, InstanceInfo> snap = Map.copyOf(instances);

        for (var entry : snap.entrySet()) {
            InstanceInfo info = entry.getValue();
            if (!"IDLE".equals(info.state) && !"READY".equals(info.state)) continue;

            for (MapInfo map : info.maps) {
                if (seen.containsKey(map.folder())) continue;

                List<ModeInfo> compatible = new ArrayList<>();
                for (ModeInfo mode : map.modes()) {
                    if (playerCount >= mode.minPlayers() && playerCount <= mode.maxPlayers()) {
                        compatible.add(mode);
                    }
                }

                if (!compatible.isEmpty()) {
                    seen.put(map.folder(), new MapWithModes(map.folder(), map.name(), compatible));
                }
            }
        }

        List<MapWithModes> result = new ArrayList<>(seen.values());
        Collections.shuffle(result);
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /**
     * Find an available instance with a specific map and mode.
     */
    public Optional<MatchResult> findMatchForMap(int playerCount, String mapFolder, String mode) {
        if (mapFolder == null || "random".equals(mapFolder)) {
            return findMatch(playerCount, mode);
        }

        Map<String, InstanceInfo> snap = Map.copyOf(instances);
        for (var entry : snap.entrySet()) {
            String name = entry.getKey();
            InstanceInfo info = entry.getValue();
            if (!"IDLE".equals(info.state) && !"READY".equals(info.state)) continue;

            for (MapInfo map : info.maps) {
                if (!map.folder().equals(mapFolder)) continue;

                // Check if the map has a compatible mode
                for (ModeInfo m : map.modes()) {
                    if (mode != null && m.name().equals(mode) &&
                            playerCount >= m.minPlayers() && playerCount <= m.maxPlayers()) {
                        return Optional.of(new MatchResult(name, map.folder(), mode));
                    }
                }

                // If no specific mode requested, find any compatible mode
                if (mode == null) {
                    for (ModeInfo m : map.modes()) {
                        if (playerCount >= m.minPlayers() && playerCount <= m.maxPlayers()) {
                            return Optional.of(new MatchResult(name, map.folder(), m.name()));
                        }
                    }
                }
            }
        }
        return findMatch(playerCount, mode);
    }

    /**
     * Find any available instance with a map+mode matching the player count.
     */
    public Optional<MatchResult> findMatch(int playerCount, String preferredMode) {
        List<MatchResult> preferred = new ArrayList<>();
        List<MatchResult> fallback = new ArrayList<>();

        Map<String, InstanceInfo> snap = Map.copyOf(instances);
        for (var entry : snap.entrySet()) {
            String name = entry.getKey();
            InstanceInfo info = entry.getValue();
            if (!"IDLE".equals(info.state) && !"READY".equals(info.state)) continue;

            for (MapInfo map : info.maps) {
                for (ModeInfo m : map.modes()) {
                    if (playerCount >= m.minPlayers() && playerCount <= m.maxPlayers()) {
                        MatchResult result = new MatchResult(name, map.folder(), m.name());
                        if (preferredMode != null && m.name().equals(preferredMode)) {
                            preferred.add(result);
                        } else {
                            fallback.add(result);
                        }
                    }
                }
            }
        }

        if (!preferred.isEmpty()) {
            Collections.shuffle(preferred);
            return Optional.of(preferred.get(0));
        }
        if (!fallback.isEmpty()) {
            Collections.shuffle(fallback);
            return Optional.of(fallback.get(0));
        }
        return Optional.empty();
    }

    public boolean loadMap(String instanceName, String mapFolder) {
        try {
            sendRconCommand(instanceName, "ron-loadmap " + mapFolder);
            logger.info("[{}] Loading map: {}", instanceName, mapFolder);
            instances.put(instanceName, new InstanceInfo("PREPARING", "", 0, List.of(), 0,
                instances.get(instanceName).maps, null));
            cachedMaps.remove(instanceName);
            return true;
        } catch (Exception e) {
            logger.error("[{}] Failed to load map {}", instanceName, mapFolder, e);
            pendingScores.remove(instanceName);
            pendingModes.remove(instanceName);
            pendingPrivateFlags.remove(instanceName);
            return false;
        }
    }

    // --- Info accessors ---

    public Map<String, InstanceInfo> getAllInstances() { return Map.copyOf(instances); }
    public InstanceConfig getConfig(String instanceName) { return configs.get(instanceName); }

    public int getAvailableCount() {
        return (int) instances.values().stream()
                .filter(i -> "IDLE".equals(i.state) || "READY".equals(i.state)).count();
    }

    public int getRunningCount() {
        return (int) instances.values().stream()
                .filter(i -> "RUNNING".equals(i.state)).count();
    }

    public int getMinPlayers() {
        return instances.values().stream().flatMap(i -> i.maps.stream())
                .flatMap(m -> m.modes().stream())
                .mapToInt(ModeInfo::minPlayers).min().orElse(2);
    }

    public int getMaxPlayers() {
        return instances.values().stream().flatMap(i -> i.maps.stream())
                .flatMap(m -> m.modes().stream())
                .mapToInt(ModeInfo::maxPlayers).max().orElse(2);
    }

    public JsonObject buildLobbyInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("type", "info");
        info.addProperty("minPlayers", getMinPlayers());
        info.addProperty("maxPlayers", getMaxPlayers());
        info.addProperty("availableInstances", getAvailableCount());
        info.addProperty("occupiedInstances", getRunningCount());

        JsonObject matches = new JsonObject();
        JsonObject instancesObj = new JsonObject();

        Map<String, InstanceInfo> snap = Map.copyOf(instances);
        for (var entry : snap.entrySet()) {
            InstanceInfo i = entry.getValue();
            JsonObject inst = new JsonObject();
            inst.addProperty("status", i.state);
            inst.addProperty("map", i.currentMap);
            inst.addProperty("mapCount", i.maps.size());
            instancesObj.add(entry.getKey(), inst);

            if ("RUNNING".equals(i.state)) {
                JsonObject match = new JsonObject();
                match.addProperty("map", i.currentMap);
                match.addProperty("players", i.rtsPlayers);
                match.add("playerNames", gson.toJsonTree(i.playerNames));
                match.addProperty("elapsedSeconds", i.gameSeconds);
                matches.add(entry.getKey(), match);
            }
        }

        info.add("matches", matches);
        info.add("instances", instancesObj);
        return info;
    }
}
