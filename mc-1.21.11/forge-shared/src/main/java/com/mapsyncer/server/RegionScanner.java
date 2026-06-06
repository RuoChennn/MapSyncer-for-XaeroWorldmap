package com.mapsyncer.server;

import com.mapsyncer.util.DimensionPathMapping;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Region文件扫描器 - 扫描Minecraft世界中的区域文件
 *
 * 支持 Minecraft 26.1+ 新格式和传统格式：
 * - 新格式：dimensions/<namespace>/<dimension_name>/region/
 * - 传统格式：region/, DIM-1/region/, DIM1/region/, DIM{id}/region/
 *
 * 自动检测实际使用的格式，优先检查新格式。
 * 跳过空的MCA文件（0字节），避免处理无数据的区域。
 */
public class RegionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScanner.class);

    /** MCA/MCR文件名匹配正则表达式 */
    private static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$");

    /**
     * 区域坐标记录
     *
     * @param x 区域X坐标
     * @param z 区域Z坐标
     */
    public record RegionCoords(int x, int z) {
    }

    /**
     * 区域扫描结果
     *
     * @param regions 扫描到的区域坐标列表
     * @param skippedEmptyCount 跳过的空文件数量
     */
    public record RegionScanResult(List<RegionCoords> regions, int skippedEmptyCount) {
    }

    /**
     * 维度区域数据
     *
     * @param dimension 维度ResourceKey
     * @param regions 区域坐标列表
     * @param skippedEmptyCount 跳过的空文件数量
     */
    public record DimensionRegions(net.minecraft.resources.ResourceKey<Level> dimension, List<RegionCoords> regions, int skippedEmptyCount) {
    }

    /**
     * 扫描服务器所有维度的region文件
     *
     * @param server Minecraft服务器实例
     * @return 所有维度的区域数据列表
     */
    public static List<DimensionRegions> scanAllDimensions(MinecraftServer server) {
        List<DimensionNames> dimNames = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimId = level.dimension().identifier().getPath();
            if (!dimNames.stream().anyMatch(d -> d.name().equals(dimId))) {
                dimNames.add(new DimensionNames(dimId, level.dimension()));
            }
        }

        List<DimensionRegions> result = new ArrayList<>();
        for (DimensionNames dn : dimNames) {
            RegionScanResult scanResult = scanRegionDir(server.getWorldPath(LevelResource.ROOT), dn.key());
            result.add(new DimensionRegions(dn.key(), scanResult.regions(), scanResult.skippedEmptyCount()));
        }
        return result;
    }

    /**
     * 扫描指定维度的region文件
     *
     * @param level 服务端维度实例
     * @return 该维度的扫描结果
     */
    public static RegionScanResult scanDimension(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        return scanRegionDir(worldRoot, level.dimension());
    }

    /**
     * 获取指定维度的region目录路径
     *
     * 自动检测实际使用的格式（新格式优先）：
     * 1. 新格式（26.1+）：dimensions/<namespace>/<dimension_name>/region/
     * 2. 传统格式：region/（主世界）、DIM-1/region/（地狱）、DIM1/region/（末地）
     * 3. Mod预设：DIM{id}/region/
     *
     * 检测结果会被缓存到DimensionPathMapping中。
     *
     * @param level 服务端维度实例
     * @return region目录路径，如果未找到返回null
     */
    public static Path getRegionDir(ServerLevel level) {
        try {
            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
            if (!Files.exists(worldRoot)) return null;
            worldRoot = worldRoot.toRealPath();

            DimensionPathMapping mapping = DimensionPathMapping.getInstance();
            String dimId = level.dimension().identifier().toString();

            // 使用统一的检测方法（优先新格式，回退传统格式）
            Path regionDir = mapping.detectRegionDir(worldRoot, dimId);

            if (regionDir != null && Files.exists(regionDir)) {
                return regionDir.toRealPath();
            }

            LOGGER.warn("Region directory not found for dimension {} after detection", dimId);
            return null;
        } catch (IOException e) {
            LOGGER.error("Failed to get region directory", e);
            return null;
        }
    }

    /**
     * 扫描维度目录中的region文件
     *
     * 使用DimensionPathMapping检测region目录位置。
     *
     * @param worldRoot 世界根目录
     * @param dimensionKey 维度ResourceKey
     * @return 扫描结果
     */
    private static RegionScanResult scanRegionDir(Path worldRoot, net.minecraft.resources.ResourceKey<Level> dimensionKey) {
        DimensionPathMapping mapping = DimensionPathMapping.getInstance();
        String dimId = dimensionKey.identifier().toString();

        // 使用统一的检测方法
        Path regionDir = mapping.detectRegionDir(worldRoot, dimId);

        if (regionDir == null || !Files.exists(regionDir)) {
            LOGGER.warn("Region directory not found for dimension: {}", dimId);
            return new RegionScanResult(List.of(), 0);
        }

        return scanRegionDirectory(regionDir);
    }

    /**
     * 扫描region目录中的所有MCA文件
     *
     * 解析文件名提取区域坐标，跳过空文件（0字节）。
     *
     * @param regionDir region目录路径
     * @return 扫描结果（包含region坐标列表和跳过的空文件数量）
     */
    public static RegionScanResult scanRegionDirectory(Path regionDir) {
        List<RegionCoords> regions = new ArrayList<>();
        if (!Files.exists(regionDir)) {
            return new RegionScanResult(regions, 0);
        }

        int skippedEmpty = 0;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                Matcher matcher = REGION_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    // Skip empty (0KB) MCA files - they contain no chunk data
                    try {
                        long fileSize = Files.size(file);
                        if (fileSize == 0) {
                            skippedEmpty++;
                            LOGGER.debug("Skipping empty MCA file: {} (0 bytes)", fileName);
                            continue;
                        }
                    } catch (IOException e) {
                        LOGGER.warn("Failed to check file size for {}", fileName, e);
                        continue;
                    }

                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    regions.add(new RegionCoords(regionX, regionZ));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }

        if (skippedEmpty > 0) {
            LOGGER.info("Skipped {} empty (0KB) MCA files in {}", skippedEmpty, regionDir);
        }

        return new RegionScanResult(regions, skippedEmpty);
    }

    /**
     * 维度名称内部记录
     *
     * @param name 维度名称
     * @param key 维度ResourceKey
     */
    private record DimensionNames(String name, net.minecraft.resources.ResourceKey<Level> key) {}
}