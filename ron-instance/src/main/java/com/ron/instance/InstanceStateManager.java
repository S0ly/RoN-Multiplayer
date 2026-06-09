package com.ron.instance;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class InstanceStateManager {

    // Fixed team modes: name -> total players (teamCount is always 2)
    private static final Map<String, Integer> TEAM_MODES = Map.of(
            "1v1", 2,
            "2v2", 4,
            "3v3", 6,
            "4v4", 8
    );

    // Per-size FFA modes: name -> player count (each player is their own team)
    private static final Map<String, Integer> FFA_MODES = Map.of(
            "ffa_3", 3,
            "ffa_4", 4,
            "ffa_5", 5,
            "ffa_6", 6,
            "ffa_7", 7,
            "ffa_8", 8
    );

    // Per-size coop modes: name -> player count (everyone on one team)
    private static final Map<String, Integer> COOP_MODES = Map.of(
            "coop_2", 2,
            "coop_3", 3,
            "coop_4", 4,
            "coop_5", 5,
            "coop_6", 6,
            "coop_7", 7,
            "coop_8", 8
    );

    private static volatile InstanceState state = InstanceState.IDLE;
    private static volatile String currentMap = "unknown";
    private static volatile String currentMode = null;
    private static final List<MapInfo> availableMaps = new ArrayList<>();

    public record ModeInfo(String name, int players, int teamCount) {}
    public record MapInfo(String folder, String name, List<String> author, List<ModeInfo> modes, String defaultMode) {}

    public static InstanceState getState() {
        return state;
    }

    public static void setState(InstanceState newState) {
        RonInstance.LOGGER.info("State: {} -> {}", state, newState);
        state = newState;
    }

    public static String getCurrentMap() {
        return currentMap;
    }

    public static void setCurrentMap(String map) {
        currentMap = map;
    }

    public static String getCurrentMode() {
        return currentMode;
    }

    public static void setCurrentMode(String mode) {
        currentMode = mode;
        RonInstance.LOGGER.info("Mode set to: {}", mode);
    }

    public static List<MapInfo> getAvailableMaps() {
        return Collections.unmodifiableList(availableMaps);
    }

    public static int getExpectedPlayersForMode(String mapFolder, String mode) {
        for (MapInfo map : availableMaps) {
            if (map.folder().equals(mapFolder) || map.name().equals(mapFolder)) {
                for (ModeInfo m : map.modes()) {
                    if (m.name().equals(mode)) {
                        return m.players();
                    }
                }
                // Mode not found, try default
                for (ModeInfo m : map.modes()) {
                    if (m.name().equals(map.defaultMode())) {
                        return m.players();
                    }
                }
            }
        }
        return 2;
    }

    public static void scanMaps() {
        availableMaps.clear();
        String mapsPath = RonInstanceConfig.MAPS_POOL_PATH.get();
        Path pool = Paths.get(mapsPath).toAbsolutePath().normalize();

        if (!Files.exists(pool)) {
            RonInstance.LOGGER.warn("Maps directory does not exist: {}", pool);
            return;
        }

        try (var dirs = Files.list(pool)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                String folderName = dir.getFileName().toString();
                Path rtsMapFile = dir.resolve("rtsmap.json");

                if (!Files.exists(rtsMapFile)) {
                    RonInstance.LOGGER.warn("Skipping map folder without rtsmap.json: {}", folderName);
                    return;
                }

                try {
                    String json = Files.readString(rtsMapFile);
                    MapInfo mapInfo = parseRtsMap(folderName, json);
                    if (mapInfo != null) {
                        availableMaps.add(mapInfo);
                        RonInstance.LOGGER.info("Found map: {} ({} modes, default: {})",
                                mapInfo.name(), mapInfo.modes().size(), mapInfo.defaultMode());
                    }
                } catch (IOException e) {
                    RonInstance.LOGGER.error("Failed to read rtsmap.json in {}", folderName, e);
                }
            });
        } catch (IOException e) {
            RonInstance.LOGGER.error("Failed to scan maps directory", e);
        }

        RonInstance.LOGGER.info("Scanned {} maps", availableMaps.size());
    }

    private static final String ALLOWED_MODES_HINT =
            "ffa_3..ffa_8, coop_2..coop_8, 1v1, 2v2, 3v3, 4v4";

    /**
     * Validate a single mode entry against the whitelist:
     *   - {@code ffa_<n>} with {@code n} ∈ {3, 4}: each player on their own team
     *   - {@code coop_<n>} with {@code n} ∈ {2, 3, 4}: everyone on one team
     *   - {@code 1v1}, {@code 2v2}, {@code 3v3}, {@code 4v4}: 2 teams of N
     *
     * Returns null and logs a warning when the mode is rejected.
     */
    private static ModeInfo parseMode(String folder, String modeName, int teamCount, int totalPlayers) {
        String lower = modeName.toLowerCase();

        Integer ffaPlayers = FFA_MODES.get(lower);
        if (ffaPlayers != null) {
            if (teamCount != ffaPlayers || totalPlayers != ffaPlayers) {
                RonInstance.LOGGER.warn("rtsmap.json in {}: '{}' requires {} solo teams of 1 player (got teams={}, players={}) — skipping",
                        folder, modeName, ffaPlayers, teamCount, totalPlayers);
                return null;
            }
            return new ModeInfo(modeName, ffaPlayers, teamCount);
        }
        if (lower.startsWith("ffa")) {
            RonInstance.LOGGER.warn("rtsmap.json in {}: invalid ffa mode '{}', expected ffa_<n> with n in 3..8 — skipping", folder, modeName);
            return null;
        }

        Integer coopPlayers = COOP_MODES.get(lower);
        if (coopPlayers != null) {
            if (teamCount != 1 || totalPlayers != coopPlayers) {
                RonInstance.LOGGER.warn("rtsmap.json in {}: '{}' requires 1 team of {} players (got teams={}, players={}) — skipping",
                        folder, modeName, coopPlayers, teamCount, totalPlayers);
                return null;
            }
            return new ModeInfo(modeName, coopPlayers, 1);
        }
        if (lower.startsWith("coop")) {
            RonInstance.LOGGER.warn("rtsmap.json in {}: invalid coop mode '{}', expected coop_<n> with n in 2..8 — skipping", folder, modeName);
            return null;
        }

        Integer expectedTotal = TEAM_MODES.get(lower);
        if (expectedTotal != null) {
            if (teamCount != 2 || totalPlayers != expectedTotal) {
                RonInstance.LOGGER.warn("rtsmap.json in {}: '{}' requires 2 teams of {} players (got teams={}, players={}) — skipping",
                        folder, modeName, expectedTotal / 2, teamCount, totalPlayers);
                return null;
            }
            return new ModeInfo(modeName, expectedTotal, 2);
        }

        RonInstance.LOGGER.warn("rtsmap.json in {}: unsupported mode '{}' — allowed: {}", folder, modeName, ALLOWED_MODES_HINT);
        return null;
    }

    private static MapInfo parseRtsMap(String folder, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (!root.has("name") || !root.has("startPositions") || !root.has("modes") || !root.has("defaultMode")) {
                RonInstance.LOGGER.warn("rtsmap.json in {} missing required fields (name, startPositions, modes, defaultMode)", folder);
                return null;
            }

            String name = root.get("name").getAsString();
            String defaultMode = root.get("defaultMode").getAsString();

            List<String> author = new ArrayList<>();
            if (root.has("author")) {
                for (JsonElement el : root.getAsJsonArray("author")) {
                    author.add(el.getAsString());
                }
            }

            JsonObject modesObj = root.getAsJsonObject("modes");
            List<ModeInfo> modes = new ArrayList<>();

            for (var entry : modesObj.entrySet()) {
                String modeName = entry.getKey();
                JsonArray teams = entry.getValue().getAsJsonArray();

                int teamCount = teams.size();
                int totalPlayers = 0;
                for (JsonElement team : teams) {
                    totalPlayers += team.getAsJsonArray().size();
                }

                ModeInfo mode = parseMode(folder, modeName, teamCount, totalPlayers);
                if (mode != null) modes.add(mode);
            }

            if (modes.isEmpty()) {
                RonInstance.LOGGER.warn("rtsmap.json in {} has no valid modes", folder);
                return null;
            }

            boolean defaultExists = modes.stream().anyMatch(m -> m.name().equals(defaultMode));
            if (!defaultExists) {
                RonInstance.LOGGER.warn("rtsmap.json in {} has defaultMode '{}' not in valid modes", folder, defaultMode);
                return null;
            }

            return new MapInfo(folder, name, author, modes, defaultMode);
        } catch (Exception e) {
            RonInstance.LOGGER.error("Failed to parse rtsmap.json in {}", folder, e);
            return null;
        }
    }
}
