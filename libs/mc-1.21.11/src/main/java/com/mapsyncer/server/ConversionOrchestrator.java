package com.mapsyncer.server;

import com.mapsyncer.config.ConversionOutputPaths;
import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.RegionGenerationPlanner;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.config.TimeoutConfig;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import com.mapsyncer.mca.RegionConverterStandalone.LayerConvertedRegion;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import com.mapsyncer.server.RegionScanner.DimensionRegions;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.DimensionTypeHelper;
import com.mapsyncer.util.NamedThreadFactory;
import com.mapsyncer.util.XaeroPathResolver;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * 转换协调器 - 协调区域转换流水线：扫描 → 转换 → 写入
 *
 * 支持三种转换模式：
 * - 全量转换：转换所有维度的所有区域
 * - 单维度转换：转换指定维度的所有区域
 * - 单区域转换：转换指定维度的单个区域
 *
 * 使用时间戳缓存检测需要更新的区域，避免重复处理未变化的文件。
 * 支持增量更新，仅处理时间戳变化的MCA文件。
 */
public class ConversionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversionOrchestrator.class);

    /** 并发转换线程池 */
    private static volatile ExecutorService conversionExecutor = null;

    /** 是否正在运行转换任务 */
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    /** 已处理的区域数量（原子变量，支持并发更新） */
    private static final AtomicInteger processedCountAtomic = new AtomicInteger(0);

    /** 已处理的区域数量（兼容旧代码） */
    private static volatile int processedCount = 0;

    /** 跳过的区域数量（时间戳未变化，原子操作安全） */
    private static final AtomicInteger skippedCount = new AtomicInteger(0);

    /** 实际写入缓存的区域数量（不含跳过） */
    private static final AtomicInteger convertedCountAtomic = new AtomicInteger(0);

    /** 转换阶段发现无有效区块内容的区域数量 */
    private static final AtomicInteger skippedEmptyContentCount = new AtomicInteger(0);

    /** 总区域数量 */
    private static volatile int totalCount = 0;

    /** 当前状态描述 */
    private static volatile String currentStatus = "idle";

    /** 当前正在处理的维度 */
    private static volatile ResourceKey<Level> currentDimension = null;

    /** 已完成的维度列表（用于全量生成完成提示，线程安全） */
    private static final List<String> completedDimensions = new CopyOnWriteArrayList<>();

    /** 缓存输出目录 */
    private static final Path DEFAULT_CACHE_DIR = Path.of("server_map_cache");
    private static volatile Path effectiveCacheDir = null;

    public static Path getCacheDir() {
        return effectiveCacheDir != null ? effectiveCacheDir : DEFAULT_CACHE_DIR;
    }

    public static void setCacheDir(Path dir) {
        effectiveCacheDir = dir;
        LOGGER.info("Cache directory set to: {}", dir);
    }

    /**
     * 初始化内置服务器缓存目录。
     * 仅当非独立服务器时生效，复用 Xaero 客户端地图目录避免二次转换。
     */
    public static void tryInitIntegratedServerCache(MinecraftServer server, Path gameDir) {
        if (!server.isDedicatedServer()) {
            String worldName = server.getWorldPath(LevelResource.ROOT).getParent().getFileName().toString();
            setCacheDir(XaeroPathResolver.getWorldMapDir(gameDir).resolve(worldName));
        }
        // 清理历史残留的 .zip.temp 文件
        XaeroWriter.cleanStaleTempFiles(getCacheDir());
    }

    /** 时间戳缓存实例 */
    private static McaTimestampCache timestampCache;

    /**
     * 单区域生成结果状态
     */
    public enum SingleRegionResult {
        /** 成功 */
        SUCCESS,
        /** 区域未找到 */
        REGION_NOT_FOUND,
        /** 转换失败 */
        CONVERSION_FAILED,
        /** 已有任务运行 */
        ALREADY_RUNNING
    }

    /**
     * 获取或创建转换线程池
     *
     * 线程池大小由配置 maxConcurrentRegions 决定（0=自动：逻辑处理器数-2，范围 1–16）。
     * MCA 解析和转换是纯文件 IO 操作，不依赖 Minecraft API，
     * 因此可以安全并发执行。
     *
     * @return ExecutorService 线程池实例
     */
    private static ExecutorService getOrCreateExecutor() {
        if (conversionExecutor == null || conversionExecutor.isShutdown()) {
            int maxConcurrent = PlatformManager.getPlatform().getMaxConcurrentRegions();
            conversionExecutor = Executors.newFixedThreadPool(maxConcurrent,
                new NamedThreadFactory("mapsyncer-converter"));
            LOGGER.info("Created conversion thread pool with {} threads (resolved maxConcurrentRegions)", maxConcurrent);
        }
        return conversionExecutor;
    }

    /**
     * 关闭转换线程池
     *
     * 在服务器停止时调用，释放线程资源。
     */
    public static void shutdownExecutor() {
        if (conversionExecutor != null && !conversionExecutor.isShutdown()) {
            conversionExecutor.shutdown();
            try {
                if (!conversionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    conversionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                conversionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.info("Conversion thread pool shut down");
        }
    }

    /**
     * 清除维度缓存目录
     *
     * @param dimCacheDir 维度缓存目录路径
     */
    private static void clearDimensionCache(Path dimCacheDir) {
        if (!Files.exists(dimCacheDir)) {
            LOGGER.info("No existing cache to clear for dimension: {}", dimCacheDir);
            return;
        }

        try {
            try (var files = Files.walk(dimCacheDir)) {
                files.sorted((a, b) -> -a.compareTo(b))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                                LOGGER.debug("Deleted: {}", path);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to delete: {}", path);
                            }
                        });
            }
            LOGGER.info("Cleared cache directory: {}", dimCacheDir);
        } catch (IOException e) {
            LOGGER.error("Failed to clear dimension cache: {}", dimCacheDir, e);
        }
    }

    /**
     * 清除 GenerationCache 中指定维度的记录。
     *
     * @param xaeroDimName Xaero 格式的维度名（如 null, DIM-1, DIM1, namespace$path）
     */
    private static void clearGenerationCacheEntries(String xaeroDimName) {
        int removed = GenerationCache.getInstance(getCacheDir()).removeByPrefix(xaeroDimName + "/");
        if (removed > 0) {
            LOGGER.debug("Cleared {} generation_cache entries for dimension: {}", removed, xaeroDimName);
        } else {
            LOGGER.debug("No generation_cache entries found for dimension: {}", xaeroDimName);
        }
    }

    /**
     * 获取或初始化时间戳缓存
     *
     * @return MCA时间戳缓存实例
     */
    private static McaTimestampCache getTimestampCache() {
        if (timestampCache == null) {
            timestampCache = McaTimestampCache.getInstance(getCacheDir());
        }
        return timestampCache;
    }

    /**
     * 执行全量转换 - 转换服务器所有维度的所有区域
     *
     * @param server Minecraft服务器实例
     */
    public static boolean generateAll(MinecraftServer server) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting generateAll");
            return false;
        }
        processedCount = 0;
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        completedDimensions.clear();  // 重置已完成维度列表

        // Note: caller handles saveEverything on server thread before invoking this method.

        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        totalCount = countTotalWork(server, allRegions);
        int totalSkippedEmpty = allRegions.stream().mapToInt(DimensionRegions::skippedEmptyCount).sum();
        if (totalCount == 0) {
            LOGGER.info("No regions found to convert");
            isRunning.set(false);
            return true;
        }
        LOGGER.info("Starting conversion of {} regions across {} dimensions", totalCount, allRegions.size());
        try {
            for (DimensionRegions dimRegions : allRegions) {
                convertDimension(server, dimRegions, false);
            }
        } finally {
            isRunning.set(false);
            currentStatus = "completed";
            shutdownExecutor();
            LOGGER.info("Conversion completed: {}/{} regions converted, {} skipped (empty MCA at scan)",
                    convertedCountAtomic.get(), totalCount, totalSkippedEmpty);
        }
        return true;
    }

    /**
     * 执行单维度转换 - 转换指定维度的所有区域
     *
     * 使用时间戳缓存检测需要更新的区域，跳过未变化的区域。
     *
     * @param server Minecraft服务器实例
     * @param dimensionId 维度ID（如"minecraft:overworld"）
     */
    public static boolean generateDimension(MinecraftServer server, String dimensionId) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimension");
            return false;
        }
        processedCount = 0;
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); isRunning.set(false); return true; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); isRunning.set(false); return true; }

        // Note: caller handles saveEverything on server thread before invoking this method.

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount(), scanResult.fileEntries()), false);
        } finally {
            isRunning.set(false);
            currentStatus = "completed";
            shutdownExecutor();
        }
        return true;
    }

    /**
     * 执行单维度强制转换 - 强制重新生成指定维度的所有区域
     *
     * 清除维度缓存目录后重新生成所有区域，忽略时间戳缓存。
     *
     * @param server Minecraft服务器实例
     * @param dimensionId 维度ID（如"minecraft:overworld"）
     */
    public static boolean generateDimensionForce(MinecraftServer server, String dimensionId) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimensionForce");
            return false;
        }
        processedCount = 0;
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); isRunning.set(false); return true; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); isRunning.set(false); return true; }

        // 强制生成前先清除该维度的缓存目录和 generation_cache 记录
        String fullDimId = dimKey.identifier().toString(); // 完整维度 ID（包含 namespace）
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path dimCacheDir = getCacheDir().resolve(xaeroDimName);
        clearDimensionCache(dimCacheDir);
        clearGenerationCacheEntries(xaeroDimName);

        // Note: caller handles saveEverything on server thread before invoking this method.

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount(), scanResult.fileEntries()), true);
        } finally {
            isRunning.set(false);
            currentStatus = "completed";
            shutdownExecutor();
        }
        return true;
    }

    /**
     * 检查单个区域的MCA文件是否存在
     *
     * @param server MinecraftServer实例
     * @param dimension 维度ResourceKey
     * @param regionX 区域X坐标
     * @param regionZ 区域Z坐标
     * @return MCA文件路径（如果存在），null表示不存在
     */
    public static Path checkMcaFileExists(MinecraftServer server, ResourceKey<Level> dimension, int regionX, int regionZ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return null;

        Path regionDir = RegionScanner.getRegionDir(level);

        if (regionDir == null) return null;

        Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        return Files.exists(mcaPath) ? mcaPath : null;
    }

    /**
     * 执行单区域转换 - 转换指定维度的单个区域
     *
     * @param server Minecraft服务器实例
     * @param dimension 维度ResourceKey
     * @param regionX 区域X坐标
     * @param regionZ 区域Z坐标
     * @return 转换结果状态
     */
    public static SingleRegionResult generateSingleRegion(MinecraftServer server, ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress");
            return SingleRegionResult.ALREADY_RUNNING;
        }

        // 提前检查 MCA 文件是否存在
        Path mcaPath = checkMcaFileExists(server, dimension, regionX, regionZ);
        if (mcaPath == null) {
            LOGGER.warn("MCA file not found for region ({}, {}) in dimension {}", regionX, regionZ, dimension.identifier().getPath());
            isRunning.set(false);
            return SingleRegionResult.REGION_NOT_FOUND;
        }

        totalCount = 1;
        processedCount = 0;
        currentDimension = dimension;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimension); isRunning.set(false); return SingleRegionResult.CONVERSION_FAILED; }

        // Note: caller handles saveEverything on server thread before invoking this method.

        // 使用完整维度 ID 作为缓存 key（确保新格式路径正确转换）
        String fullDimId = dimension.identifier().toString();
        String dimPath = dimension.identifier().getPath(); // 用于配置查找

        // 从配置获取维度扫描配置
        DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);

        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

        Path regionDir = RegionScanner.getRegionDir(level);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", dimension);
            isRunning.set(false);
            return SingleRegionResult.CONVERSION_FAILED;
        }

        DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
        List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);
        Path baseOutputDir = getCacheDir().resolve(xaeroDimName);

        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, logicalTop={}, passes={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.logicalTopY(), passes.size());

        SingleRegionResult result = SingleRegionResult.SUCCESS;
        try {
            for (RegionScanPass pass : passes) {
                Files.createDirectories(ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer()));
            }
            totalCount = passes.size();
            List<LayerConvertedRegion> converted = RegionConverterStandalone.convertRegionMulti(
                mcaPath, regionX, regionZ, dimTypeInfo, passes, BlockPropertyResolver.INSTANCE);
            int written = 0;
            for (int i = 0; i < passes.size(); i++) {
                RegionScanPass pass = passes.get(i);
                LayerConvertedRegion layer = i < converted.size() ? converted.get(i) : null;
                ConvertedRegion single = layer == null ? null
                    : new ConvertedRegion(layer.regionX(), layer.regionZ(), layer.xaeroData());
                if (EmptyRegionSupport.isEmptyConverted(single)) {
                    continue;
                }
                Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
                XaeroWriter.writeRegionFile(outputDir, single);
                written++;
            }
            processedCount = written;
            if (written == 0) {
                LOGGER.warn("Could not convert region ({}, {}): all passes empty", regionX, regionZ);
                result = SingleRegionResult.CONVERSION_FAILED;
            } else {
                LOGGER.info("Converted single region ({}, {}) with {} passes", regionX, regionZ, written);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write region file", e);
            result = SingleRegionResult.CONVERSION_FAILED;
        }
        finally {
            isRunning.set(false);
            currentStatus = "completed";
        }
        return result;
    }

    /**
     * 转换指定维度的所有区域
     *
     * 根据force参数决定是否强制重新生成所有区域，
     * 或使用时间戳缓存仅处理有变化的区域。
     *
     * @param server Minecraft服务器实例
     * @param dimRegions 维度区域数据
     * @param force 是否强制重新生成
     */
    private static void convertDimension(MinecraftServer server, DimensionRegions dimRegions, boolean force) {
        ServerLevel level = server.getLevel(dimRegions.dimension());
        if (level == null) { LOGGER.error("Level not loaded"); return; }

        currentDimension = dimRegions.dimension();
        String fullDimId = dimRegions.dimension().identifier().toString();
        String dimPath = dimRegions.dimension().identifier().getPath();

        DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);

        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path regionDir = RegionScanner.getRegionDir(level);
        Path baseOutputDir = getCacheDir().resolve(xaeroDimName);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", xaeroDimName);
            return;
        }

        DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
        List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);

        try {
            for (RegionScanPass pass : passes) {
                Files.createDirectories(ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer()));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create output directories under: {}", baseOutputDir, e);
            return;
        }

        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, logicalTop={}, passes={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.logicalTopY(), passes.size());

        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(getCacheDir());
        List<RegionCoords> needsUpdate = force ? dimRegions.regions()
            : (!dimRegions.fileEntries().isEmpty()
                ? mcaCache.classifyUpdates(dimPath, dimRegions.fileEntries())
                : mcaCache.scanAndUpdate(dimPath, regionDir));
        List<RegionCoords> regions = dimRegions.regions();

        totalCount = regions.size() * passes.size();
        LOGGER.info("Dimension {}: {} total regions, {} need update, {} passes/region (force={})",
            dimPath, regions.size(), needsUpdate.size(), passes.size(), force);

        ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();
        processedCountAtomic.set(0);
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        long generationTimeSeconds = System.currentTimeMillis() / 1000;

        ExecutorService executor = getOrCreateExecutor();

        List<java.util.concurrent.Future<?>> futures = submitConversionTasks(
            executor, needsUpdate, regions, regionDir, baseOutputDir, xaeroDimName, dimPath,
            dimTypeInfo, passes, mcaCache, genCache,
            generationTimeSeconds, failedRegions, true);
        waitForCompletion(futures, "Region conversion");

        if (!force) {
            futures = submitNewRegionTasks(
                executor, regions, new HashSet<>(needsUpdate), regionDir, baseOutputDir, xaeroDimName, dimPath,
                dimTypeInfo, passes, mcaCache, genCache,
                generationTimeSeconds, failedRegions);
            waitForCompletion(futures, "New region conversion");
        }

        processedCount = processedCountAtomic.get();

        if (!failedRegions.isEmpty()) {
            LOGGER.warn("Failed to convert {} regions", failedRegions.size());
            for (RegionCoords coords : failedRegions) {
                LOGGER.warn("Failed region: ({}, {})", coords.x(), coords.z());
            }
        }

        LOGGER.info("Dimension {} completed: {} total, {} converted, {} skipped (unchanged), {} skipped (empty MCA at scan), {} skipped (empty content), {} failed",
            dimPath, regions.size(), convertedCountAtomic.get(), skippedCount.get(),
            dimRegions.skippedEmptyCount(), skippedEmptyContentCount.get(), failedRegions.size());

        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimRegions.dimension().identifier().toString());
        completedDimensions.add(friendlyName);

        mcaCache.saveCache();
        genCache.save();
    }

    /**
     * 获取输出目录（根据洞穴层决定路径）
     *
     * @param baseOutputDir 基础输出目录
     * @param caveLayer 洞穴层号（地表层使用 Integer.MAX_VALUE）
     * @return 输出目录路径
     */
    private static Path getOutputDir(Path baseOutputDir, int caveLayer) {
        return ConversionOutputPaths.outputDir(baseOutputDir, caveLayer);
    }

    private static int countTotalWork(MinecraftServer server, List<DimensionRegions> allRegions) {
        int total = 0;
        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) {
                continue;
            }
            String dimPath = dimRegions.dimension().identifier().getPath();
            DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);
            DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
            int passCount = RegionGenerationPlanner.countPasses(scanConfig, dimTypeInfo);
            total += dimRegions.regions().size() * passCount;
        }
        return total;
    }

    /**
     * 提交批量区域转换任务
     *
     * @param executor 线程池
     * @param coordsToProcess 待处理的区域坐标列表
     * @param allRegions 所有区域列表（用于检查）
     * @param regionDir MCA 文件目录
     * @param outputDir 输出目录
     * @param xaeroDimName Xaero 格式维度名
     * @param dimPath 维度路径
     * @param dimTypeInfo 维度类型信息
     * @param lightMode 光照模式
     * @param caveParams 洞穴参数
     * @param caveLayer 洞穴层号
     * @param mcaCache 时间戳缓存
     * @param genCache 生成缓存
     * @param generationTimeSeconds 生成时间戳
     * @param failedRegions 失败区域队列
     * @param logProgress 是否记录进度日志
     * @return 任务 Future 列表
     */
    private static List<java.util.concurrent.Future<?>> submitConversionTasks(
            ExecutorService executor, List<RegionCoords> coordsToProcess, List<RegionCoords> allRegions,
            Path regionDir, Path baseOutputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, List<RegionScanPass> passes,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress) {

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        Set<RegionCoords> validRegions = new HashSet<>(allRegions);

        for (RegionCoords coords : coordsToProcess) {
            if (!validRegions.contains(coords)) continue;

            java.util.concurrent.Future<?> future = executor.submit(() ->
                convertRegionMultiPasses(coords, regionDir, baseOutputDir, xaeroDimName, dimPath,
                    dimTypeInfo, passes, mcaCache, genCache,
                    generationTimeSeconds, failedRegions, logProgress, "Converted")
            );
            futures.add(future);
        }

        return futures;
    }

    /**
     * 提交新增区域转换任务
     *
     * 检查输出文件是否存在，不存在则转换。
     *
     * @param executor 线程池
     * @param allRegions 所有区域列表
     * @param processedRegions 已处理的区域列表
     * @param regionDir MCA 文件目录
     * @param outputDir 输出目录
     * @param xaeroDimName Xaero 格式维度名
     * @param dimPath 维度路径
     * @param dimTypeInfo 维度类型信息
     * @param lightMode 光照模式
     * @param caveParams 洞穴参数
     * @param caveLayer 洞穴层号
     * @param mcaCache 时间戳缓存
     * @param genCache 生成缓存
     * @param generationTimeSeconds 生成时间戳
     * @param failedRegions 失败区域队列
     * @return 任务 Future 列表
     */
    private static List<java.util.concurrent.Future<?>> submitNewRegionTasks(
            ExecutorService executor, List<RegionCoords> allRegions, Set<RegionCoords> processedRegions,
            Path regionDir, Path baseOutputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, List<RegionScanPass> passes,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions) {

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (RegionCoords coords : allRegions) {
            if (processedRegions.contains(coords)) continue;

            boolean allExist = true;
            for (RegionScanPass pass : passes) {
                Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
                if (!XaeroWriter.regionFileExists(outputDir, coords.x(), coords.z())) {
                    allExist = false;
                    break;
                }
            }
            if (allExist) {
                processedCountAtomic.addAndGet(passes.size());
                skippedCount.incrementAndGet();
                LOGGER.debug("Skipped region ({}, {}): all pass outputs exist", coords.x(), coords.z());
                continue;
            }

            java.util.concurrent.Future<?> future = executor.submit(() ->
                convertRegionMultiPasses(coords, regionDir, baseOutputDir, xaeroDimName, dimPath,
                    dimTypeInfo, passes, mcaCache, genCache,
                    generationTimeSeconds, failedRegions, true, "Generated new")
            );
            futures.add(future);
        }

        return futures;
    }

    /**
     * 转换单个区域
     *
     * 读取 MCA 文件、转换、写入 Xaero 格式、更新缓存。
     *
     * @param coords 区域坐标
     * @param regionDir MCA 文件目录
     * @param outputDir 输出目录
     * @param xaeroDimName Xaero 格式维度名
     * @param dimPath 维度路径
     * @param dimTypeInfo 维度类型信息
     * @param lightMode 光照模式
     * @param caveParams 洞穴参数
     * @param caveLayer 洞穴层号
     * @param mcaCache 时间戳缓存
     * @param genCache 生成缓存
     * @param generationTimeSeconds 生成时间戳
     * @param failedRegions 失败区域队列
     * @param logProgress 是否记录进度日志
     * @param logPrefix 日志前缀
     */
    private static void convertRegionMultiPasses(
            RegionCoords coords, Path regionDir, Path baseOutputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, List<RegionScanPass> passes,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress, String logPrefix) {

        Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");

        if (!com.mapsyncer.mca.McaContentProbe.hasAnyChunk(mcaPath)) {
            for (RegionScanPass pass : passes) {
                Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
                String relativePath = ConversionOutputPaths.relativePath(
                    xaeroDimName, pass.caveLayer(), coords.x(), coords.z());
                EmptyRegionSupport.purgeGeneratedArtifacts(
                    outputDir, coords.x(), coords.z(), relativePath, genCache);
            }
            skippedEmptyContentCount.incrementAndGet();
            if (logProgress) {
                processedCountAtomic.addAndGet(passes.size());
            }
            return;
        }

        List<LayerConvertedRegion> converted = RegionConverterStandalone.convertRegionMulti(
            mcaPath, coords.x(), coords.z(), dimTypeInfo, passes, BlockPropertyResolver.INSTANCE);

        if (converted.isEmpty()) {
            failedRegions.add(coords);
            return;
        }

        boolean anyWritten = false;
        boolean anyFailed = false;
        for (int i = 0; i < passes.size(); i++) {
            RegionScanPass pass = passes.get(i);
            LayerConvertedRegion layer = i < converted.size() ? converted.get(i) : null;
            Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
            String relativePath = ConversionOutputPaths.relativePath(
                xaeroDimName, pass.caveLayer(), coords.x(), coords.z());

            ConvertedRegion single = layer == null ? null
                : new ConvertedRegion(layer.regionX(), layer.regionZ(), layer.xaeroData());

            if (EmptyRegionSupport.isEmptyConverted(single)) {
                EmptyRegionSupport.purgeGeneratedArtifacts(
                    outputDir, coords.x(), coords.z(), relativePath, genCache);
                if (logProgress) {
                    processedCountAtomic.incrementAndGet();
                }
                continue;
            }

            try {
                XaeroWriter.RegionWriteResult writeResult = XaeroWriter.writeRegionFile(outputDir, single);
                genCache.update(relativePath, generationTimeSeconds, writeResult.crc32Hash());
                anyWritten = true;
                if (logProgress) {
                    int convertedSoFar = convertedCountAtomic.incrementAndGet();
                    processedCountAtomic.incrementAndGet();
                    String layerLabel = pass.isSurfaceLayer() ? "surface" : String.valueOf(pass.caveLayer());
                    LOGGER.info("{} region ({}, {}) layer={}: {}/{}",
                        logPrefix, coords.x(), coords.z(), layerLabel, convertedSoFar, totalCount);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to write region file for layer {}", pass.caveLayer(), e);
                anyFailed = true;
            }
        }

        if (anyWritten) {
            mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);
        }
        if (anyFailed) {
            failedRegions.add(coords);
        } else if (!anyWritten) {
            skippedEmptyContentCount.incrementAndGet();
        }
    }

    /**
     * 等待所有任务完成
     *
     * @param futures 任务 Future 列表
     * @param taskName 任务名称（用于日志）
     */
    private static void waitForCompletion(List<java.util.concurrent.Future<?>> futures, String taskName) {
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get(TimeoutConfig.TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                LOGGER.warn("{} task timeout", taskName);
            } catch (ExecutionException e) {
                LOGGER.error("{} task failed", taskName, e);
            } catch (InterruptedException e) {
                LOGGER.error("{} task interrupted", taskName, e);
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 解析维度ID为ResourceKey
     *
     * 支持多种输入格式：
     * - 简称：overworld, the_nether, the_end
     * - 全称：minecraft:overworld, minecraft:the_nether
     * - Mod维度ID：twilightforest:twilight_forest
     *
     * @param id 维度ID字符串
     * @param server Minecraft服务器实例
     * @return 维度ResourceKey，无效ID返回null
     */
    public static ResourceKey<Level> parseDimensionId(String id, MinecraftServer server) {
        String normalized = id.toLowerCase();

        // 原版维度标准名称（支持多种输入格式，但内部使用标准名称）
        switch (normalized) {
            case "overworld", "minecraft:overworld":
                return Level.OVERWORLD;
            case "the_nether", "minecraft:the_nether":
                return Level.NETHER;
            case "the_end", "minecraft:the_end":
                return Level.END;
        }

        // 尝试解析为 Identifier 并查找维度
        try {
            Identifier location = Identifier.parse(id);
            // 遍历所有已加载的维度查找匹配
            for (ServerLevel level : server.getAllLevels()) {
                Identifier dimLocation = level.dimension().identifier();
                if (dimLocation.equals(location) ||
                    dimLocation.getPath().equals(id) ||
                    dimLocation.toString().equals(id)) {
                    return level.dimension();
                }
            }
            LOGGER.warn("Dimension not found: {}", id);
        } catch (RuntimeException e) {
            LOGGER.error("Invalid dimension id format '{}'", id, e);
        }

        return null;
    }

    /**
     * 增量扫描快照：在主线程采集维度/路径信息，后台线程仅做磁盘 IO 与转换。
     */
    public record IncrementalScanSnapshot(
            String dimPath,
            String xaeroDimName,
            Path regionDir,
            Path baseOutputDir,
            DimensionTypeInfo dimTypeInfo,
            List<RegionScanPass> passes
    ) {}

    /**
     * 在主线程构建增量扫描快照（访问 ServerLevel / dimensionType）。
     */
    public static List<IncrementalScanSnapshot> buildIncrementalScanSnapshots(MinecraftServer server) {
        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        List<IncrementalScanSnapshot> snapshots = new ArrayList<>();

        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) {
                continue;
            }

            String fullDimId = dimRegions.dimension().identifier().toString();
            String dimPath = dimRegions.dimension().identifier().getPath();

            DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);
            String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

            Path regionDir = RegionScanner.getRegionDir(level);
            if (regionDir == null) {
                continue;
            }

            Path baseOutputDir = getCacheDir().resolve(xaeroDimName);
            DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
            List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);

            snapshots.add(new IncrementalScanSnapshot(
                    dimPath, xaeroDimName, regionDir, baseOutputDir, dimTypeInfo, passes));
        }

        return snapshots;
    }

    /**
     * 执行计划增量扫描 - 扫描所有维度并更新时间戳变化的区域
     *
     * @param server Minecraft服务器实例
     */
    public static void performIncrementalScan(MinecraftServer server) {
        List<IncrementalScanSnapshot> snapshots;
        try {
            snapshots = server.submit(() -> buildIncrementalScanSnapshots(server)).get(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Incremental scan snapshot interrupted");
            return;
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.error("Timed out building incremental scan snapshot on server thread");
            return;
        } catch (ExecutionException e) {
            LOGGER.error("Failed to build incremental scan snapshot on server thread", e.getCause());
            return;
        }
        performIncrementalScan(snapshots);
    }

    /**
     * 基于主线程预采集的快照执行增量扫描（可在后台线程调用）。
     */
    public static void performIncrementalScan(List<IncrementalScanSnapshot> snapshots) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.debug("Conversion already in progress, skipping incremental scan");
            return;
        }

        try {
        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(getCacheDir());
        int totalUpdated = 0;
        totalCount = 0;
        long generationTimeSeconds = System.currentTimeMillis() / 1000;
        ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();
        ExecutorService executor = getOrCreateExecutor();

        for (IncrementalScanSnapshot snapshot : snapshots) {
            String dimPath = snapshot.dimPath();
            Path regionDir = snapshot.regionDir();
            Path baseOutputDir = snapshot.baseOutputDir();
            String xaeroDimName = snapshot.xaeroDimName();
            List<RegionScanPass> passes = snapshot.passes();
            DimensionTypeInfo dimTypeInfo = snapshot.dimTypeInfo();

            java.util.List<RegionCoords> needsUpdate = mcaCache.scanAndUpdate(dimPath, regionDir);

            if (needsUpdate.isEmpty()) {
                LOGGER.debug("No updates needed for dimension {}", dimPath);
                continue;
            }

            LOGGER.info("Dimension {}: {} regions need incremental update (passes={})",
                dimPath, needsUpdate.size(), passes.size());

            try {
                for (RegionScanPass pass : passes) {
                    Files.createDirectories(ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer()));
                }
            } catch (IOException e) {
                LOGGER.error("Failed to create output directories: {}", baseOutputDir, e);
                continue;
            }

            totalCount += needsUpdate.size() * passes.size();
            int failuresBefore = failedRegions.size();
            List<java.util.concurrent.Future<?>> futures = submitConversionTasks(
                executor, needsUpdate, needsUpdate, regionDir, baseOutputDir, xaeroDimName, dimPath,
                dimTypeInfo, passes, mcaCache, genCache,
                generationTimeSeconds, failedRegions, false);
            waitForCompletion(futures, "Incremental update");
            totalUpdated += needsUpdate.size() - (failedRegions.size() - failuresBefore);
        }

        if (totalUpdated > 0) {
            LOGGER.info("Incremental scan completed: {} regions updated", totalUpdated);
            mcaCache.saveCache();
            genCache.save();
        }
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * 检查转换任务是否正在运行
     *
     * @return true表示正在运行，false表示空闲
     */
    public static boolean isRunning() { return isRunning.get(); }

    /**
     * 获取已处理的区域数量
     *
     * @return 已处理数量
     */
    public static int getProcessedCount() { return processedCount; }

    /**
     * 获取总区域数量
     *
     * @return 总数量
     */
    public static int getTotalCount() { return totalCount; }

    /**
     * 获取本次实际更新的区域数量（不含跳过的）
     *
     * @return 实际更新数量
     */
    public static int getUpdatedCount() { return convertedCountAtomic.get(); }

    /**
     * 获取跳过的区域数量（时间戳未变化）
     *
     * @return 跳过数量
     */
    public static int getSkippedCount() { return skippedCount.get(); }

    /**
     * 获取当前状态描述
     *
     * @return 状态字符串
     */
    public static String getStatus() { return currentStatus; }

    /**
     * 获取当前正在处理的维度
     *
     * @return 维度ResourceKey，空闲时返回null
     */
    public static ResourceKey<Level> getCurrentDimension() { return currentDimension; }

    /**
     * 获取已完成的维度列表
     *
     * @return 已完成维度的友好名称列表
     */
    public static List<String> getCompletedDimensions() { return completedDimensions; }

    /**
     * 维度缓存统计信息
     *
     * @param dimension 维度名称（友好格式）
     * @param regionCount 区域数量
     * @param sizeBytes 占用空间（字节）
     */
    public record DimensionCacheStats(String dimension, int regionCount, long sizeBytes) {
        /**
         * 获取占用空间（MB）
         *
         * @return 占用空间（MB）
         */
        public double sizeMB() {
            return sizeBytes / (1024.0 * 1024.0);
        }
    }

    /**
     * 获取缓存统计信息
     *
     * 遍历缓存目录，统计各维度的区域数量和文件大小。
     *
     * @return 维度缓存统计信息列表
     */
    public static List<DimensionCacheStats> getCacheStats() {
        List<DimensionCacheStats> stats = new ArrayList<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();

        if (!Files.exists(getCacheDir())) {
            return stats;
        }

        try (DirectoryStream<Path> dimDirs = Files.newDirectoryStream(getCacheDir())) {
            for (Path dimDir : dimDirs) {
                if (!dimDir.toFile().isDirectory()) continue;

                String dimName = dimDir.getFileName().toString();
                String friendlyName = dimMapping.getFriendlyName(dimName);

                int regionCount = 0;
                long totalSize = 0;

                // 遍历维度目录下的所有 zip 文件（包括 caves 子目录）
                try (Stream<Path> files = Files.walk(dimDir)) {
                    List<Path> zipFiles = files
                            .filter(p -> p.toString().endsWith(".zip"))
                            .toList();

                    regionCount = zipFiles.size();
                    totalSize = zipFiles.stream()
                            .mapToLong(p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .sum();
                }

                if (regionCount > 0) {
                    stats.add(new DimensionCacheStats(friendlyName, regionCount, totalSize));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get cache stats", e);
        }

        return stats;
    }
}
