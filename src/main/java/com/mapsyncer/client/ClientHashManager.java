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
     * @param mapDir the mw$worldId directory
     * @return map of relative path -> ClientMeta (timestamp in seconds + hash)
     */
    public static Map<String, ClientMeta> computeMetaForSync(Path mapDir) {
        Map<String, ClientMeta> metaMap = new HashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return metaMap;
        }

        // Get the dimension directory (null, DIM-1, etc.)
        Path dimDir = mapDir.getParent(); // mw$worldId -> null/DIM-1/etc
        if (dimDir == null) {
            return metaMap;
        }

        Path serverDir = dimDir.getParent(); // null -> Multiplayer_<server>
        if (serverDir == null) {
            return metaMap;
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

                            // Get file modification time (seconds)
                            long timestampMillis = getFileModificationTime(zipPath);
                            long timestampSeconds = timestampMillis / 1000;

                            // Compute CRC32 hash
                            String hash = computeFileHash(zipPath);

                            String relativePath = serverDir.relativize(zipPath).toString();
                            relativePath = relativePath.replace("\\", "/");
                            // Remove .zip extension for comparison with server format
                            relativePath = relativePath.substring(0, relativePath.length() - 4);

                            metaMap.put(relativePath, new ClientMeta(timestampSeconds, hash));

                            LOGGER.debug("Region {}: ts={}s, hash={}", relativePath, timestampSeconds, hash);

                        } catch (NumberFormatException e) {
                            LOGGER.warn("Invalid region filename: {}", zipPath);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to compute map metadata", e);
        }

        LOGGER.info("Found {} regions with metadata", metaMap.size());

        return metaMap;
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
}