package com.mapsyncer.client;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
 *
 * <p>线程配置：</p>
 * <ul>
 *   <li>线程数通过客户端配置 ModConfig.CLIENT 控制</li>
 *   <li>默认使用 JVM 可用处理器数的一半</li>
 *   <li>可在游戏内通过配置界面调整</li>
 * </ul>
 */
public class ClientHashManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHashManager.class);

    /** 共享的 ForkJoinPool（延迟初始化，支持配置更改时重建） */
    private static volatile ForkJoinPool sharedPool;

    /** 当前 pool 使用的线程数（用于检测配置更改） */
    private static volatile int currentPoolThreads;

    /** 正在使用共享 pool 的计算任务数 */
    private static final AtomicInteger poolUsers = new AtomicInteger(0);

    /** 默认线程数（可用处理器数的一半） */
    private static final int DEFAULT_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    /**
     * 获取配置的哈希计算线程数。
     *
     * <p>如果客户端配置未初始化，使用默认值。</p>
     *
     * @return 线程数
     */
    private static int getConfiguredThreads() {
        try {
            return PlatformManager.getPlatform().getClientHashThreads();
        } catch (Exception e) {
            // 配置未初始化或平台未就绪，使用默认值
            LOGGER.debug("ClientConfig not initialized, using default threads: {}", DEFAULT_THREADS);
            return DEFAULT_THREADS;
        }
    }

    /**
     * 获取共享的 ForkJoinPool。
     *
     * <p>使用延迟初始化，并根据配置动态调整线程数。</p>
     * <p>如果配置更改，会重建 pool 以使用新的线程数。</p>
     *
     * @return 共享的 ForkJoinPool
     */
    private static ForkJoinPool getSharedPool() {
        int configuredThreads = getConfiguredThreads();

        // 如果 pool 未创建或配置已更改，重建 pool
        if (sharedPool == null || sharedPool.isShutdown() || currentPoolThreads != configuredThreads) {
            synchronized (ClientHashManager.class) {
                // 双重检查
                if (sharedPool == null || sharedPool.isShutdown() || currentPoolThreads != configuredThreads) {
                    // 关闭旧的 pool（如果存在）
                    if (sharedPool != null && !sharedPool.isShutdown()) {
                        sharedPool.shutdown();
                        try {
                            if (!sharedPool.awaitTermination(5, TimeUnit.SECONDS)) {
                                sharedPool.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            sharedPool.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                        LOGGER.info("Shutting down old ForkJoinPool (threads={})", currentPoolThreads);
                    }

                    // 创建新的 pool
                    sharedPool = new ForkJoinPool(configuredThreads);
                    currentPoolThreads = configuredThreads;
                    LOGGER.info("Created new ForkJoinPool with {} threads (configured via client settings)", configuredThreads);
                }
            }
        }

        return sharedPool;
    }

    /**
     * 是否有哈希元数据计算正在进行。
     */
    public static boolean isComputingMeta() {
        return poolUsers.get() > 0;
    }

    /**
     * 收集所有区域的修改时间戳和哈希值（同步，阻塞调用线程）。
     *
     * @see #computeMetaForSyncAsync(Path, Consumer)
     */
    public static Map<String, ClientMeta> computeMetaForSync(Path mapDir) {
        poolUsers.incrementAndGet();
        try {
            MetaScanResult result = computeMetaForSyncWorker(mapDir, false);
            if (!result.isSuccess()) {
                throw new IllegalStateException("Hash scan failed: " + result.failureReason());
            }
            return result.meta();
        } finally {
            poolUsers.decrementAndGet();
        }
    }

    /**
     * 异步收集区域元数据，不阻塞调用线程；计算期间通过 {@link SyncProgressTracker} 显示进度。
     *
     * @param mapDir 要扫描的目录
     * @param onComplete 完成回调（在后台线程调用，调用方应调度到主线程后再发网络包）
     */
    public static void computeMetaForSyncAsync(Path mapDir, Consumer<MetaScanResult> onComplete) {
        poolUsers.incrementAndGet();
        getSharedPool().submit(() -> {
            try {
                onComplete.accept(computeMetaForSyncWorker(mapDir, true));
            } catch (Exception e) {
                LOGGER.error("Failed to compute hashes asynchronously", e);
                onComplete.accept(MetaScanResult.failure("async_error", 0));
            } finally {
                SyncProgressTracker.completeHashScan();
                poolUsers.decrementAndGet();
            }
        });
    }

    private static MetaScanResult computeMetaForSyncWorker(Path mapDir, boolean reportProgress) {
        Map<String, ClientMeta> metaMap = new ConcurrentHashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return MetaScanResult.ok(metaMap);
        }

        Path serverDir = findServerDir(mapDir);
        if (serverDir == null) {
            LOGGER.error("Could not resolve Multiplayer server directory from {}", mapDir);
            return MetaScanResult.failure("server_dir", 0);
        }

        // Load cached timestamps from previous sync
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        Map<String, TimestampHashEntry> cachedTimestamps = tsCache.getAll();
        LOGGER.info("Loaded {} cached timestamps from previous sync", cachedTimestamps.size());

        // Collect all zip files from the specified directory (not entire server)
        java.util.List<Path> zipFiles;
        try (Stream<Path> walk = Files.walk(mapDir)) {
            zipFiles = walk.filter(p -> p.toString().endsWith(".zip"))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to walk map directory {}", mapDir, e);
            return MetaScanResult.failure("walk_error", 0);
        }

        LOGGER.info("Computing hashes for {} region files in {} (parallel threads={})", zipFiles.size(), mapDir, currentPoolThreads);

        if (reportProgress && !zipFiles.isEmpty()) {
            SyncProgressTracker.startHashScan(zipFiles.size());
        }

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failedFiles = new AtomicInteger();
        AtomicInteger lastReportedPercent = new AtomicInteger(-1);
        int totalFiles = zipFiles.size();

        ForkJoinPool pool = getSharedPool();
        try {
            pool.submit(() ->
                    zipFiles.parallelStream()
                            .forEach(zipPath -> {
                                try {
                                    String fileName = zipPath.getFileName().toString();
                                    if (!fileName.endsWith(".zip")) return;

                                    String relativePath = buildRelativePath(zipPath, serverDir);
                                    TimestampHashEntry cached = cachedTimestamps.get(relativePath);
                                    String hash = resolveSyncHash(zipPath, cached);

                                    long timestampSeconds = resolveSyncTimestamp(zipPath, cached);
                                    if (cached != null) {
                                        LOGGER.debug("Region {}: ts={}s, hash={} (cache-first)",
                                                relativePath, timestampSeconds, hash);
                                    } else {
                                        LOGGER.debug("Region {}: ts={}s, hash={} (no cache)",
                                                relativePath, timestampSeconds, hash);
                                    }

                                    metaMap.put(relativePath, new ClientMeta(timestampSeconds, hash));

                                } catch (Exception e) {
                                    failedFiles.incrementAndGet();
                                    LOGGER.warn("Failed to hash region file: {}", zipPath, e);
                                } finally {
                                    if (reportProgress && totalFiles > 0) {
                                        int done = processed.incrementAndGet();
                                        int percent = (done * 100) / totalFiles;
                                        int prev = lastReportedPercent.get();
                                        if (done == totalFiles || percent >= prev + 10) {
                                            lastReportedPercent.set(percent);
                                            SyncProgressTracker.updateHashScan(done, totalFiles);
                                        }
                                    }
                                }
                            })
            ).join();
        } catch (Exception e) {
            LOGGER.error("Failed to compute hashes in parallel", e);
            return MetaScanResult.failure("parallel_error", failedFiles.get());
        }

        if (failedFiles.get() > 0) {
            LOGGER.error("Hash scan completed with {} failed file(s) out of {}", failedFiles.get(), totalFiles);
            return MetaScanResult.failure("partial_error", failedFiles.get());
        }

        addMissingCacheEntries(metaMap, cachedTimestamps, collectDimPrefixes(mapDir, serverDir));

        LOGGER.info("Found {} regions with metadata", metaMap.size());

        return MetaScanResult.ok(metaMap);
    }

    /**
     * 计算 region 同步 hash：zip 存在且合法时优先 cache（服务端对齐版本）；否则 DEFAULT 触发补传。
     */
    private static String resolveSyncHash(Path zipPath, TimestampHashEntry cached) {
        if (zipPath == null || !Files.exists(zipPath) || !HashUtils.isValidRegionZip(zipPath)) {
            if (cached != null) {
                LOGGER.warn("Region {} missing or invalid on disk, will request re-sync",
                        zipPath != null ? zipPath.getFileName() : "unknown");
            }
            return HashUtils.DEFAULT_HASH;
        }
        if (cached != null && HashUtils.isValidHash(cached.hash())) {
            return cached.hash();
        }
        return HashUtils.computeFileHash(zipPath);
    }

    private static long resolveSyncTimestamp(Path zipPath, TimestampHashEntry cached) {
        long fileTs = getFileModificationTime(zipPath) / 1000;
        if (cached != null) {
            return Math.max(cached.timestampSeconds(), fileTs);
        }
        return fileTs;
    }

    /**
     * 缓存中有记录但磁盘无对应 zip 时，加入 DEFAULT_HASH 元数据以触发补传。
     */
    private static void addMissingCacheEntries(Map<String, ClientMeta> metaMap,
            Map<String, TimestampHashEntry> cachedTimestamps, Set<String> dimPrefixes) {
        if (dimPrefixes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, TimestampHashEntry> entry : cachedTimestamps.entrySet()) {
            String key = entry.getKey();
            if (metaMap.containsKey(key)) {
                continue;
            }
            for (String prefix : dimPrefixes) {
                if (key.startsWith(prefix)) {
                    metaMap.put(key, new ClientMeta(entry.getValue().timestampSeconds(), HashUtils.DEFAULT_HASH));
                    LOGGER.warn("Region {} in cache but file missing, will request re-sync", key);
                    break;
                }
            }
        }
    }

    /** 根据扫描目录推断需要补全的维度前缀（如 {@code null/}）。 */
    private static Set<String> collectDimPrefixes(Path mapDir, Path serverDir) {
        Set<String> prefixes = new java.util.HashSet<>();
        Path current = mapDir;
        while (current != null && !current.equals(serverDir)) {
            String name = current.getFileName() != null ? current.getFileName().toString() : "";
            if (name.startsWith("mw$")) {
                Path dimDir = current.getParent();
                if (dimDir != null) {
                    prefixes.add(dimDir.getFileName().toString() + "/");
                }
                break;
            }
            current = current.getParent();
        }
        if (prefixes.isEmpty() && mapDir.equals(serverDir)) {
            try (Stream<Path> dirs = Files.list(serverDir)) {
                dirs.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> !n.startsWith("_"))
                        .forEach(n -> prefixes.add(n + "/"));
            } catch (IOException e) {
                LOGGER.warn("Failed to list dimension dirs under {}", serverDir, e);
            }
        }
        return prefixes;
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
        synchronized (ClientHashManager.class) {
            if (poolUsers.get() > 0) {
                LOGGER.debug("Deferring ForkJoinPool shutdown, {} active hash computations", poolUsers.get());
                return;
            }
            ForkJoinPool pool = sharedPool;
            if (pool != null && !pool.isShutdown()) {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                sharedPool = null;
                LOGGER.debug("ClientHashManager shared ForkJoinPool shutdown (threads={})", currentPoolThreads);
            }
        }
    }
}
