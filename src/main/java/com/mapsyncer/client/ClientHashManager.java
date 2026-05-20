package com.mapsyncer.client;

import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
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
     * Uses cached timestamps from previous sync (stored in sync_timestamps.cache)
     * to avoid issues where file modification time changes after write.
     *
     * Uses parallel processing with limited concurrency (2 threads) to avoid
     * blocking the game while computing hashes for many regions.
     *
     * @param mapDir the directory to scan:
     *               - mw$worldId directory for single dimension sync
     *               - Multiplayer_<server> directory for all dimensions sync
     * @return map of relative path -> ClientMeta (timestamp in seconds + hash)
     */
    public static Map<String, ClientMeta> computeMetaForSync(Path mapDir) {
        Map<String, ClientMeta> metaMap = new ConcurrentHashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return metaMap;
        }

        // Determine the server directory (Multiplayer_<server>) for cache lookup
        Path serverDir = findServerDir(mapDir);
        if (serverDir == null) {
            LOGGER.warn("Could not find server directory from {}", mapDir);
            return metaMap;
        }

        // Load cached timestamps from previous sync
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        Map<String, ClientTimestampCache.CacheEntry> cachedTimestamps = tsCache.getAll();
        LOGGER.info("Loaded {} cached timestamps from previous sync", cachedTimestamps.size());

        // Collect all zip files from the specified directory (not entire server)
        java.util.List<Path> zipFiles;
        try (Stream<Path> walk = Files.walk(mapDir)) {
            zipFiles = walk.filter(p -> p.toString().endsWith(".zip"))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to walk map directory", e);
            return metaMap;
        }

        LOGGER.info("Computing hashes for {} region files in {} (parallel=2)", zipFiles.size(), mapDir);

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

                                    // Build relative path in server format (using serverDir as base)
                                    // This ensures path format matches server's GenerationCache
                                    String relativePath = buildRelativePath(zipPath, serverDir);

                                    // Compute CRC32 hash
                                    String hash = computeFileHash(zipPath);

                                    // Use cached timestamp if available (from previous sync)
                                    // This avoids issues where file modification time changes
                                    ClientTimestampCache.CacheEntry cached = cachedTimestamps.get(relativePath);
                                    long timestampSeconds;
                                    if (cached != null) {
                                        timestampSeconds = cached.timestampSeconds();
                                        LOGGER.debug("Region {}: using cached ts={}s, hash={}",
                                                relativePath, timestampSeconds, hash);
                                    } else {
                                        // No cached timestamp, use file modification time
                                        long timestampMillis = getFileModificationTime(zipPath);
                                        timestampSeconds = timestampMillis / 1000;
                                        LOGGER.debug("Region {}: using file ts={}s, hash={} (no cache)",
                                                relativePath, timestampSeconds, hash);
                                    }

                                    metaMap.put(relativePath, new ClientMeta(timestampSeconds, hash));

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
     * Compute CRC32 hash of file content (uses HashUtils).
     * @param filePath file path
     * @return CRC32 hash (8 hex digits)
     */
    private static String computeFileHash(Path filePath) {
        return HashUtils.computeFileHash(filePath);
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
     * Build relative path in server format.
     * Converts Xaero's dimension names to Minecraft dimension names.
     * Removes mw$worldId directory level.
     *
     * 支持 caves/<layer> 目录结构：
     * - 地表：xaero_dim/regionX_regionZ
     * - 洞穴：xaero_dim/caves/layer/regionX_regionZ
     *
     * 重要修复：确保 xaeroDim 使用正确的 Xaero 格式（namespace$path）
     * - 如果目录名包含 $，说明已经是正确格式
     * - 如果不包含，尝试从缓存反向查找正确格式
     * - 使用 DimensionPathMapping 进行转换
     *
     * @param zipPath the zip file path
     * @param serverDir the Multiplayer_<server> directory
     * @return relative path in server format (without .zip extension)
     *         Format matches server's GenerationCache: dim/regionX_regionZ or dim/caves/layer/regionX_regionZ
     */
    private static String buildRelativePath(Path zipPath, Path serverDir) {
        // Get relative path from server directory
        String relative = serverDir.relativize(zipPath).toString();
        relative = relative.replace("\\", "/");

        // Remove .zip extension
        if (relative.endsWith(".zip")) {
            relative = relative.substring(0, relative.length() - 4);
        }

        // Parse path components
        // 客户端路径格式：
        // 地表：dimension/mw$worldId/regionX_regionZ (3 parts)
        // 洞穴：dimension/mw$worldId/caves/layer/regionX_regionZ (5 parts)
        String[] parts = relative.split("/");
        if (parts.length < 3) {
            LOGGER.warn("Unexpected path format: {}", relative);
            return relative;
        }

        String dirName = parts[0];  // 目录名（可能是正确的 Xaero 格式，也可能是错误的）
        String regionCoords = parts[parts.length - 1];  // Last part is regionX_regionZ

        // 检查是否有 caves 层
        // 客户端洞穴路径：dimension/mw$worldId/caves/layer/regionX_regionZ
        // caves 在 parts[2]（因为 mw$worldId 在 parts[1]）
        int caveLayer = Integer.MAX_VALUE;
        boolean hasCaves = false;
        for (int i = 1; i < parts.length - 2; i++) {
            if (parts[i].equals("caves") && i + 1 < parts.length - 1) {
                hasCaves = true;
                try {
                    caveLayer = Integer.parseInt(parts[i + 1]);
                    LOGGER.debug("Found caves layer {} at index {} in path: {}", caveLayer, i, relative);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid cave layer at index {} in path: {}", i + 1, relative);
                }
                break;
            }
        }

        if (hasCaves) {
            LOGGER.debug("Path has caves layer: {}", relative);
        }

        // 关键修复：确保 xaeroDim 使用正确的 Xaero 格式
        // 目录名可能是：
        // 1. 正确的 Xaero 格式：twilightforest$twilight_forest（包含 $）
        // 2. 原版维度：null, DIM-1, DIM1
        // 3. 错误的格式：twilight_forest（缺少 namespace）
        String xaeroDim = ensureCorrectXaeroFormat(dirName, serverDir);

        // Build path in server format (matches GenerationCache key format)
        String serverPath;
        if (caveLayer == Integer.MAX_VALUE) {
            // 地表层：xaero_dim/regionX_regionZ
            serverPath = xaeroDim + "/" + regionCoords;
        } else {
            // 洞穴层：xaero_dim/caves/layer/regionX_regionZ
            serverPath = xaeroDim + "/caves/" + caveLayer + "/" + regionCoords;
        }

        LOGGER.debug("buildRelativePath: {} -> {} (dirName={}, xaeroDim={})", relative, serverPath, dirName, xaeroDim);
        return serverPath;
    }

    /**
     * 确保维度名使用正确的 Xaero 格式
     *
     * @param dirName 目录名（可能是正确的 Xaero 格式，也可能是错误的）
     * @param serverDir 服务器目录（用于查找缓存）
     * @return 正确的 Xaero 格式维度名
     */
    private static String ensureCorrectXaeroFormat(String dirName, Path serverDir) {
        // 原版维度直接返回
        if (dirName.equals("null") || dirName.equals("DIM-1") || dirName.equals("DIM1")) {
            return dirName;
        }

        // 如果已经包含 $，说明是正确的 namespace$path 格式
        if (dirName.contains("$")) {
            return dirName;
        }

        // 如果是 DIM{id} 格式（传统格式），直接返回
        if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
            return dirName;
        }

        // 尝试从缓存反向查找正确的格式
        // 缓存键格式：xaeroDim/regionX_regionZ
        // 我们需要找到包含 dirName 的缓存键
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        for (String cacheKey : tsCache.getAll().keySet()) {
            int slashIndex = cacheKey.indexOf('/');
            if (slashIndex > 0) {
                String cachedDim = cacheKey.substring(0, slashIndex);
                // 检查缓存中的 xaeroDim 是否匹配 dirName
                // 缓存中的格式：namespace$path，dirName 可能是 path 部分
                if (cachedDim.contains("$")) {
                    String pathPart = cachedDim.substring(cachedDim.indexOf('$') + 1);
                    if (pathPart.equals(dirName)) {
                        LOGGER.info("Found correct xaeroDim from cache: {} -> {}", dirName, cachedDim);
                        return cachedDim;
                    }
                }
            }
        }

        // 尝试使用 DimensionPathMapping 转换
        // 注意：toXaeroDimension 对于没有 namespace 的名字可能无法正确转换
        String converted = DimensionPathMapping.getInstance().toXaeroDimension(dirName);
        if (!converted.equals(dirName)) {
            LOGGER.info("Converted xaeroDim via mapping: {} -> {}", dirName, converted);
            return converted;
        }

        // 无法转换，返回原始值（可能导致同步问题，但会记录日志）
        LOGGER.warn("Could not convert dirName '{}' to correct Xaero format, sync may fail", dirName);
        return dirName;
    }
}