package com.ron.instance;

import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Map swap runs at the END of the previous JVM (ServerStoppedEvent), AFTER
 * Minecraft's final save + level close + session.lock release. This way the
 * next JVM boots and reads the swapped level.dat normally — preserving the
 * map's world border, gamerules, weather, time, etc.
 *
 * If the previous JVM crashed before completing the swap (or pending-map.txt
 * was dropped manually before any clean shutdown), onServerAboutToStart falls
 * back to running the swap in-process on the next boot.
 */
public class MapSwapper {

    private static final String PENDING_FLAG = "pending-map.txt";
    private static final String COMPLETED_FLAG = "swapped-map.txt";

    // Top-level folders in a source map that hold per-player state (gamemode, inventory,
    // stats, advancements). Skipped during copy so map-author residue doesn't leak into matches.
    private static final Set<String> SKIP_TOP_LEVEL = Set.of("playerdata", "stats", "advancements");

    private static volatile String swappedMapFolder = null;

    public static boolean didSwapMap() {
        return swappedMapFolder != null;
    }

    public static String getSwappedMapFolder() {
        return swappedMapFolder;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        Path serverRoot = Paths.get(".").toAbsolutePath().normalize();
        Path pendingFlag = serverRoot.resolve(PENDING_FLAG);

        if (!Files.exists(pendingFlag)) return;

        try {
            String mapName = Files.readString(pendingFlag).trim();
            RonInstance.LOGGER.info("Map swap triggered on server stop: {}", mapName);

            Path mapsPool = Paths.get(RonInstanceConfig.MAPS_POOL_PATH.get()).toAbsolutePath().normalize();
            Path sourceMap = mapsPool.resolve(mapName);

            if (!Files.exists(sourceMap) || !Files.isDirectory(sourceMap)) {
                RonInstance.LOGGER.error("Map not found in pool: {}", sourceMap);
                Files.deleteIfExists(pendingFlag);
                return;
            }

            performSwap(serverRoot, sourceMap, mapName);

            Files.writeString(serverRoot.resolve(COMPLETED_FLAG), mapName);
            Files.deleteIfExists(pendingFlag);
            RonInstance.LOGGER.info("Map swap complete on shutdown — {} ready for next boot", mapName);

        } catch (IOException e) {
            RonInstance.LOGGER.error("Map swap failed on shutdown", e);
        }
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        swappedMapFolder = null;
        Path serverRoot = Paths.get(".").toAbsolutePath().normalize();
        Path completedFlag = serverRoot.resolve(COMPLETED_FLAG);
        Path pendingFlag = serverRoot.resolve(PENDING_FLAG);

        try {
            if (Files.exists(completedFlag)) {
                swappedMapFolder = Files.readString(completedFlag).trim();
                Files.deleteIfExists(pendingFlag);
                Files.delete(completedFlag);
                RonInstance.LOGGER.info("Detected completed swap from previous JVM: {}", swappedMapFolder);
                return;
            }

            if (!Files.exists(pendingFlag)) {
                RonInstance.LOGGER.info("No pending map — booting with existing world");
                return;
            }

            String mapName = Files.readString(pendingFlag).trim();
            RonInstance.LOGGER.warn("pending-map.txt present at boot — previous JVM did not complete swap; running in-process");

            Path mapsPool = Paths.get(RonInstanceConfig.MAPS_POOL_PATH.get()).toAbsolutePath().normalize();
            Path sourceMap = mapsPool.resolve(mapName);

            if (!Files.exists(sourceMap) || !Files.isDirectory(sourceMap)) {
                RonInstance.LOGGER.error("Map not found in pool: {}", sourceMap);
                Files.deleteIfExists(pendingFlag);
                return;
            }

            performSwap(serverRoot, sourceMap, mapName);
            Files.deleteIfExists(pendingFlag);
            swappedMapFolder = mapName;
            RonInstance.LOGGER.info("Map swap complete (fallback path) — {} is ready", mapName);

        } catch (IOException e) {
            RonInstance.LOGGER.error("Map swap failed on boot", e);
        }
    }

    private static void performSwap(Path serverRoot, Path sourceMap, String mapName) throws IOException {
        Path worldDir = serverRoot.resolve("world");
        Path serverConfig = worldDir.resolve("serverconfig");
        Path tempConfig = serverRoot.resolve("serverconfig-backup");

        // Preserve serverconfig (contains PCF/Forge mod configs needed for proxy connections)
        if (Files.exists(serverConfig)) {
            RonInstance.LOGGER.info("Preserving serverconfig...");
            if (Files.exists(tempConfig)) deleteDirectory(tempConfig);
            copyDirectory(serverConfig, tempConfig);
        }

        if (Files.exists(worldDir)) {
            RonInstance.LOGGER.info("Deleting existing world directory...");
            deleteDirectory(worldDir);
        }

        RonInstance.LOGGER.info("Copying map {} to world/", mapName);
        copyDirectory(sourceMap, worldDir);

        if (Files.exists(tempConfig)) {
            RonInstance.LOGGER.info("Restoring serverconfig...");
            if (Files.exists(serverConfig)) deleteDirectory(serverConfig);
            copyDirectory(tempConfig, serverConfig);
            deleteDirectory(tempConfig);
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (source.equals(dir.getParent()) && SKIP_TOP_LEVEL.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
