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

/**
 * 客户端哈希管理器。
 * 用于计算和管理客户端区域文件的哈希值和时间戳信息，
 * 以便与服务端的生成缓存进行比较，决定同步策略。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>扫描客户端地图目录，计算所有区域文件的CRC32哈希</li>
 *   <li>使用缓存的时间戳避免文件修改时间变化导致的误同步</li>
 *   <li>使用共享 ForkJoinPool 提高大量区域文件的哈希计算效率</li>
 * </ul>
 *
 * <p>同步逻辑：</p>
 * <ul>
 *   <li>哈希匹配 → 跳过同步（文件内容相同）</li>
 *   <li>哈希不匹配 + 客户端时间戳较旧 → 同步</li>
 * </ul>
 */
public class ClientHashManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHashManager.class);

    /** 共享的 ForkJoinPool，限制 2 个线程避免阻塞游戏 */
    private static final ForkJoinPool SHARED_POOL = new ForkJoinPool(2);

    /**
     * 客户端元数据记录：时间戳(秒) + CRC32哈希。
     *
     * @param timestampSeconds 区域文件的时间戳（秒）
     * @param hash 区域文件的CRC32哈希值（8位十六进制）
     */
    public record ClientMeta(long timestampSeconds, String hash) {}

    /**
     * 收集所有区域的修改时间戳和哈希值。
     * 用于与服务端的生成缓存进行比较。
     *
     * <p>同步逻辑：</p>
     * <ul>
     *   <li>哈希匹配 → 跳过同步（文件内容相同）</li>
     *   <li>哈希不匹配 + 客户端时间戳较旧 → 同步</li>
     * </ul>
     *
     * <p>使用上次同步时缓存的（存储在 sync_timestamps.cache 中）
     * 时间戳，避免文件写入后修改时间变化导致的问题。</p>
     *
     * <p>使用并行处理（限制2个线程）避免在计算大量区域哈希时阻塞游戏。</p>
     *
     * @param mapDir 要扫描的目录：
     *               - 单维度同步时使用 mw$worldId 目录
     *               - 全维度同步时使用 Multiplayer_<server> 目录
     * @return 相对路径到 ClientMeta（时间戳秒 + 哈希）的映射
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

        // 使用共享的 ForkJoinPool（限制2个线程）避免阻塞游戏和重复创建开销
        try {
            SHARED_POOL.submit(() ->
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
        }

        LOGGER.info("Found {} regions with metadata", metaMap.size());

        return metaMap;
    }

    /**
     * 从给定路径查找服务器目录（Multiplayer_<server>）。
     * 适用于基础目录和 mw$worldId 目录两种情况。
     *
     * @param mapDir 起始目录路径
     * @return 服务器目录路径，如果未找到返回 null
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
     * 获取文件修改时间（毫秒）。
     *
     * @param path 文件路径
     * @return 修改时间（毫秒），如果获取失败返回 0
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
     * 计算文件内容的 CRC32 哈希值（使用 HashUtils）。
     *
     * @param filePath 文件路径
     * @return CRC32 哈希值（8位十六进制字符串）
     */
    private static String computeFileHash(Path filePath) {
        return HashUtils.computeFileHash(filePath);
    }

    /**
     * 构建服务器格式的相对路径。
     * 将 Xaero 的维度名称转换为 Minecraft 维度名称。
     * 移除 mw$worldId 目录层级。
     *
     * <p>支持 caves/<layer> 目录结构：</p>
     * <ul>
     *   <li>地表：xaero_dim/regionX_regionZ</li>
     *   <li>洞穴：xaero_dim/caves/layer/regionX_regionZ</li>
     * </ul>
     *
     * <p>重要修复：确保 xaeroDim 使用正确的 Xaero 格式（namespace$path）：</p>
     * <ul>
     *   <li>如果目录名包含 $，说明已经是正确格式</li>
     *   <li>如果不包含，尝试从缓存反向查找正确格式</li>
     *   <li>使用 DimensionPathMapping 进行转换</li>
     * </ul>
     *
     * @param zipPath zip 文件路径
     * @param serverDir Multiplayer_<server> 目录
     * @return 服务器格式的相对路径（不含 .zip 扩展名）
     *         格式匹配服务端 GenerationCache：dim/regionX_regionZ 或 dim/caves/layer/regionX_regionZ
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
     * 确保维度名使用正确的 Xaero 格式。
     * 处理以下情况：
     * <ul>
     *   <li>原版维度（null、DIM-1、DIM1）直接返回</li>
     *   <li>已包含 $ 的正确格式直接返回</li>
     *   <li>错误的格式尝试从缓存或映射表转换</li>
     * </ul>
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

    /**
     * 关闭共享的 ForkJoinPool。
     * 在客户端离开服务器或停止时调用，释放资源。
     */
    public static void shutdown() {
        if (!SHARED_POOL.isShutdown()) {
            SHARED_POOL.shutdown();
            LOGGER.debug("ClientHashManager shared ForkJoinPool shutdown");
        }
    }
}