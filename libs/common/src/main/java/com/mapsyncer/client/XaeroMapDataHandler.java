package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Xaero 地图数据处理器（跨版本公共）。
 * 提供地图数据文件写入和区域追踪功能，不依赖任何 Minecraft API。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>写入地图数据文件到 Xaero 目录结构</li>
 *   <li>管理同步期间的区域追踪集合</li>
 *   <li>构建时间戳缓存路径</li>
 * </ul>
 *
 * <p>MC 版本相关的功能（获取服务器目录、玩家位置等）保留在各版本的 XaeroMapIntegrator 中。</p>
 */
public final class XaeroMapDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapDataHandler.class);

    private XaeroMapDataHandler() {}

    /** 同步期间更新的区域集合，用于选择性重置 */
    private static volatile Set<RegionCoord> updatedRegions = new HashSet<>();

    /** 同步前预卸载的区域集合（原本已加载的），用于同步后设置 loadState=4 */
    private static volatile Set<RegionCoord> preUnloadedRegions = new HashSet<>();

    /**
     * 区域坐标记录，用于追踪更新的区域。
     * 包含 caveLayer 信息，用于区分地表层和洞穴层。
     *
     * @param x 区域X坐标
     * @param z 区域Z坐标
     * @param caveLayer 洞穴层编号，地表层使用 Integer.MAX_VALUE
     */
    public record RegionCoord(int x, int z, int caveLayer) {
        /**
         * 兼容旧代码的构造器（默认地表层）。
         *
         * @param x 区域X坐标
         * @param z 区域Z坐标
         */
        public RegionCoord(int x, int z) {
            this(x, z, Integer.MAX_VALUE);
        }

        /**
         * 判断是否为地表层。
         *
         * @return 如果是地表层返回 true；否则返回 false
         */
        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    /**
     * 获取同步期间更新的区域集合。
     *
     * @return 更新区域集合的副本
     */
    public static Set<RegionCoord> getUpdatedRegions() {
        return new HashSet<>(updatedRegions);
    }

    /**
     * 获取同步前预卸载的区域集合（原本已加载的）。
     * 这些区域在同步后应使用 loadState=4（需要重载）而非 loadState=0（未加载）。
     *
     * @return 预卸载区域集合的副本
     */
    public static Set<RegionCoord> getPreUnloadedRegions() {
        return new HashSet<>(preUnloadedRegions);
    }

    /**
     * 获取预卸载区域集合（直接引用，供内部使用）。
     */
    static Set<RegionCoord> getPreUnloadedRegionsInternal() {
        return preUnloadedRegions;
    }

    /**
     * 清除预卸载区域集合。
     */
    public static void clearPreUnloadedRegions() {
        preUnloadedRegions.clear();
    }

    /**
     * 清除所有区域追踪集合，释放内存。
     * 在同步完成或离开服务器时调用。
     */
    public static void clearRegionTracking() {
        updatedRegions.clear();
        preUnloadedRegions.clear();
        LOGGER.debug("Cleared region tracking sets");
    }

    /**
     * 记录同步期间更新的区域。
     * 这些区域将在重新加载时被选择性重置。
     * 包含 caveLayer 信息，用于区分地表层和洞穴层。
     *
     * @param chunks 同步期间接收的区块数据列表
     */
    public static void recordUpdatedRegions(List<ChunkMapData> chunks) {
        updatedRegions.clear();

        for (ChunkMapData chunk : chunks) {
            updatedRegions.add(new RegionCoord(chunk.regionX, chunk.regionZ, chunk.caveLayer));
        }
        LOGGER.debug("Recorded {} updated regions for selective reset", updatedRegions.size());
    }

    /**
     * 记录同步期间更新的区域（使用预计算的坐标集合）。
     * 此方法更节省内存，直接接收坐标集合而非完整数据。
     *
     * @param coords 区域坐标集合
     */
    public static void recordUpdatedRegionCoords(Set<RegionCoord> coords) {
        updatedRegions.clear();
        updatedRegions.addAll(coords);
        LOGGER.debug("Recorded {} updated region coords for selective reset", updatedRegions.size());
    }

    /**
     * 写入区块数据到 Xaero 地图目录结构。
     * 支持 caves/&lt;layer&gt; 目录结构：
     * <ul>
     *   <li>地表：&lt;serverDir&gt;/&lt;xaero_dimension&gt;/mw$&lt;worldId&gt;/&lt;regionX_regionZ&gt;.zip</li>
     *   <li>洞穴：&lt;serverDir&gt;/&lt;xaero_dimension&gt;/mw$&lt;worldId&gt;/caves/&lt;layer&gt;/&lt;regionX_regionZ&gt;.zip</li>
     * </ul>
     *
     * @param chunk 区块数据
     * @param serverDir 服务器目录（Multiplayer_&lt;serverIP&gt;）
     * @param worldId 服务端 worldId
     * @return mw 目录路径
     */
    public static Path writeChunkData(ChunkMapData chunk, Path serverDir, int worldId) {
        String xaeroDim = chunk.dimension;
        Path dimDir = serverDir.resolve(xaeroDim);
        Path mwDir = dimDir.resolve("mw$" + worldId);

        Path targetDir;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            targetDir = mwDir;
        } else {
            targetDir = mwDir.resolve("caves").resolve(String.valueOf(chunk.caveLayer));
        }

        Path outputFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip");
        Path tempFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip.temp");

        try {
            Files.createDirectories(targetDir);

            Files.write(tempFile, chunk.data);
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Wrote map file: {} (layer={}, {} bytes)", outputFile,
                chunk.isSurfaceLayer() ? "surface" : chunk.caveLayer, chunk.data.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write map file: {}", outputFile, e);
        }

        return mwDir;
    }

    /**
     * 批量写入地图数据并更新时间戳缓存。
     *
     * @param chunks 区块数据列表
     * @param serverDir 服务器目录
     * @param worldId 服务端 worldId
     * @return 最后写入的 mw 目录路径
     */
    public static Path writeMapData(List<ChunkMapData> chunks, Path serverDir, int worldId) {
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);

        Path lastMwDir = null;
        for (ChunkMapData chunk : chunks) {
            lastMwDir = writeChunkData(chunk, serverDir, worldId);

            String relativePath = buildRelativePathForCache(chunk);
            String hash = HashUtils.computeHash(chunk.data);
            tsCache.update(relativePath, chunk.timestampSeconds, hash);
            LOGGER.debug("Updated timestamp cache for {}: ts={}s, hash={}",
                    relativePath, chunk.timestampSeconds, hash);
        }

        tsCache.save();
        LOGGER.info("Saved timestamp cache for {} regions", chunks.size());

        return lastMwDir;
    }

    /**
     * 构建时间戳缓存的相对路径（匹配服务端 GenerationCache 格式）。
     *
     * <p>格式：</p>
     * <ul>
     *   <li>地表：xaeroDim/regionX_regionZ（如 twilightforest$twilight_forest/0_0）</li>
     *   <li>洞穴：xaeroDim/caves/layer/regionX_regionZ</li>
     * </ul>
     *
     * @param chunk 区块数据
     * @return 相对路径字符串
     */
    private static String buildRelativePathForCache(ChunkMapData chunk) {
        String xaeroDim = chunk.dimension;

        if (chunk.caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }
}
