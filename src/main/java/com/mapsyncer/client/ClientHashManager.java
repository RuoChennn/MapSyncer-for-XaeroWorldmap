package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class ClientHashManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHashManager.class);

    /**
     * Collect modification timestamps for all regions.
     * Used to compare with server generation timestamps.
     * If client timestamp >= server timestamp, skip sync (client has newer data).
     *
     * @param mapDir the mw$worldId directory
     * @return map of relative path -> modification timestamp (milliseconds, comparison uses seconds)
     */
    public static Map<String, Long> computeTimestampsForSync(Path mapDir) {
        Map<String, Long> timestamps = new HashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return timestamps;
        }

        // Get the dimension directory (null, DIM-1, etc.)
        Path dimDir = mapDir.getParent(); // mw$worldId -> null/DIM-1/etc
        if (dimDir == null) {
            return timestamps;
        }

        Path serverDir = dimDir.getParent(); // null -> Multiplayer_<server>
        if (serverDir == null) {
            return timestamps;
        }

        try (Stream<Path> walk = Files.walk(dimDir)) {
            walk.filter(p -> p.toString().endsWith(".zip"))
                    .forEach(zipPath -> {
                        try {
                            // Extract region coordinates from filename
                            String fileName = zipPath.getFileName().toString();
                            if (!fileName.endsWith(".zip")) return;

                            String coords = fileName.substring(0, fileName.length() - 4);
                            String[] parts = coords.split("_");
                            if (parts.length != 2) return;

                            // Get file modification time
                            long timestamp = getFileModificationTime(zipPath);
                            String relativePath = serverDir.relativize(zipPath).toString();
                            relativePath = relativePath.replace("\\", "/");
                            // Remove .zip extension for comparison with server format
                            relativePath = relativePath.substring(0, relativePath.length() - 4);
                            timestamps.put(relativePath, timestamp);

                            LOGGER.debug("Region {}: client timestamp {}", relativePath, timestamp);

                        } catch (NumberFormatException e) {
                            LOGGER.warn("Invalid region filename: {}", zipPath);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to compute map timestamps", e);
        }

        LOGGER.info("Found {} regions with timestamps", timestamps.size());

        return timestamps;
    }

    /**
     * Get file modification time in milliseconds.
     */
    private static long getFileModificationTime(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime time = attrs.lastModifiedTime();
            return time.toMillis();
        } catch (IOException e) {
            LOGGER.error("Failed to get modification time for {}", path, e);
            return 0;
        }
    }

    /**
     * Get detailed information about missing chunks for a region.
     *
     * @param regionFile the region zip file
     * @return set of missing chunk coordinates (0-63)
     */
    public static Set<Integer> getMissingChunksInfo(Path regionFile) {
        try {
            return RegionMerger.findMissingChunks(regionFile);
        } catch (IOException e) {
            LOGGER.error("Failed to get missing chunks info for {}", regionFile, e);
            return new HashSet<>();
        }
    }
}