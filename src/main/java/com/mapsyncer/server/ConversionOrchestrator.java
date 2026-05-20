package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.DimensionScanConfig;
import com.mapsyncer.config.ModConfig.ScanMode;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.RegionConverterStandalone.CaveModeParams;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import com.mapsyncer.server.RegionScanner.DimensionRegions;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the region conversion pipeline: scan → convert → write.
 * Supports full, per-dimension, and single-region conversion modes.
 */
public class ConversionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversionOrchestrator.class);

    private static volatile boolean isRunning = false;
    private static volatile int processedCount = 0;
    private static volatile int totalCount = 0;
    private static volatile String currentStatus = "idle";
    private static volatile ResourceKey<Level> currentDimension = null;

    public static final Path CACHE_DIR = Path.of("server_map_cache");

    // 时间戳缓存实例
    private static McaTimestampCache timestampCache;

    // 单区域生成结果状态
    public enum SingleRegionResult {
        SUCCESS,
        REGION_NOT_FOUND,
        CONVERSION_FAILED,
        ALREADY_RUNNING
    }

    /**
     * 清除维度缓存目录
     * @param dimCacheDir 维度缓存目录路径
     */
    private static void clearDimensionCache(Path dimCacheDir) {
        if (!Files.exists(dimCacheDir)) {
            LOGGER.info("No existing cache to clear for dimension: {}", dimCacheDir);
            return;
        }

        try {
            // 递归删除目录中的所有文件和子目录
            Files.walk(dimCacheDir)
                    .sorted((a, b) -> -a.compareTo(b)) // 先删除文件再删除目录
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOGGER.debug("Deleted: {}", path);
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete: {}", path);
                        }
                    });
            LOGGER.info("Cleared cache directory: {}", dimCacheDir);
        } catch (IOException e) {
            LOGGER.error("Failed to clear dimension cache: {}", dimCacheDir, e);
        }
    }

    /**
     * 获取或初始化时间戳缓存
     */
    private static McaTimestampCache getTimestampCache() {
        if (timestampCache == null) {
            timestampCache = McaTimestampCache.getInstance(CACHE_DIR);
        }
        return timestampCache;
    }

    public static void generateAll(MinecraftServer server) {
        if (isRunning) {
            LOGGER.warn("Conversion already in progress");
            return;
        }
        isRunning = true;
        processedCount = 0;

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

    public static void generateDimension(MinecraftServer server, String dimensionId) {
        if (isRunning) {
            LOGGER.warn("Conversion already in progress");
            return;
        }
        isRunning = true;
        processedCount = 0;
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

    public static void generateDimensionForce(MinecraftServer server, String dimensionId) {
        if (isRunning) {
            LOGGER.warn("Conversion already in progress");
            return;
        }
        isRunning = true;
        processedCount = 0;
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); isRunning = false; return; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); isRunning = false; return; }

        // 强制生成前先清除该维度的缓存目录
        String fullDimId = dimKey.location().toString(); // 完整维度 ID（包含 namespace）
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path dimCacheDir = CACHE_DIR.resolve(xaeroDimName);
        clearDimensionCache(dimCacheDir);

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
     * 检查单个区域的 MCA 文件是否存在
     * @param server MinecraftServer 实例
     * @param dimension 维度 ResourceKey
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @return MCA 文件路径（如果存在），null 表示不存在
     */
    public static Path checkMcaFileExists(MinecraftServer server, ResourceKey<Level> dimension, int regionX, int regionZ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return null;

        String dimPath = dimension.location().getPath();
        DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);

        Path regionDir;
        String configRegionFolder = scanConfig.regionFolder();
        if (configRegionFolder != null && !configRegionFolder.isEmpty()) {
            regionDir = server.getWorldPath(LevelResource.ROOT).resolve(configRegionFolder).resolve("region");
        } else {
            regionDir = RegionScanner.getRegionDir(level);
        }

        if (regionDir == null) return null;

        Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        return Files.exists(mcaPath) ? mcaPath : null;
    }

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
        DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
        ScanMode scanMode = scanConfig.scanMode();
        int caveLayer = scanConfig.getCaveLayer();

        // 使用 Xaero 格式的维度目录名（使用完整维度 ID，确保新格式路径正确转换）
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

        // 获取 MCA 文件存放目录（用于读取）
        Path regionDir;
        String configRegionFolder = scanConfig.regionFolder();
        if (configRegionFolder != null && !configRegionFolder.isEmpty()) {
            regionDir = server.getWorldPath(LevelResource.ROOT).resolve(configRegionFolder).resolve("region");
        } else {
            regionDir = RegionScanner.getRegionDir(level);
        }

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
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());
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
                mcaPath, regionX, regionZ, dimTypeInfo, lightMode, caveParams);
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

    private static void convertDimension(MinecraftServer server, DimensionRegions dimRegions, boolean force) {
        ServerLevel level = server.getLevel(dimRegions.dimension());
        if (level == null) { LOGGER.error("Level not loaded"); return; }

        currentDimension = dimRegions.dimension();
        // 获取完整的维度 ID（包含 namespace，如 "twilightforest:twilight_forest"）
        // 用于 Xaero 目录映射，确保新格式路径能正确转换为 namespace$path 格式
        String fullDimId = dimRegions.dimension().location().toString();
        // 获取维度 path 部分（不含 namespace，如 "twilight_forest"）
        // 用于配置查找，因为配置可能只使用 path 部分
        String dimPath = dimRegions.dimension().location().getPath();

        // 从配置获取维度扫描配置（使用 path 部分，因为配置可能不含 namespace）
        DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
        ScanMode scanMode = scanConfig.scanMode();
        int caveLayer = scanConfig.getCaveLayer();

        // 获取 Xaero 格式的目录名（使用完整维度 ID，确保新格式路径正确转换）
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

        // 获取 MCA 文件存放目录（用于读取）
        // 优先使用配置中的 regionFolder，否则使用默认路径
        Path regionDir;
        String configRegionFolder = scanConfig.regionFolder();
        if (configRegionFolder != null && !configRegionFolder.isEmpty()) {
            // 使用配置指定的 region 目录
            regionDir = server.getWorldPath(LevelResource.ROOT).resolve(configRegionFolder).resolve("region");
            LOGGER.info("Using configured region_folder: {} -> {}", configRegionFolder, regionDir);
        } else {
            // 使用 Minecraft 默认路径
            regionDir = RegionScanner.getRegionDir(level);
            LOGGER.info("Using default region path for dimension {}", dimPath);
        }

        // 计算输出目录（包含 caves/<layer> 子目录）
        Path baseOutputDir = CACHE_DIR.resolve(xaeroDimName);
        Path outputDir;
        if (caveLayer == Integer.MAX_VALUE) {
            // 地表模式：直接在维度目录
            outputDir = baseOutputDir;
        } else {
            // 洞穴模式：存放到 caves/<layer> 子目录
            outputDir = baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
        }

        try { Files.createDirectories(outputDir); } catch (IOException e) {
            LOGGER.error("Failed to create output directory: {}", outputDir, e);
            return;
        }

        // 检查 region 目录是否存在
        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", xaeroDimName);
            return;
        }

        // 从运行时获取准确的维度类型信息
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());
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
            LOGGER.info("Dimension {}: using CAVE mode with caveStart={}, caveLayer={}, caveDepth={}",
                xaeroDimName, scanConfig.caveStart(), caveLayer, caveDepth);
        } else {
            lightMode = LightMode.SURFACE;
            caveParams = CaveModeParams.NONE;
            LOGGER.info("Dimension {}: using SURFACE mode", xaeroDimName);
        }

        // 使用时间戳缓存检测需要更新的区域
        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(CACHE_DIR);
        List<RegionCoords> needsUpdate = force ? dimRegions.regions() : mcaCache.scanAndUpdate(dimPath, regionDir);

        List<RegionCoords> regions = dimRegions.regions();
        LOGGER.info("Dimension {}: {} total regions, {} need update (force={})", dimPath, regions.size(), needsUpdate.size(), force);

        List<RegionCoords> failedRegions = new ArrayList<>();
        int skippedCount = 0;
        long generationTimeSeconds = System.currentTimeMillis() / 1000;  // Unified generation timestamp (seconds)

        // 使用独立 MCA 解析器转换需要更新的区域（更快，不加载 chunks）
        for (RegionCoords coords : needsUpdate) {
            // 检查是否在区域列表中
            if (!regions.contains(coords)) {
                continue;
            }

            currentStatus = "Converting region (" + coords.x() + ", " + coords.z() + ")";
            Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");

            // 使用独立解析器直接读取 MCA 文件（使用维度类型信息）
            ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams);

            if (converted != null) {
                try {
                    Path outputFile = XaeroWriter.writeRegionFile(outputDir, converted);
                    mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);
                    // Update generation cache with timestamp and hash
                    // relativePath 格式：xaeroDim/regionX_regionZ（地表）或 xaeroDim/caves/layer/regionX_regionZ（洞穴）
                    String relativePath;
                    if (caveLayer == Integer.MAX_VALUE) {
                        relativePath = xaeroDimName + "/" + coords.x() + "_" + coords.z();
                    } else {
                        relativePath = xaeroDimName + "/caves/" + caveLayer + "/" + coords.x() + "_" + coords.z();
                    }
                    genCache.updateWithHash(relativePath, outputFile, generationTimeSeconds);
                } catch (IOException e) {
                    LOGGER.error("Failed to write region file", e);
                    failedRegions.add(coords);
                    continue;
                }
                processedCount++;
                LOGGER.info("Converted region ({}, {}): {}/{}", coords.x(), coords.z(), processedCount, needsUpdate.size());
            } else {
                failedRegions.add(coords);
            }
        }

        // 非 force 模式下，也处理尚未生成过的区域（新增区域）
        if (!force) {
            for (RegionCoords coords : regions) {
                if (needsUpdate.contains(coords)) continue;  // 已处理

                // 检查输出文件是否存在
                if (XaeroWriter.regionFileExists(outputDir, coords.x(), coords.z())) {
                    // 文件存在且时间戳未更新，跳过
                    processedCount++;
                    skippedCount++;
                    LOGGER.debug("Skipped region ({}, {}): unchanged (timestamp match)", coords.x(), coords.z());
                    continue;
                }

                // 新区域，需要生成
                currentStatus = "Generating new region (" + coords.x() + ", " + coords.z() + ")";
                Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");

                // 使用维度类型信息进行转换
                ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                    mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams);

                if (converted != null) {
                    try {
                        Path outputFile = XaeroWriter.writeRegionFile(outputDir, converted);
                        mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);
                        // Update generation cache with timestamp and hash
                        // relativePath 格式：xaeroDim/regionX_regionZ（地表）或 xaeroDim/caves/layer/regionX_regionZ（洞穴）
                        String relativePath;
                        if (caveLayer == Integer.MAX_VALUE) {
                            relativePath = xaeroDimName + "/" + coords.x() + "_" + coords.z();
                        } else {
                            relativePath = xaeroDimName + "/caves/" + caveLayer + "/" + coords.x() + "_" + coords.z();
                        }
                        genCache.updateWithHash(relativePath, outputFile, generationTimeSeconds);
                    } catch (IOException e) {
                        LOGGER.error("Failed to write region file", e);
                        failedRegions.add(coords);
                        continue;
                    }
                    processedCount++;
                    LOGGER.info("Generated new region ({}, {}): {}/{}", coords.x(), coords.z(), processedCount, totalCount);
                } else {
                    failedRegions.add(coords);
                }
            }
        }

        // 重试失败的区域
        if (!failedRegions.isEmpty()) {
            LOGGER.warn("Failed to convert {} regions", failedRegions.size());
            for (RegionCoords coords : failedRegions) {
                LOGGER.warn("Failed region: ({}, {})", coords.x(), coords.z());
            }
        }

        // 输出汇总信息
        LOGGER.info("Dimension {} completed: {} total, {} converted, {} skipped (unchanged), {} skipped (empty MCA), {} failed",
            dimPath, regions.size(), processedCount - skippedCount, skippedCount, dimRegions.skippedEmptyCount(), failedRegions.size());

        // 保存时间戳缓存
        mcaCache.saveCache();
        genCache.save();
    }

    /**
     * Force-save all chunks across all dimensions to ensure .mca files are up-to-date
     * before reading them for map generation.
     * Must be called from server thread (not async) due to C2ME concurrency restrictions.
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
            long timeoutMs = 60000; // 60 seconds timeout
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
        } catch (Exception e) {
            LOGGER.error("Error during chunk flush", e);
            return false;
        }
    }

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
            ResourceLocation location = ResourceLocation.parse(id);
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
        } catch (Exception e) {
            LOGGER.error("Invalid dimension id format: {}", id, e);
        }

        return null;
    }

    /**
     * Perform scheduled incremental scan for changed regions.
     * Called periodically by IncrementalUpdateHandler from server thread.
     * Scans all dimensions and updates only regions with changed timestamps.
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
        } catch (Exception e) {
            LOGGER.error("Failed to save chunks for incremental scan", e);
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
            DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
            ScanMode scanMode = scanConfig.scanMode();
            int caveLayer = scanConfig.getCaveLayer();

            // 获取 Xaero 格式的目录名（使用完整维度 ID，确保新格式路径正确转换）
            String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

            // 获取 MCA 文件存放目录（用于读取）
            Path regionDir;
            String configRegionFolder = scanConfig.regionFolder();
            if (configRegionFolder != null && !configRegionFolder.isEmpty()) {
                regionDir = server.getWorldPath(LevelResource.ROOT).resolve(configRegionFolder).resolve("region");
            } else {
                regionDir = RegionScanner.getRegionDir(level);
            }
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
            DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());

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
                    mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams);

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

    public static boolean isRunning() { return isRunning; }
    public static int getProcessedCount() { return processedCount; }
    public static int getTotalCount() { return totalCount; }
    public static String getStatus() { return currentStatus; }
    public static ResourceKey<Level> getCurrentDimension() { return currentDimension; }
}
