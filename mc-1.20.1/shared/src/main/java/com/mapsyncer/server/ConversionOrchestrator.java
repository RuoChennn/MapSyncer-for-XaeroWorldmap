package com.mapsyncer.server;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.ScanMode;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.config.TimeoutConfig;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.RegionConverterStandalone.CaveModeParams;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import com.mapsyncer.server.RegionScanner.DimensionRegions;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.DimensionTypeHelper;
import com.mapsyncer.util.NamedThreadFactory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private static volatile boolean isRunning = false;

    /** 已处理的区域数量（原子变量，支持并发更新） */
    private static final AtomicInteger processedCountAtomic = new AtomicInteger(0);

    /** 已处理的区域数量（兼容旧代码） */
    private static volatile int processedCount = 0;

    /** 跳过的区域数量（时间戳未变化） */
    private static volatile int skippedCount = 0;

    /** 总区域数量 */
    private static volatile int totalCount = 0;

    /** 当前状态描述 */
    private static volatile String currentStatus = "idle";

    /** 当前正在处理的维度 */
    private static volatile ResourceKey<Level> currentDimension = null;

    /** 已完成的维度列表（用于全量生成完成提示） */
    private static volatile List<String> completedDimensions = new ArrayList<>();

    /** 缓存输出目录 */
    public static final Path CACHE_DIR = Path.of("server_map_cache");

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
     * 线程池大小由配置 maxConcurrentRegions 决定。
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
            LOGGER.info("Created conversion thread pool with {} threads", maxConcurrent);
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
        int removed = GenerationCache.getInstance(CACHE_DIR).removeByPrefix(xaeroDimName + "/");
        if (removed > 0) {
            LOGGER.info("Cleared {} generation_cache entries for dimension: {}", removed, xaeroDimName);
        } else {
            LOGGER.info("No generation_cache entries found for dimension: {}", xaeroDimName);
        }
    }

    /**
     * 获取或初始化时间戳缓存
     *
     * @return MCA时间戳缓存实例
     */
    private static McaTimestampCache getTimestampCache() {
        if (timestampCache == null) {
            timestampCache = McaTimestampCache.getInstance(CACHE_DIR);
        }
        return timestampCache;
    }

    /**
     * 执行全量转换 - 转换服务器所有维度的所有区域
     *
     * @param server Minecraft服务器实例
     */
    public static void generateAll(MinecraftServer server) {
        if (isRunning) {
            LOGGER.warn("Conversion already in progress");
            return;
        }
        isRunning = true;
        processedCount = 0;
        skippedCount = 0;
        completedDimensions = new ArrayList<>();  // 重置已完成维度列表

        // Step 1: Force save all chunks to disk before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            isRunning = false;
            return;
        }

        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        totalCount = allRegions.stream().mapToInt(d -> d.regions().size()).sum();
        int totalSkippedEmpty = allRegions.stream().mapToInt(DimensionRegions::skippedEmptyCount).sum();
        if (totalCount == 0) {
            LOGGER.info("No regions found to convert");
            isRunning = false;
            return;
        }
        LOGGER.info("Starting conversion of {} regions across {} dimensions", totalCount, allRegions.size());
        try {
            for (DimensionRegions dimRegions : allRegions) {
                convertDimension(server, dimRegions, false);
            }
        } finally {
            isRunning = false;
            currentStatus = "completed";
            LOGGER.info("Conversion completed: {}/{} regions, {} skipped (empty MCA)", processedCount, totalCount, totalSkippedEmpty);
        }
    }

    /**
     * 执行单维度转换 - 转换指定维度的所有区域
     *
     * 使用时间戳缓存检测需要更新的区域，跳过未变化的区域。
     *
     * @param server Minecraft服务器实例
     * @param dimensionId 维度ID（如"minecraft:overworld"）
     */
    public static void generateDimension(MinecraftServer server, String dimensionId) {
        if (isRunning) {
            LOGGER.warn("Conversion already in progress");
            return;
        }
        isRunning = true;
        processedCount = 0;
        skippedCount = 0;
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); isRunning = false; return; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); isRunning = false; return; }

        // Force save all chunks before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            isRunning = false;
            return;
        }

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount()), false);
        } finally {
            isRunning = false;
            currentStatus = "completed";
        }
    }

    /**
     * 执行单维度强制转换 - 强制重新生成指定维度的所有区域
     *
     * 清除维度缓存目录后重新生成所有区域，忽略时间戳缓存。
     *
     * @param server Minecraft服务器实例
     * @param dimensionId 维度ID（如"minecraft:overworld"）
     */
    public static void generateDimensionForce(MinecraftServer server, String dimensionId) {
        if (isRunning) {
            LOGGER.warn("Conversion already in progress");
            return;
        }
        isRunning = true;
        processedCount = 0;
        skippedCount = 0;
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); isRunning = false; return; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); isRunning = false; return; }

        // 强制生成前先清除该维度的缓存目录和 generation_cache 记录
        String fullDimId = dimKey.location().toString(); // 完整维度 ID（包含 namespace）
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path dimCacheDir = CACHE_DIR.resolve(xaeroDimName);
        clearDimensionCache(dimCacheDir);
        clearGenerationCacheEntries(xaeroDimName);

        // Force save all chunks before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            isRunning = false;
            return;
        }

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount()), true);
        } finally {
            isRunning = false;
            currentStatus = "completed";
        }
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
        if (isRunning) {
            LOGGER.warn("Conversion already in progress");
            return SingleRegionResult.ALREADY_RUNNING;
        }

        // 提前检查 MCA 文件是否存在
        Path mcaPath = checkMcaFileExists(server, dimension, regionX, regionZ);
        if (mcaPath == null) {
            LOGGER.warn("MCA file not found for region ({}, {}) in dimension {}", regionX, regionZ, dimension.location().getPath());
            return SingleRegionResult.REGION_NOT_FOUND;
        }

        isRunning = true;
        totalCount = 1;
        processedCount = 0;
        currentDimension = dimension;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimension); isRunning = false; return SingleRegionResult.CONVERSION_FAILED; }

        // Force save all chunks before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            isRunning = false;
            return SingleRegionResult.CONVERSION_FAILED;
        }

        // 使用完整维度 ID 作为缓存 key（确保新格式路径正确转换）
        String fullDimId = dimension.location().toString();
        String dimPath = dimension.location().getPath(); // 用于配置查找

        // 从配置获取维度扫描配置
        DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);
        ScanMode scanMode = scanConfig.scanMode();
        int caveLayer = scanConfig.getCaveLayer();

        // 使用 Xaero 格式的维度目录名（使用完整维度 ID，确保新格式路径正确转换）
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

        // 获取 MCA 文件存放目录（1.21+ 自动检测路径）
        Path regionDir = RegionScanner.getRegionDir(level);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", dimension);
            isRunning = false;
            return SingleRegionResult.CONVERSION_FAILED;
        }

        // 计算输出目录（包含 caves/<layer> 子目录）
        Path baseOutputDir = CACHE_DIR.resolve(xaeroDimName);
        Path outputDir;
        if (caveLayer == Integer.MAX_VALUE) {
            outputDir = baseOutputDir;
        } else {
            outputDir = baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
        }

        // 从运行时获取准确的维度类型信息
        DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.height());

        // 根据配置选择光照模式和洞穴参数
        LightMode lightMode;
        CaveModeParams caveParams;
        if (scanMode == ScanMode.CAVE) {
            lightMode = LightMode.CAVE;
            int caveDepth = scanConfig.getCaveDepth(dimTypeInfo.minY());
            caveParams = new CaveModeParams(scanConfig.caveStart(), caveDepth);
            LOGGER.info("Single region generation: using CAVE mode with caveStart={}, caveLayer={}",
                scanConfig.caveStart(), caveLayer);
        } else {
            lightMode = LightMode.SURFACE;
            caveParams = CaveModeParams.NONE;
            LOGGER.info("Single region generation: using SURFACE mode");
        }

        SingleRegionResult result = SingleRegionResult.SUCCESS;
        try {
            Files.createDirectories(outputDir);
            ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                mcaPath, regionX, regionZ, dimTypeInfo, lightMode, caveParams, BlockPropertyResolver.INSTANCE);
            if (converted != null) {
                XaeroWriter.writeRegionFile(outputDir, converted);
                processedCount = 1;
                LOGGER.info("Converted single region: ({}, {})", regionX, regionZ);
            } else {
                LOGGER.warn("Could not convert region ({}, {}): conversion failed", regionX, regionZ);
                result = SingleRegionResult.CONVERSION_FAILED;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write region file", e);
            result = SingleRegionResult.CONVERSION_FAILED;
        }
        finally {
            isRunning = false;
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
        String fullDimId = dimRegions.dimension().location().toString();
        String dimPath = dimRegions.dimension().location().getPath();

        DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);
        ScanMode scanMode = scanConfig.scanMode();
        int caveLayer = scanConfig.getCaveLayer();

        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path regionDir = RegionScanner.getRegionDir(level);
        Path outputDir = getOutputDir(CACHE_DIR.resolve(xaeroDimName), caveLayer);

        try { Files.createDirectories(outputDir); } catch (IOException e) {
            LOGGER.error("Failed to create output directory: {}", outputDir, e);
            return;
        }

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", xaeroDimName);
            return;
        }

        DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.height());

        LightMode lightMode = scanMode == ScanMode.CAVE ? LightMode.CAVE : LightMode.SURFACE;
        CaveModeParams caveParams = scanMode == ScanMode.CAVE
            ? new CaveModeParams(scanConfig.caveStart(), scanConfig.getCaveDepth(dimTypeInfo.minY()))
            : CaveModeParams.NONE;

        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(CACHE_DIR);
        List<RegionCoords> needsUpdate = force ? dimRegions.regions() : mcaCache.scanAndUpdate(dimPath, regionDir);
        List<RegionCoords> regions = dimRegions.regions();

        LOGGER.info("Dimension {}: {} total regions, {} need update (force={})", dimPath, regions.size(), needsUpdate.size(), force);

        ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();
        processedCountAtomic.set(0);
        skippedCount = 0;
        long generationTimeSeconds = System.currentTimeMillis() / 1000;

        ExecutorService executor = getOrCreateExecutor();

        // 第一轮：并发转换时间戳变化的区域
        List<java.util.concurrent.Future<?>> futures = submitConversionTasks(
            executor, needsUpdate, regions, regionDir, outputDir, xaeroDimName, dimPath,
            dimTypeInfo, lightMode, caveParams, caveLayer, mcaCache, genCache,
            generationTimeSeconds, failedRegions, true);
        waitForCompletion(futures, "Region conversion");

        // 第二轮：处理新增区域（非 force 模式）
        if (!force) {
            futures = submitNewRegionTasks(
                executor, regions, needsUpdate, regionDir, outputDir, xaeroDimName, dimPath,
                dimTypeInfo, lightMode, caveParams, caveLayer, mcaCache, genCache,
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

        LOGGER.info("Dimension {} completed: {} total, {} converted, {} skipped (unchanged), {} skipped (empty MCA), {} failed",
            dimPath, regions.size(), processedCount - skippedCount, skippedCount, dimRegions.skippedEmptyCount(), failedRegions.size());

        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimRegions.dimension().location().toString());
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
        if (caveLayer == Integer.MAX_VALUE) {
            return baseOutputDir;
        } else {
            return baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
        }
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
            Path regionDir, Path outputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, LightMode lightMode, CaveModeParams caveParams, int caveLayer,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress) {

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (RegionCoords coords : coordsToProcess) {
            if (!allRegions.contains(coords)) continue;

            java.util.concurrent.Future<?> future = executor.submit(() ->
                convertSingleRegion(coords, regionDir, outputDir, xaeroDimName, dimPath,
                    dimTypeInfo, lightMode, caveParams, caveLayer, mcaCache, genCache,
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
            ExecutorService executor, List<RegionCoords> allRegions, List<RegionCoords> processedRegions,
            Path regionDir, Path outputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, LightMode lightMode, CaveModeParams caveParams, int caveLayer,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions) {

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (RegionCoords coords : allRegions) {
            if (processedRegions.contains(coords)) continue;

            if (XaeroWriter.regionFileExists(outputDir, coords.x(), coords.z())) {
                processedCountAtomic.incrementAndGet();
                skippedCount++;
                LOGGER.debug("Skipped region ({}, {}): unchanged (timestamp match)", coords.x(), coords.z());
                continue;
            }

            java.util.concurrent.Future<?> future = executor.submit(() ->
                convertSingleRegion(coords, regionDir, outputDir, xaeroDimName, dimPath,
                    dimTypeInfo, lightMode, caveParams, caveLayer, mcaCache, genCache,
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
    private static void convertSingleRegion(
            RegionCoords coords, Path regionDir, Path outputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, LightMode lightMode, CaveModeParams caveParams, int caveLayer,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress, String logPrefix) {

        Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");

        ConvertedRegion converted = RegionConverterStandalone.convertRegion(
            mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams, BlockPropertyResolver.INSTANCE);

        if (converted == null) {
            failedRegions.add(coords);
            return;
        }

        try {
            Path outputFile = XaeroWriter.writeRegionFile(outputDir, converted);
            mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);

            String relativePath = caveLayer == Integer.MAX_VALUE
                ? xaeroDimName + "/" + coords.x() + "_" + coords.z()
                : xaeroDimName + "/caves/" + caveLayer + "/" + coords.x() + "_" + coords.z();

            genCache.updateWithHash(relativePath, outputFile, generationTimeSeconds);

        } catch (IOException e) {
            LOGGER.error("Failed to write region file", e);
            failedRegions.add(coords);
            return;
        }

        if (logProgress) {
            int currentProcessed = processedCountAtomic.incrementAndGet();
            LOGGER.info("{} region ({}, {}): {}/{}", logPrefix, coords.x(), coords.z(), currentProcessed, totalCount);
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
                LOGGER.error("{} task failed: {}", taskName, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                LOGGER.error("{} task interrupted", taskName, e);
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 强制保存所有维度的所有区块到磁盘
     *
     * 确保MCA文件是最新的，必须在读取MCA文件之前调用。
     * 由于C2ME并发限制，必须在服务器线程上执行（不能异步）。
     *
     * @param server Minecraft服务器实例
     * @return true表示保存成功，false表示保存失败
     */
    private static boolean saveAllChunks(MinecraftServer server) {
        try {
            LOGGER.info("Flushing all chunks to disk...");
            currentStatus = "Saving all chunks to disk...";

            // Must execute on server thread to avoid C2ME ConcurrentModificationException
            // C2ME prevents async calls to saveEverything()
            final boolean[] success = new boolean[1];
            final Throwable[] error = new Throwable[1];

            server.execute(() -> {
                try {
                    server.saveEverything(false, true, true);
                    success[0] = true;
                } catch (Throwable t) {
                    error[0] = t;
                }
            });

            // Wait for save to complete (with timeout)
            long startTime = System.currentTimeMillis();
            long timeoutMs = TimeoutConfig.SAVE_TIMEOUT_MS;
            while (!success[0] && error[0] == null) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    LOGGER.error("Timeout waiting for chunk save to complete");
                    return false;
                }
                Thread.sleep(100);
            }

            if (error[0] != null) {
                LOGGER.error("Error during chunk flush", error[0]);
                return false;
            }

            LOGGER.info("All chunks flushed to disk successfully");
            return true;
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for chunk save", e);
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            LOGGER.error("Runtime error during chunk flush: {}", e.getMessage());
            return false;
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

        // 尝试解析为 ResourceLocation 并查找维度
        try {
            ResourceLocation location = new ResourceLocation(id);
            // 遍历所有已加载的维度查找匹配
            for (ServerLevel level : server.getAllLevels()) {
                ResourceLocation dimLocation = level.dimension().location();
                if (dimLocation.equals(location) ||
                    dimLocation.getPath().equals(id) ||
                    dimLocation.toString().equals(id)) {
                    return level.dimension();
                }
            }
            LOGGER.warn("Dimension not found: {}", id);
        } catch (RuntimeException e) {
            LOGGER.error("Invalid dimension id format '{}': {}", id, e.getMessage());
        }

        return null;
    }

    /**
     * 执行计划增量扫描 - 扫描所有维度并更新时间戳变化的区域
     *
     * 由IncrementalUpdateHandler从服务器线程周期性调用。
     * 扫描所有维度，仅更新时间戳有变化的区域。
     *
     * @param server Minecraft服务器实例
     */
    public static void performIncrementalScan(MinecraftServer server) {
        if (isRunning) {
            LOGGER.debug("Conversion already in progress, skipping incremental scan");
            return;
        }

        // Save chunks before scanning to ensure MCA files are up-to-date
        // This is called from server thread via ServerTickEvent, so direct call is safe
        try {
            server.saveEverything(false, true, true);
        } catch (RuntimeException e) {
            LOGGER.error("Runtime error saving chunks for incremental scan: {}", e.getMessage());
            return;
        }

        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(CACHE_DIR);
        int totalUpdated = 0;
        long generationTimeSeconds = System.currentTimeMillis() / 1000;

        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) continue;

            // 获取完整维度 ID（包含 namespace，用于 Xaero 目录映射）
            String fullDimId = dimRegions.dimension().location().toString();
            String dimPath = dimRegions.dimension().location().getPath(); // 用于配置查找

            // 从配置获取维度扫描配置
            DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);
            ScanMode scanMode = scanConfig.scanMode();
            int caveLayer = scanConfig.getCaveLayer();

            // 获取 Xaero 格式的目录名（使用完整维度 ID，确保新格式路径正确转换）
            String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

            // 获取 MCA 文件存放目录（1.21+ 自动检测路径）
            Path regionDir = RegionScanner.getRegionDir(level);
            if (regionDir == null) continue;

            // 计算输出目录（包含 caves/<layer> 子目录）
            Path baseOutputDir = CACHE_DIR.resolve(xaeroDimName);
            Path outputDir;
            if (caveLayer == Integer.MAX_VALUE) {
                outputDir = baseOutputDir;
            } else {
                outputDir = baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
            }

            // 从运行时获取准确的维度类型信息
            DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());

            // 获取光照模式和洞穴参数
            LightMode lightMode;
            CaveModeParams caveParams;
            if (scanMode == ScanMode.CAVE) {
                lightMode = LightMode.CAVE;
                int caveDepth = scanConfig.getCaveDepth(dimTypeInfo.minY());
                caveParams = new CaveModeParams(scanConfig.caveStart(), caveDepth);
            } else {
                lightMode = LightMode.SURFACE;
                caveParams = CaveModeParams.NONE;
            }

            // Scan for regions that need update
            java.util.List<RegionCoords> needsUpdate = mcaCache.scanAndUpdate(dimPath, regionDir);

            if (needsUpdate.isEmpty()) {
                LOGGER.debug("No updates needed for dimension {}", dimPath);
                continue;
            }

            LOGGER.info("Dimension {}: {} regions need incremental update (mode={}, hasSkylight={})",
                dimPath, needsUpdate.size(), scanMode, dimTypeInfo.hasSkylight());

            try {
                Files.createDirectories(outputDir);
            } catch (IOException e) {
                LOGGER.error("Failed to create output directory: {}", outputDir, e);
                continue;
            }

            for (RegionCoords coords : needsUpdate) {
                Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");
                if (!Files.exists(mcaPath)) continue;

                ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                    mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams, BlockPropertyResolver.INSTANCE);

                if (converted != null) {
                    try {
                        Path outputFile = XaeroWriter.writeRegionFile(outputDir, converted);
                        mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);

                        // Update GenerationCache with correct relativePath format
                        String relativePath;
                        if (caveLayer == Integer.MAX_VALUE) {
                            relativePath = xaeroDimName + "/" + coords.x() + "_" + coords.z();
                        } else {
                            relativePath = xaeroDimName + "/caves/" + caveLayer + "/" + coords.x() + "_" + coords.z();
                        }
                        genCache.updateWithHash(relativePath, outputFile, generationTimeSeconds);

                        totalUpdated++;
                        LOGGER.debug("Incrementally updated region ({}, {}) in {} (layer={})", coords.x(), coords.z(), dimPath, caveLayer == Integer.MAX_VALUE ? "surface" : caveLayer);
                    } catch (IOException e) {
                        LOGGER.error("Failed to write region file during incremental update", e);
                    }
                }
            }
        }

        if (totalUpdated > 0) {
            LOGGER.info("Incremental scan completed: {} regions updated", totalUpdated);
            mcaCache.saveCache();
            genCache.save();
        }
    }

    /**
     * 检查转换任务是否正在运行
     *
     * @return true表示正在运行，false表示空闲
     */
    public static boolean isRunning() { return isRunning; }

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
    public static int getUpdatedCount() { return processedCount - skippedCount; }

    /**
     * 获取跳过的区域数量（时间戳未变化）
     *
     * @return 跳过数量
     */
    public static int getSkippedCount() { return skippedCount; }

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

        if (!Files.exists(CACHE_DIR)) {
            return stats;
        }

        try (DirectoryStream<Path> dimDirs = Files.newDirectoryStream(CACHE_DIR)) {
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
