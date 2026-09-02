package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CheckedOutputStream;
import java.util.zip.CRC32;

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
    private static final Set<RegionCoord> updatedRegions = ConcurrentHashMap.newKeySet();

    /** 同步前预卸载的区域集合（原本已加载的），用于同步后设置 loadState=4 */
    private static final Set<RegionCoord> preUnloadedRegions = ConcurrentHashMap.newKeySet();

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
        return Set.copyOf(updatedRegions);
    }

    /**
     * 获取同步前预卸载的区域集合（原本已加载的）。
     * 这些区域在同步后应使用 loadState=4（需要重载）而非 loadState=0（未加载）。
     *
     * @return 预卸载区域集合的副本
     */
    public static Set<RegionCoord> getPreUnloadedRegions() {
        return Set.copyOf(preUnloadedRegions);
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
     * 写入结果：mw 目录、最终 zip 路径，以及写盘时计算的 CRC32（与 {@link HashUtils#computeFileHash} 一致）。
     */
    public record RegionWriteResult(Path mwDir, Path outputFile, String crc32Hash) {}

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
     * @return 写入成功时返回 mw 目录与 zip 路径，失败返回 null
     */
    public static RegionWriteResult writeChunkData(ChunkMapData chunk, Path serverDir, int worldId) {
        String xaeroDim = chunk.dimension;
        Path dimDir = serverDir.resolve(xaeroDim);

        // 优先使用 Xaero 已有的 mw$ 目录（客户端 Xaero 生成的 worldId），避免与服务端 worldId 不匹配
        Path mwDir = findExistingMwDir(dimDir);
        if (mwDir == null) {
            // 没有现有目录，使用服务端 worldId 创建
            mwDir = dimDir.resolve("mw$" + worldId);
        } else {
            LOGGER.debug("Using existing Xaero mw$ directory: {} (server worldId={})", mwDir, worldId);
        }

        Path targetDir;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            targetDir = mwDir;
        } else {
            targetDir = mwDir.resolve("caves").resolve(String.valueOf(chunk.caveLayer));
        }

        Path outputFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip");
        Path tempFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip.temp");

        if (!HashUtils.isValidRegionZip(chunk.data)) {
            LOGGER.error("Refusing to write invalid region zip: {} ({} bytes)", outputFile, chunk.data.length);
            return null;
        }

        CRC32 crc32 = new CRC32();
        try {
            Files.createDirectories(targetDir);

            try (OutputStream fileOut = Files.newOutputStream(tempFile);
                 CheckedOutputStream checkedOut = new CheckedOutputStream(fileOut, crc32)) {
                checkedOut.write(chunk.data);
            }
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Wrote map file: {} (layer={}, {} bytes)", outputFile,
                chunk.isSurfaceLayer() ? "surface" : chunk.caveLayer, chunk.data.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write map file: {}", outputFile, e);
            return null;
        }

        return new RegionWriteResult(mwDir, outputFile, String.format("%08x", crc32.getValue()));
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
            RegionWriteResult result = writeChunkData(chunk, serverDir, worldId);
            if (result == null) {
                continue;
            }
            lastMwDir = result.mwDir();

            if (tsCache != null) {
                String relativePath = buildRelativePathForCache(chunk);
                tsCache.update(relativePath, chunk.timestampSeconds, result.crc32Hash());
                LOGGER.debug("Updated timestamp cache for {}: ts={}s, hash={}",
                        relativePath, chunk.timestampSeconds, result.crc32Hash());
            }
        }

        if (tsCache != null) {
            tsCache.save();
        }
        LOGGER.info("Saved timestamp cache for {} regions", chunks.size());

        return lastMwDir;
    }

    /**
     * 构建时间戳缓存的相对路径（匹配服务端 GenerationCache 格式）。
     */
    public static String buildRelativePathForCache(ChunkMapData chunk) {
        String xaeroDim = chunk.dimension;

        if (chunk.caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    /**
     * 查找 Xaero 已经创建的 mw$ 目录。
     * Xaero 启动时会自动创建 mw$<worldId> 目录，客户端应该复用这个目录，
     * 而不是用服务端的 worldId 创建新目录（两者不匹配会导致地图不显示）。
     *
     * @param dimDir 维度目录（null/）
     * @return 现有的 mw$ 目录路径，如果没有找到返回 null
     */
    private static Path findExistingMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) {
            return null;
        }
        try {
            // 查找所有 mw$ 目录
            try (var stream = Files.list(dimDir)) {
                return stream
                        .filter(p -> p.getFileName().toString().startsWith("mw$"))
                        .filter(Files::isDirectory)
                        .max((a, b) -> {
                            // 使用最后修改时间作为备选
                            try {
                                return Long.compare(Files.getLastModifiedTime(a).toMillis(),
                                        Files.getLastModifiedTime(b).toMillis());
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .orElse(null);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to search for existing mw$ directory: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 清除单个 region 的 Xaero 运行时缓存文件（.xwmc），可在 IO 线程调用。
     *
     * <p>地表层：{@code cache[_*]/{x}_{z}.xwmc}；洞穴层：{@code cache[_*]/caves/{layer}/{x}_{z}.xwmc}。</p>
     */
    public static void clearRegionCacheFiles(Path mwDir, RegionCoord coord) {
        if (mwDir == null) {
            return;
        }

        String baseName = coord.x() + "_" + coord.z();
        for (Path cacheDir : findCacheDirectories(mwDir)) {
            Path cacheRoot = coord.isSurfaceLayer()
                ? cacheDir
                : cacheDir.resolve("caves").resolve(String.valueOf(coord.caveLayer()));
            deleteRegionCacheFile(cacheRoot.resolve(baseName + ".xwmc"));
            deleteRegionCacheFile(cacheRoot.resolve(baseName + ".xwmc.outdated"));
        }
    }

    private static void deleteRegionCacheFile(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            Files.deleteIfExists(cacheFile);
            LOGGER.debug("Cleared region cache: {}", cacheFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to clear region cache: {}", cacheFile, e);
        }
    }

    private static List<Path> findCacheDirectories(Path mwDir) {
        List<Path> cacheDirs = new ArrayList<>();

        try {
            Path cache = mwDir.resolve("cache");
            Path cache1 = mwDir.resolve("cache_1");

            if (Files.isDirectory(cache)) {
                cacheDirs.add(cache);
            }
            if (Files.isDirectory(cache1)) {
                cacheDirs.add(cache1);
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mwDir, "cache_*")) {
                for (Path dir : stream) {
                    if (Files.isDirectory(dir) && !cacheDirs.contains(dir)) {
                        cacheDirs.add(dir);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to find cache directories under {}", mwDir, e);
        }

        return cacheDirs;
    }
}
