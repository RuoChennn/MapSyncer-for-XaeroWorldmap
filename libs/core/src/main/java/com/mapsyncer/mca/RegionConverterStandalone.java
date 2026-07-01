package com.mapsyncer.mca;

import com.mapsyncer.mca.convert.RegionConversionPipeline;
import com.mapsyncer.mca.convert.model.ConvertConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapsyncer.mca.convert.scan.RegionScanPass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 独立的区域转换器 - 不依赖 Minecraft 库
 *
 * <p>使用自研 MCA 解析器读取 .mca 文件，转换为 Xaero WorldMap 格式。</p>
 *
 * <p>实现已迁移至 {@link com.mapsyncer.mca.convert} 包；本类保留 public API 入口。</p>
 *
 * @see com.mapsyncer.mca.convert.RegionConversionPipeline
 * @see McaReader 用于读取 MCA 文件
 * @see ChunkDataParser 用于解析 Chunk 数据
 * @see ChunkSectionParser 用于解析 Section 数据
 * @see LightMode 光照模式枚举
 * @see DimensionTypeInfo 维度类型信息
 */
public class RegionConverterStandalone {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionConverterStandalone.class);

    public static final int REGION_SIZE_BLOCKS = ConvertConstants.REGION_SIZE_BLOCKS;
    public static final int CHUNKS_PER_REGION = ConvertConstants.CHUNKS_PER_REGION;
    public static final int BLOCKS_PER_TILE_CHUNK = ConvertConstants.BLOCKS_PER_TILE_CHUNK;
    public static final int BLOCKS_PER_TILE = ConvertConstants.BLOCKS_PER_TILE;
    public static final int TILES_PER_TILE_CHUNK = ConvertConstants.TILES_PER_TILE_CHUNK;
    public static final int TILE_CHUNKS_PER_REGION = ConvertConstants.TILE_CHUNKS_PER_REGION;
    public static final int MAJOR_VERSION = ConvertConstants.MAJOR_VERSION;
    public static final int MINOR_VERSION = ConvertConstants.MINOR_VERSION;

    public record ConvertedRegion(int regionX, int regionZ, byte[] xaeroData) {}

    /** 多 pass 转换结果，含 caveLayer（地表层为 Integer.MAX_VALUE） */
    public record LayerConvertedRegion(int regionX, int regionZ, int caveLayer, byte[] xaeroData) {}

    public record CaveModeParams(int caveStart, int caveDepth) {
        public static final CaveModeParams NONE = new CaveModeParams(Integer.MAX_VALUE, 0);

        public static CaveModeParams createDefault(int worldTopY, int defaultDepth) {
            return new CaveModeParams(worldTopY, defaultDepth);
        }
    }

    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY,
                                                  BlockPropertyLookup blockLookup) {
        return convertRegion(mcaPath, regionX, regionZ, minBuildHeight, worldTopY,
                             LightMode.SURFACE, CaveModeParams.NONE, true, blockLookup);
    }

    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams,
                                                  boolean worldHasSkylight,
                                                  BlockPropertyLookup blockLookup) {
        if (!Files.exists(mcaPath)) {
            return null;
        }

        try {
            return RegionConversionPipeline.convert(
                mcaPath, regionX, regionZ, minBuildHeight, worldTopY,
                lightMode, caveParams, worldHasSkylight, blockLookup);
        } catch (IOException e) {
            LOGGER.warn("Failed to convert region ({}, {})", regionX, regionZ, e);
            return null;
        }
    }

    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  DimensionTypeInfo dimTypeInfo,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams,
                                                  BlockPropertyLookup blockLookup) {
        if (!Files.exists(mcaPath)) {
            return null;
        }

        try {
            return RegionConversionPipeline.convert(
                mcaPath, regionX, regionZ, dimTypeInfo, lightMode, caveParams, blockLookup);
        } catch (IOException e) {
            LOGGER.warn("Failed to convert region ({}, {})", regionX, regionZ, e);
            return null;
        }
    }

    public static List<LayerConvertedRegion> convertRegionMulti(
            Path mcaPath, int regionX, int regionZ,
            DimensionTypeInfo dimTypeInfo,
            List<RegionScanPass> passes,
            BlockPropertyLookup blockLookup) {
        if (!Files.exists(mcaPath)) {
            return List.of();
        }
        try {
            return RegionConversionPipeline.convertMulti(
                mcaPath, regionX, regionZ, dimTypeInfo, passes, blockLookup);
        } catch (IOException e) {
            LOGGER.warn("Failed to convert region ({}, {}) multi-pass", regionX, regionZ, e);
            return List.of();
        }
    }
}
