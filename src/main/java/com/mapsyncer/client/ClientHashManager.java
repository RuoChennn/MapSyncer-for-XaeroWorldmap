package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;
import java.util.zip.CRC32;

public class ClientHashManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHashManager.class);

    /**
     * Region客户端元数据：时间戳(秒) + CRC32哈希
     */
    public record ClientMeta(long timestampSeconds, String hash) {}

    /**
     * Collect modification timestamps and hashes for all regions.
     * Used to compare with server generation cache.
     * Sync logic:
     * - Hash match → skip sync (file content identical)
     * - Hash mismatch + client timestamp older → sync
     *
     * Uses parallel processing with limited concurrency (2 threads) to avoid
     * blocking the game while computing hashes for many regions.
     *
     * @param mapDir the base directory (Multiplayer_<server>/null) or mw$worldId directory
     * @return map of relative path -> ClientMeta (timestamp in seconds + hash)
     */
    public static Map<String, ClientMeta> computeMetaForSync(Path mapDir) {
        Map<String, ClientMeta> metaMap = new ConcurrentHashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return metaMap;
        }

        // Determine the server directory (Multiplayer_<server>)
        Path serverDir = findServerDir(mapDir);
        if (serverDir == null) {
            LOGGER.warn("Could not find server directory from {}", mapDir);
            return metaMap;
        }

        // Collect all zip files first
        java.util.List<Path> zipFiles;
        try (Stream<Path> walk = Files.walk(serverDir)) {
            zipFiles = walk.filter(p -> p.toString().endsWith(".zip"))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to walk map directory", e);
            return metaMap;
        }

        LOGGER.info("Computing hashes for {} region files (parallel=2)", zipFiles.size());

        // Process with limited parallelism (2 threads) to avoid blocking game
        ForkJoinPool limitedPool = new ForkJoinPool(2);
        try {
            limitedPool.submit(() ->
                    zipFiles.parallelStream()
                            .forEach(zipPath -> {
                                try {
                                    // Extract region coordinates from filename
                                    String fileName = zipPath.getFileName().toString();
                                    if (!fileName.endsWith(".zip")) return;

                                    // Get file modification time (seconds)
                                    long timestampMillis = getFileModificationTime(zipPath);
                                    long timestampSeconds = timestampMillis / 1000;

                                    // Compute CRC32 hash
                                    String hash = computeFileHash(zipPath);

                                    // Build relative path in server format
                                    String relativePath = buildRelativePath(zipPath, serverDir);

                                    metaMap.put(relativePath, new ClientMeta(timestampSeconds, hash));

                                    LOGGER.debug("Region {}: ts={}s, hash={}", relativePath, timestampSeconds, hash);

                                } catch (Exception e) {
                                    LOGGER.warn("Invalid region filename: {}", zipPath, e);
                                }
                            })
            ).get();  // Wait for completion
        } catch (Exception e) {
            LOGGER.error("Failed to compute hashes in parallel", e);
        } finally {
            limitedPool.shutdown();
        }

        LOGGER.info("Found {} regions with metadata", metaMap.size());

        return metaMap;
    }

    /**
     * Find the server directory (Multiplayer_<server>) from a given path.
     * Works with both base directory and mw$worldId directory.
     */
    private static Path findServerDir(Path mapDir) {
        Path current = mapDir;

        // Walk up the directory tree to find Multiplayer_<server>
        while (current != null) {
            String name = current.getFileName() != null ? current.getFileName().toString() : "";
            if (name.startsWith("Multiplayer_")) {
                return current;
            }
            current = current.getParent();
        }

        return null;
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
     * Compute CRC32 hash of file content.
     * @param filePath file path
     * @return CRC32 hash (8 hex digits)
     */
    private static String computeFileHash(Path filePath) {
        if (!Files.exists(filePath)) {
            return "00000000";
        }

        try {
            CRC32 crc32 = new CRC32();
            byte[] data = Files.readAllBytes(filePath);
            crc32.update(data);
            return String.format("%08x", crc32.getValue());
        } catch (IOException e) {
            LOGGER.warn("Failed to compute hash for {}", filePath, e);
            return "00000000";
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

    /**
     * Build relative path in server format: dimension/regionX_regionZ
     * Converts Xaero's dimension names to Minecraft dimension names:
     * - null → overworld
     * - DIM-1 → the_nether
     * - DIM1 → the_end
     * Removes mw$worldId directory level.
     *
     * @param zipPath the zip file path
     * @param serverDir the Multiplayer_<server> directory
     * @return relative path in server format (without .zip extension)
     */
    private static String buildRelativePath(Path zipPath, Path serverDir) {
        // Get relative path from server directory
        String relative = serverDir.relativize(zipPath).toString();
        relative = relative.replace("\\", "/");

        // Remove .zip extension
        if (relative.endsWith(".zip")) {
            relative = relative.substring(0, relative.length() - 4);
        }

        // Parse path components: dimension/mw$worldId/regionX_regionZ
        String[] parts = relative.split("/");
        if (parts.length < 3) {
            LOGGER.warn("Unexpected path format: {}", relative);
            return relative;
        }

        String xaeroDim = parts[0];
        String regionCoords = parts[parts.length - 1];  // Last part is regionX_regionZ

        // Convert Xaero dimension to Minecraft dimension
        String mcDim = convertXaeroDimension(xaeroDim);

        // Build server format: dimension/regionX_regionZ
        return mcDim + "/" + regionCoords;
    }

    /**
     * Convert Xaero's dimension directory name to Minecraft dimension name.
     * - null → overworld
     * - DIM-1 → the_nether
     * - DIM1 → the_end
     * - others → keep as-is
     */
    private static String convertXaeroDimension(String xaeroDim) {
        switch (xaeroDim) {
            case "null":
                return "overworld";
            case "DIM-1":
                return "the_nether";
            case "DIM1":
                return "the_end";
            default:
                return xaeroDim;
        }
    }
}