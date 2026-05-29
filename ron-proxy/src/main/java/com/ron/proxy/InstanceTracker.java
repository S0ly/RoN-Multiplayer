package com.ron.proxy;

import com.google.gson.*;
import com.ron.common.db.Match;
import com.ron.common.db.PlayerStatsDAO;
import com.ron.proxy.sync.RankSyncService;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class InstanceTracker {

    public record InstanceConfig(String name, String rconHost, int rconPort, String rconPassword) {}
    public record ModeInfo(String name, int minPlayers, int maxPlayers) {}
    public record MapInfo(String folder, String name, List<ModeInfo> modes) {}
    public record MapWithModes(String folder, String name, List<ModeInfo> compatibleModes) {}
    public record MatchResult(String instanceName, String mapFolder, String mode) {}

    public record MatchResultData(List<PlayerResultData> winners, List<PlayerResultData> losers) {}
    public record PlayerResultData(String uuid, String name) {}

    public record InstanceInfo(
        InstanceState state,
        String currentMap,
        int rtsPlayers,
        int spectatorCount,
        List<String> playerNames,
        long gameSeconds,
        List<MapInfo> maps,
        MatchResultData matchResult
    ) {}

    public static final int MAX_SPECTATORS_PER_INSTANCE = 4;

    private static final int ACTIVE_POLL_SECONDS = 5;
    private static final int IDLE_POLL_SECONDS = 30;
    private static final long RCON_LOCK_TIMEOUT_SECONDS = 10;
    // Paper closes its RCON listener while reloading a world during a map swap.
    // Hold PREPARING through this gap instead of declaring the instance offline.
    private static final int PREPARING_RCON_GRACE_POLLS = 18; // ~90s at 5s active poll

    private final ProxyServer server;
    private final Logger logger;
    private final Gson gson = RonProxy.GSON;
    private final Map<String, InstanceConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();
    private final Map<String, List<MapInfo>> cachedMaps = new ConcurrentHashMap<>();
    private final Map<String, InstanceInfo> instances = new ConcurrentHashMap<>();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Long> lastPollTime = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    private MessageHandler messageHandler;
    private PlayerRouter playerRouter;
    private ActiveMatchTracker activeMatchTracker;
    private PlayerStatsDAO statsDAO;
    private MatchService matchService;
    private QueueMirror queueMirror;
    private RankSyncService rankSyncService;

    public InstanceTracker(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    public void setMessageHandler(MessageHandler messageHandler) { this.messageHandler = messageHandler; }
    public void setPlayerRouter(PlayerRouter playerRouter) { this.playerRouter = playerRouter; }
    public void setActiveMatchTracker(ActiveMatchTracker activeMatchTracker) { this.activeMatchTracker = activeMatchTracker; }
    public void setStatsDAO(PlayerStatsDAO statsDAO) { this.statsDAO = statsDAO; }
    public void setMatchService(MatchService matchService) { this.matchService = matchService; }
    public void setQueueMirror(QueueMirror queueMirror) { this.queueMirror = queueMirror; }
    public void setRankSyncService(RankSyncService rankSyncService) { this.rankSyncService = rankSyncService; }

    public void addInstance(String name, String rconHost, int rconPort, String rconPassword) {
        configs.put(name, new InstanceConfig(name, rconHost, rconPort, rconPassword));
        instanceLocks.put(name, new ReentrantLock());
        instances.put(name, new InstanceInfo(InstanceState.OFFLINE, "", 0, 0, List.of(), 0, List.of(), null));
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
                consecutiveFailures.remove(name);
            } catch (Exception e) {
                InstanceState prevState = instances.containsKey(name) ? instances.get(name).state : InstanceState.OFFLINE;
                int fails = consecutiveFailures.merge(name, 1, Integer::sum);

                // Map swap closes RCON for ~30s while the world reloads. Hold PREPARING
                // through the expected gap rather than declaring the instance dead and
                // abandoning the assigned match.
                if (prevState == InstanceState.PREPARING && fails < PREPARING_RCON_GRACE_POLLS) {
                    continue;
                }

                instances.put(name, new InstanceInfo(InstanceState.OFFLINE, "", 0, 0, List.of(), 0,
                    cachedMaps.getOrDefault(name, List.of()), null));
                if (prevState != InstanceState.OFFLINE) {
                    logger.warn("[{}] Went offline: {}", name, e.getMessage());
                    if (matchService != null) matchService.onOffline(name);
                }
            }
        }
    }

    private boolean isLowActivity(InstanceState state) {
        return state == InstanceState.IDLE || state == InstanceState.OFFLINE;
    }

    private void pollInstance(String name, InstanceConfig config) throws Exception {
        ReentrantLock lock = instanceLocks.get(name);
        if (!lock.tryLock()) return;
        try (RconClient rcon = new RconClient(config.rconHost, config.rconPort, config.rconPassword)) {
            String statusJson = rcon.sendCommand("ron-status");
            JsonObject status = gson.fromJson(statusJson, JsonObject.class);

            InstanceState state = InstanceState.parse(status.get("state").getAsString());
            String currentMap = status.has("map") ? status.get("map").getAsString() : "";
            int rtsPlayers = status.has("rtsPlayers") ? status.get("rtsPlayers").getAsInt() : 0;
            long gameSeconds = status.has("gameSeconds") ? status.get("gameSeconds").getAsLong() : 0;
            int spectatorCount = status.has("spectatorCount") ? status.get("spectatorCount").getAsInt() : 0;

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
            if (maps == null || maps.isEmpty()) {
                maps = fetchMaps(rcon);
                if (!maps.isEmpty()) {
                    cachedMaps.put(name, maps);
                    logger.info("[{}] Cached {} maps", name, maps.size());
                }
            }

            InstanceState prevState = instances.containsKey(name) ? instances.get(name).state : InstanceState.OFFLINE;
            instances.put(name, new InstanceInfo(state, currentMap, rtsPlayers, spectatorCount, playerNames, gameSeconds, maps, matchResult));

            if (state != prevState) {
                logger.info("[{}] {} -> {}", name, prevState, state);
                handleStateTransition(name, config, prevState, state, matchResult, ranked);
            }
        } finally {
            lock.unlock();
        }
    }

    private void handleStateTransition(String name, InstanceConfig config,
                                       InstanceState prev, InstanceState next,
                                       MatchResultData matchResult, boolean ranked) {
        switch (next) {
            case READY -> {
                if (matchService != null) {
                    matchService.consumeReadyPayload(name).ifPresent(payload -> sendReadyPayload(name, payload));
                }
                if (messageHandler != null) {
                    messageHandler.sendInstanceReady(name);
                }
            }
            case RUNNING -> {
                if (matchService != null) matchService.onRunning(name);
            }
            case FINISHED -> handleFinished(name, matchResult, ranked);
            case IDLE -> {
                if (prev != InstanceState.OFFLINE && prev != InstanceState.IDLE && prev != InstanceState.PREPARING) {
                    cachedMaps.remove(name);
                }
                // PREPARING -> IDLE is the brief post-map-swap window before the instance
                // settles into READY. Don't abandon the assigned match here; the READY
                // transition is imminent.
                if (matchService != null && prev != InstanceState.PREPARING) {
                    matchService.onIdle(name);
                }
            }
            default -> { /* no-op for OFFLINE, PREPARING transitions */ }
        }
    }

    private void sendReadyPayload(String name, MatchService.ReadyPayload payload) {
        logger.info("[{}] Ready, sending deferred data", name);
        if (payload.scores() != null && !payload.scores().isEmpty()) {
            sendPlayerScores(name, payload.scores());
        }
        if (payload.mode() != null && shouldSetMode(name, payload.mode())) {
            try {
                sendRconCommand(name, "ron-setmode " + payload.mode());
                logger.info("[{}] Set mode to: {}", name, payload.mode());
            } catch (Exception e) {
                logger.error("[{}] Failed to set mode: {}", name, payload.mode(), e);
            }
        }
        if (payload.isPrivate()) {
            try {
                sendRconCommand(name, "ron-setprivate true");
                logger.info("[{}] Marked as private match", name);
            } catch (Exception e) {
                logger.error("[{}] Failed to set private flag", name, e);
            }
        }
        // Proxy-owned ranked decision — overrides instance's heuristic.
        try {
            sendRconCommand(name, "ron-setranked " + payload.ranked());
            logger.info("[{}] Ranked: {}", name, payload.ranked());
        } catch (Exception e) {
            logger.warn("[{}] Failed to set ranked flag (instance may be on old version): {}", name, e.getMessage());
        }
    }

    private boolean shouldSetMode(String instanceName, String mode) {
        if (mode == null) return false;
        InstanceInfo info = instances.get(instanceName);
        if (info == null) return true;
        List<MapInfo> maps = info.maps;
        String currentMap = info.currentMap;
        if (maps != null && currentMap != null && !currentMap.isEmpty()) {
            for (MapInfo m : maps) {
                if (m.folder().equals(currentMap) && m.modes().size() <= 1) {
                    return false; // single-mode map; instance derives from rtsmap.json
                }
            }
        }
        return true;
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
                        modeObj.get("maxPlayers").getAsInt()
                    ));
                }
            }

            maps.add(new MapInfo(folder, name, modes));
        }
        return maps;
    }

    private static final int FINISHED_GRACE_SECONDS = 10;

    private void handleFinished(String instanceName, MatchResultData matchResult, boolean ranked) {
        if (matchService != null && !matchService.markFinished(instanceName)) {
            return; // already handled
        }

        Optional<Match> match = matchService != null
                ? matchService.onFinished(instanceName, ranked)
                : Optional.empty();

        if (match.isPresent()) {
            logger.info("[{}] Match finished (ranked={}), processing results", instanceName, ranked);
            if (ranked && matchResult != null && statsDAO != null) {
                MatchResultsWriter.write(statsDAO, matchResult, rankSyncService, logger);
            } else if (!ranked) {
                logger.info("[{}] Unranked match — skipping score updates", instanceName);
            }
        } else {
            logger.warn("[{}] FINISHED with no Match record (orphan) — sending players back to lobby anyway", instanceName);
        }

        // Give players a few seconds on the scoreboard before pulling them back.
        poller.schedule(() -> {
            if (playerRouter != null) {
                playerRouter.transferAllFromServer(instanceName, "lobby");
            }
            if (activeMatchTracker != null) {
                activeMatchTracker.clearMatch(instanceName);
            }
            resetInstance(instanceName);
        }, FINISHED_GRACE_SECONDS, TimeUnit.SECONDS);
    }

    void resetInstance(String instanceName) {
        resetInstance(instanceName, 1);
    }

    /**
     * Stand down a confirmed-but-not-running match (player dropped out during prep).
     * Marks the proxy-side Match as ABANDONED, clears active-match tracking, and
     * sends {@code ron-reset} to the instance to drop it back to IDLE.
     */
    public void cancelPendingMatch(String instanceName) {
        if (matchService != null) matchService.cancelMatch(instanceName);
        if (activeMatchTracker != null) activeMatchTracker.clearMatch(instanceName);
        resetInstance(instanceName);
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
                String response;
                try (RconClient rcon = new RconClient(config.rconHost, config.rconPort, config.rconPassword)) {
                    response = rcon.sendCommand("ron-reset");
                } catch (Exception e) {
                    if (attempt < maxAttempts) {
                        logger.warn("[{}] Reset attempt {}/{} failed, retrying in 10s", instanceName, attempt, maxAttempts);
                        resetInstance(instanceName, attempt + 1);
                    } else {
                        logger.error("[{}] Reset failed after {} attempts", instanceName, maxAttempts, e);
                    }
                    return;
                }

                ResetResult parsed = parseResetResponse(response);
                if (!parsed.ok()) {
                    logger.warn("[{}] Reset rejected by instance: reason='{}', state={}",
                            instanceName, parsed.reason(), parsed.state());
                    if (attempt < maxAttempts) {
                        resetInstance(instanceName, attempt + 1);
                    }
                    return;
                }

                InstanceState newState = parsed.state() != null ? parsed.state() : InstanceState.IDLE;
                logger.info("[{}] Reset acknowledged, committing state={}", instanceName, newState);
                commitState(instanceName, newState);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (held) lock.unlock();
            }
        }, attempt == 1 ? 5 : 10, TimeUnit.SECONDS);
    }

    private record ResetResult(boolean ok, InstanceState state, String reason) {}

    private ResetResult parseResetResponse(String response) {
        if (response == null) return new ResetResult(false, null, "empty response");
        String trimmed = response.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonObject obj = gson.fromJson(trimmed, JsonObject.class);
                boolean ok = obj.has("ok") && obj.get("ok").getAsBoolean();
                InstanceState state = obj.has("state") ? InstanceState.parse(obj.get("state").getAsString()) : null;
                String reason = obj.has("reason") ? obj.get("reason").getAsString() : null;
                return new ResetResult(ok, state, reason);
            } catch (Exception ignored) { }
        }
        // Legacy plain-text response — treat any non-empty success as ok with IDLE.
        if (trimmed.toLowerCase().contains("idle")) {
            return new ResetResult(true, InstanceState.IDLE, null);
        }
        return new ResetResult(false, null, trimmed);
    }

    /**
     * Commit a state change from outside the poll loop (e.g. after a command returned the new state).
     * Triggers the same transition handling as a polled update.
     */
    public void commitState(String instanceName, InstanceState newState) {
        InstanceInfo prev = instances.get(instanceName);
        InstanceState prevState = prev != null ? prev.state : InstanceState.OFFLINE;
        if (prevState == newState) return;

        InstanceInfo updated = prev != null
            ? new InstanceInfo(newState,
                newState == InstanceState.IDLE ? "" : prev.currentMap,
                newState == InstanceState.IDLE ? 0 : prev.rtsPlayers,
                newState == InstanceState.IDLE ? 0 : prev.spectatorCount,
                newState == InstanceState.IDLE ? List.of() : prev.playerNames,
                newState == InstanceState.IDLE ? 0 : prev.gameSeconds,
                prev.maps,
                newState == InstanceState.IDLE ? null : prev.matchResult)
            : new InstanceInfo(newState, "", 0, 0, List.of(), 0, List.of(), null);
        instances.put(instanceName, updated);
        logger.info("[{}] {} -> {} (committed)", instanceName, prevState, newState);
        handleStateTransition(instanceName, configs.get(instanceName), prevState, newState, null, false);
    }

    public boolean loadMap(String instanceName, String mapFolder) {
        try {
            sendRconCommand(instanceName, "ron-loadmap " + mapFolder);
            logger.info("[{}] Loading map: {}", instanceName, mapFolder);
            InstanceInfo prev = instances.get(instanceName);
            List<MapInfo> mapsCache = prev != null ? prev.maps : List.of();
            instances.put(instanceName, new InstanceInfo(InstanceState.PREPARING, "", 0, 0, List.of(), 0,
                mapsCache, null));
            cachedMaps.remove(instanceName);
            return true;
        } catch (Exception e) {
            logger.error("[{}] Failed to load map {}", instanceName, mapFolder, e);
            return false;
        }
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

    private boolean sendPlayerScores(String instanceName, Map<String, Integer> scores) {
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
            if (!info.state.isAvailableForMatch()) continue;

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
            if (!info.state.isAvailableForMatch()) continue;

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
    private Optional<MatchResult> findMatch(int playerCount, String preferredMode) {
        List<MatchResult> preferred = new ArrayList<>();
        List<MatchResult> fallback = new ArrayList<>();

        Map<String, InstanceInfo> snap = Map.copyOf(instances);
        for (var entry : snap.entrySet()) {
            String name = entry.getKey();
            InstanceInfo info = entry.getValue();
            if (!info.state.isAvailableForMatch()) continue;

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

    // --- Info accessors ---

    public Map<String, InstanceInfo> getAllInstances() { return Map.copyOf(instances); }

    public int getSpectatorCount(String instanceName) {
        InstanceInfo info = instances.get(instanceName);
        return info != null ? info.spectatorCount : 0;
    }

    private int getAvailableCount() {
        return (int) instances.values().stream()
                .filter(i -> i.state.isAvailableForMatch()).count();
    }

    private int getRunningCount() {
        return (int) instances.values().stream()
                .filter(i -> i.state == InstanceState.RUNNING).count();
    }

    private int getMinPlayers() {
        return instances.values().stream().flatMap(i -> i.maps.stream())
                .flatMap(m -> m.modes().stream())
                .mapToInt(ModeInfo::minPlayers).min().orElse(2);
    }

    private int getMaxPlayers() {
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
            inst.addProperty("status", i.state.name());
            inst.addProperty("map", i.currentMap);
            inst.addProperty("mapCount", i.maps.size());
            inst.addProperty("spectatorCount", i.spectatorCount);
            inst.addProperty("maxSpectators", MAX_SPECTATORS_PER_INSTANCE);
            instancesObj.add(entry.getKey(), inst);

            if (i.state == InstanceState.RUNNING) {
                JsonObject match = new JsonObject();
                match.addProperty("map", i.currentMap);
                match.addProperty("players", i.rtsPlayers);
                match.add("playerNames", gson.toJsonTree(i.playerNames));
                match.addProperty("elapsedSeconds", i.gameSeconds);
                if (matchService != null) {
                    Optional<Match> m = matchService.matchOn(entry.getKey());
                    m.ifPresent(mm -> {
                        match.addProperty("matchId", mm.id());
                        match.addProperty("mode", mm.mode());
                        match.addProperty("ranked", mm.ranked());
                        match.addProperty("startedAt", mm.startedAt());
                    });
                }
                matches.add(entry.getKey(), match);
            }
        }

        info.add("matches", matches);
        info.add("instances", instancesObj);

        if (queueMirror != null) {
            var qSnap = queueMirror.snapshot();
            JsonObject queue = new JsonObject();
            queue.addProperty("phase", qSnap.phase());
            queue.add("publicQueue", gson.toJsonTree(qSnap.publicQueue()));
            queue.add("nextQueue", gson.toJsonTree(qSnap.nextQueue()));
            JsonArray lobbies = new JsonArray();
            for (var pl : qSnap.privateLobbies()) {
                JsonObject lobby = new JsonObject();
                lobby.addProperty("code", pl.code());
                lobby.addProperty("hostName", pl.hostName());
                lobby.add("playerNames", gson.toJsonTree(pl.playerNames()));
                lobbies.add(lobby);
            }
            queue.add("privateLobbies", lobbies);
            queue.addProperty("updatedAt", qSnap.updatedAt());
            info.add("queue", queue);
        }

        return info;
    }
}
