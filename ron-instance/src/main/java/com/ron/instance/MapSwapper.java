package com.ron.instance;

import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

public class MapSwapper {

    private static final String FLAG_FILE = "pending-map.txt";
    private static volatile String swappedMapFolder = null;

    public static boolean didSwapMap() {
        return swappedMapFolder != null;
    }

    public static String getSwappedMapFolder() {
        return swappedMapFolder;
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        swappedMapFolder = null;
        Path serverRoot = Paths.get(".").toAbsolutePath().normalize();
        Path flagFile = serverRoot.resolve(FLAG_FILE);

        if (!Files.exists(flagFile)) {
            RonInstance.LOGGER.info("No pending map — booting with existing world");
            return;
        }

        try {
            String mapName = Files.readString(flagFile).trim();
            RonInstance.LOGGER.info("Pending map detected: {}", mapName);

            Path mapsPool = Paths.get(RonInstanceConfig.MAPS_POOL_PATH.get()).toAbsolutePath().normalize();
            Path sourceMap = mapsPool.resolve(mapName);

            if (!Files.exists(sourceMap) || !Files.isDirectory(sourceMap)) {
                RonInstance.LOGGER.error("Map not found in pool: {}", sourceMap);
                Files.deleteIfExists(flagFile);
                return;
            }

            Path worldDir = serverRoot.resolve("world");
            Path serverConfig = worldDir.resolve("serverconfig");
            Path tempConfig = serverRoot.resolve("serverconfig-backup");

            // Preserve serverconfig (contains PCF/Forge mod configs needed for proxy connections)
            if (Files.exists(serverConfig)) {
                RonInstance.LOGGER.info("Preserving serverconfig...");
                if (Files.exists(tempConfig)) deleteDirectory(tempConfig);
                copyDirectory(serverConfig, tempConfig);
            }

            // Delete existing world
            if (Files.exists(worldDir)) {
                RonInstance.LOGGER.info("Deleting existing world directory...");
                deleteDirectory(worldDir);
            }

            // Copy map to world directory
            RonInstance.LOGGER.info("Copying map {} to world/", mapName);
            copyDirectory(sourceMap, worldDir);

            // Restore serverconfig
            if (Files.exists(tempConfig)) {
                RonInstance.LOGGER.info("Restoring serverconfig...");
                if (Files.exists(serverConfig)) deleteDirectory(serverConfig);
                copyDirectory(tempConfig, serverConfig);
                deleteDirectory(tempConfig);
            }

            // Delete flag file
            Files.deleteIfExists(flagFile);
            swappedMapFolder = mapName;
            RonInstance.LOGGER.info("Map swap complete — {} is ready", mapName);

        } catch (IOException e) {
            RonInstance.LOGGER.error("Map swap failed", e);
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
