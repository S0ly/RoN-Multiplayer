package com.ron.instance;

import com.google.gson.*;
import com.ron.common.modes.ModeCatalog;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class InstanceStateManager {

    // Supported modes (team / ffa / coop) live in the shared ModeCatalog so the
    // instance and the proxy validate against the exact same set.

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

    /**
     * Validate a single mode entry against the shared {@link ModeCatalog}. The
     * mode name must be a known mode (team / ffa / coop) and its team/player
     * layout in rtsmap.json must match the catalog's spec.
     *
     * Returns null and logs a warning when the mode is rejected.
     */
    private static ModeInfo parseMode(String folder, String modeName, int teamCount, int totalPlayers) {
        ModeCatalog.ModeSpec spec = ModeCatalog.get(modeName);
        if (spec == null) {
            RonInstance.LOGGER.warn("rtsmap.json in {}: unsupported mode '{}' — allowed: {}", folder, modeName, ModeCatalog.namesHint());
            return null;
        }
        if (teamCount != spec.teamCount() || totalPlayers != spec.players()) {
            RonInstance.LOGGER.warn("rtsmap.json in {}: '{}' requires {} teams totaling {} players (got teams={}, players={}) — skipping",
                    folder, modeName, spec.teamCount(), spec.players(), teamCount, totalPlayers);
            return null;
        }
        return new ModeInfo(spec.name(), spec.players(), spec.teamCount());
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
