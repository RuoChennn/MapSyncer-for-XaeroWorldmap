package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.util.HashUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Xaero 地图集成器。
 * 提供与 Xaero's World Map 模组的交互功能，包括地图数据写入和目录路径管理。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>获取当前服务器和地图目录路径</li>
 *   <li>写入服务端同步的地图数据到 Xaero 目录</li>
 *   <li>管理同步期间的区域追踪</li>
 *   <li>重置区域加载状态，触发地图重新加载</li>
 * </ul>
 *
 * <p>目录结构：</p>
 * <ul>
 *   <li>多人游戏：xaero/world-map/Multiplayer_<serverIP>/<dimension>/mw$<worldId>/</li>
 *   <li>单机游戏：xaero/world-map/Multiplayer_Singleplayer/<dimension>/mw$<worldId>/</li>
 *   <li>局域网游戏：xaero/world-map/Multiplayer_LAN/<dimension>/mw$<worldId>/</li>
 * </ul>
 */
public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);

    /** 同步期间更新的区域集合，用于选择性重置 */
    private static volatile Set<RegionCoord> updatedRegions = new HashSet<>();

    /** 同步前预卸载的区域集合（原本已加载的），用于同步后设置 loadState=4 */
    private static volatile Set<RegionCoord> preUnloadedRegions = new HashSet<>();

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
     * 记录同步期间更新的区域。
     * 这些区域将在重新加载时被选择性重置。
     * 包含 caveLayer 信息，用于区分地表层和洞穴层。
     *
     * @param chunks 同步期间接收的区块数据列表
     */
    public static void recordUpdatedRegions(List<ChunkMapData> chunks) {
        // Clear existing set first to prevent memory leak
        // (previous pattern "updatedRegions = regions" created new Set but old Set remained in memory)
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
        // Clear existing set first to prevent memory leak
        updatedRegions.clear();
        updatedRegions.addAll(coords);
        LOGGER.debug("Recorded {} updated region coords for selective reset", updatedRegions.size());
    }

    /**
     * 计算视距范围内的区域坐标。
     * 根据玩家的位置和视距设置，计算需要关注的区域集合。
     * 默认使用地表层 (Integer.MAX_VALUE)。
     *
     * <p>计算逻辑：</p>
     * <ul>
     *   <li>视距 = 渲染距离（chunks 半径）</li>
     *   <li>一个 region = 32 chunks</li>
     *   <li>根据玩家位置计算视距范围可能跨越的 region</li>
     * </ul>
     *
     * @return 视距范围内的区域坐标集合（地表层）
     */
    public static Set<RegionCoord> getViewDistanceRegions() {
        return getViewDistanceRegions(Integer.MAX_VALUE);
    }

    /**
     * 计算视距范围内的区域坐标。
     * 根据玩家的位置和视距设置，计算需要关注的区域集合。
     *
     * <p>计算逻辑：</p>
     * <ul>
     *   <li>视距 = 渲染距离（chunks 半径）</li>
     *   <li>一个 region = 32 chunks</li>
     *   <li>根据玩家位置计算视距范围可能跨越的 region</li>
     * </ul>
     *
     * @param caveLayer 洞穴层编号，地表层使用 Integer.MAX_VALUE
     * @return 视距范围内的区域坐标集合
     */
    public static Set<RegionCoord> getViewDistanceRegions(int caveLayer) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return new HashSet<>();
        }

        // Get player position in chunks
        int playerChunkX = player.getBlockX() >> 4;  // 16 blocks per chunk
        int playerChunkZ = player.getBlockZ() >> 4;

        // Get view distance (render distance) in chunks (radius)
        int viewDistance = mc.options.renderDistance().get();

        // 计算视距范围（chunks）
        // 从 playerChunkX - viewDistance 到 playerChunkX + viewDistance
        int minChunkX = playerChunkX - viewDistance;
        int maxChunkX = playerChunkX + viewDistance;
        int minChunkZ = playerChunkZ - viewDistance;
        int maxChunkZ = playerChunkZ + viewDistance;

        // 转换为 region 坐标
        // region 边界: regionX * 32 到 (regionX + 1) * 32 - 1
        int minRegionX = minChunkX >> 5;  // floor division for negative numbers
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;

        // 处理负数情况的 floor division
        // Java 的 >> 5 对负数是 floor，正数也是 floor，所以这里正确

        Set<RegionCoord> viewRegions = new HashSet<>();

        // 添加视距范围内的所有 region（使用指定的 caveLayer）
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                viewRegions.add(new RegionCoord(rx, rz, caveLayer));
            }
        }

        LOGGER.debug("View distance regions: viewDistance={}, chunks ({},{}) to ({},{}), regions ({},{}) to ({},{}), total {} (layer={})",
                viewDistance, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                minRegionX, minRegionZ, maxRegionX, maxRegionZ, viewRegions.size(), caveLayer);

        return viewRegions;
    }

    /**
     * 卸载玩家视野范围内的所有region。
     * 在同步当前维度时调用，让视野范围内的region可以重新加载服务端数据。
     *
     * @return 卸载的区域数量
     */
    public static int unloadViewDistanceRegions() {
        Set<RegionCoord> viewRegions = getViewDistanceRegions();
        if (viewRegions.isEmpty()) {
            LOGGER.info("No view distance regions to unload");
            return 0;
        }

        LOGGER.info("Unloading {} view distance regions before sync", viewRegions.size());
        return resetSpecificRegionLoadStates(viewRegions);
    }

    /**
     * 仅重置指定区域的加载状态。
     * 公共方法，供外部调用者使用。
     *
     * @param regionsToReset 需要重置的区域集合
     * @return 重置的区域数量
     */
    public static int resetSpecificRegionLoadStates(Set<RegionCoord> regionsToReset) {
        int resetCount = 0;

        try {
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Method getCurrentSession = worldMapSessionClass.getMethod("getCurrentSession");
            Object session = getCurrentSession.invoke(null);

            if (session == null) {
                LOGGER.warn("Could not get WorldMapSession for selective reset");
                return 0;
            }

            Method getMapProcessor = worldMapSessionClass.getMethod("getMapProcessor");
            Object mapProcessor = getMapProcessor.invoke(session);

            if (mapProcessor == null) {
                LOGGER.warn("Could not get MapProcessor for selective reset");
                return 0;
            }

            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Method getMapWorld = mapProcessorClass.getMethod("getMapWorld");
            Object mapWorld = getMapWorld.invoke(mapProcessor);

            if (mapWorld == null) {
                LOGGER.warn("Could not get MapWorld for selective reset");
                return 0;
            }

            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            Method getCurrentDimension = mapWorldClass.getMethod("getCurrentDimension");
            Object mapDimension = getCurrentDimension.invoke(mapWorld);

            if (mapDimension == null) {
                LOGGER.warn("Could not get current dimension for selective reset");
                return 0;
            }

            // Get the LayeredRegionManager
            Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            Method getLayeredMapRegions = mapDimensionClass.getMethod("getLayeredMapRegions");
            Object layeredRegionManager = getLayeredMapRegions.invoke(mapDimension);

            if (layeredRegionManager == null) {
                LOGGER.warn("Could not get LayeredRegionManager");
                return 0;
            }

            // Get the surface layer
            Class<?> layeredRegionManagerClass = Class.forName("xaero.map.region.LayeredRegionManager");
            Method getLayer = layeredRegionManagerClass.getMethod("getLayer", int.class);
            Object mapLayer = getLayer.invoke(layeredRegionManager, Integer.MAX_VALUE);

            if (mapLayer == null) {
                LOGGER.warn("Could not get surface MapLayer");
                return 0;
            }

            // Get LeveledRegionManager
            Class<?> mapLayerClass = Class.forName("xaero.map.region.MapLayer");
            Method getMapRegions = mapLayerClass.getMethod("getMapRegions");
            Object leveledRegionManager = getMapRegions.invoke(mapLayer);

            if (leveledRegionManager == null) {
                LOGGER.warn("Could not get LeveledRegionManager");
                return 0;
            }

            // Access regionTextureMap
            Class<?> leveledRegionManagerClass = Class.forName("xaero.map.region.LeveledRegionManager");
            Field regionTextureMapField = leveledRegionManagerClass.getDeclaredField("regionTextureMap");
            regionTextureMapField.setAccessible(true);
            Object regionTextureMap = regionTextureMapField.get(leveledRegionManager);

            if (regionTextureMap != null && regionTextureMap instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) regionTextureMap;

                for (Object columnEntry : map.values()) {
                    if (columnEntry instanceof java.util.Map) {
                        java.util.Map<?, ?> column = (java.util.Map<?, ?>) columnEntry;
                        for (Object regionEntry : column.values()) {
                            // Traverse and selectively reset
                            resetCount += selectiveResetLeafRegions(regionEntry, regionsToReset);
                        }
                    }
                }
            }

            LOGGER.info("Selective reset completed: {} regions reset", resetCount);

        } catch (Exception e) {
            LOGGER.warn("Failed to selective reset regions: {}", e.getMessage());
        }

        return resetCount;
    }

    /**
     * 遍历区域并选择性重置目标集合中的区域。
     * 记录原本已加载的 region（loadState==2）到 preUnloadedRegions，
     * 用于同步后区分使用 loadState=4（需要重载）或 loadState=0（未加载）。
     *
     * @param region 区域对象
     * @param regionsToReset 需要重置的区域集合
     * @return 重置的区域数量
     */
    private static int selectiveResetLeafRegions(Object region, Set<RegionCoord> regionsToReset) {
        int count = 0;
        try {
            Class<?> regionClass = region.getClass();

            // Check if this is a MapRegion (leaf)
            if (regionClass.getName().equals("xaero.map.region.MapRegion")) {
                // Get region coordinates from the MapRegion object
                Field regionXField = regionClass.getDeclaredField("regionX");
                Field regionZField = regionClass.getDeclaredField("regionZ");
                regionXField.setAccessible(true);
                regionZField.setAccessible(true);
                int rx = regionXField.getInt(region);
                int rz = regionZField.getInt(region);

                RegionCoord coord = new RegionCoord(rx, rz);

                // Only reset if this region is in our target set
                if (regionsToReset.contains(coord)) {
                    Field loadStateField = regionClass.getDeclaredField("loadState");
                    loadStateField.setAccessible(true);
                    byte currentLoadState = loadStateField.getByte(region);

                    if (currentLoadState == 2) {  // Only reset loaded regions
                        // 记录原本已加载的 region，同步后使用 loadState=4
                        preUnloadedRegions.add(coord);

                        loadStateField.setByte(region, (byte) 0);
                        count++;

                        LOGGER.debug("Pre-unloaded region ({}, {}) was loaded, recorded for loadState=4", rx, rz);
                    } else if (currentLoadState == 4) {
                        // 需要重载的状态也记录为已加载
                        preUnloadedRegions.add(coord);
                        loadStateField.setByte(region, (byte) 0);
                        count++;
                    }
                }
            } else if (regionClass.getName().equals("xaero.map.region.BranchLeveledRegion")) {
                // Traverse children
                Field childrenField = regionClass.getDeclaredField("children");
                childrenField.setAccessible(true);
                Object childrenArray = childrenField.get(region);

                if (childrenArray != null && childrenArray.getClass().isArray()) {
                    int outerLength = java.lang.reflect.Array.getLength(childrenArray);
                    for (int i = 0; i < outerLength; i++) {
                        Object innerArray = java.lang.reflect.Array.get(childrenArray, i);
                        if (innerArray != null && innerArray.getClass().isArray()) {
                            int innerLength = java.lang.reflect.Array.getLength(innerArray);
                            for (int j = 0; j < innerLength; j++) {
                                Object child = java.lang.reflect.Array.get(innerArray, j);
                                if (child != null) {
                                    count += selectiveResetLeafRegions(child, regionsToReset);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in selective reset: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 获取当前服务器的基础目录（null 目录）。
     * 路径结构：xaero/world-map/Multiplayer_<server>/null/
     *
     * <p>支持多种游戏模式：</p>
     * <ul>
     *   <li>多人游戏：Multiplayer_<serverIP>/</li>
     *   <li>单机游戏/局域网：单机游戏使用 "Singleplayer" 目录名，局域网使用 LAN server 特殊处理</li>
     * </ul>
     *
     * @return 服务器基础目录路径，如果未连接返回 null
     */
    public static Path getCurrentServerBaseDirectory() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        LOGGER.debug("getCurrentServerBaseDirectory: connection={}", connection);
        if (connection == null) {
            LOGGER.warn("getCurrentServerBaseDirectory: connection is null");
            return null;
        }

        // 尝试获取 ServerData（多人游戏）
        ServerData serverData = connection.getServerData();
        LOGGER.debug("getCurrentServerBaseDirectory: serverData={}, serverData.ip={}",
                serverData, serverData != null ? serverData.ip : "N/A");

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = gameDir.resolve("xaero").resolve("world-map");

        String serverIP;

        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            // 多人游戏模式
            serverIP = serverData.ip;

            // Clean up server IP
            int portDivider = serverIP.lastIndexOf(":");
            if (portDivider > 0 && serverIP.indexOf(":") != serverIP.lastIndexOf(":")) {
                portDivider = serverIP.lastIndexOf("]:") + 1;
            }
            if (portDivider > 0) {
                serverIP = serverIP.substring(0, portDivider);
            }
            serverIP = serverIP.replace("[", "").replace("]", "");
            serverIP = serverIP.replaceAll(":", ".");
            while (serverIP.endsWith(".")) {
                serverIP = serverIP.substring(0, serverIP.length() - 1);
            }
            if (serverIP.isEmpty()) {
                serverIP = "Empty Address";
            }
        } else {
            // 单机游戏或局域网游戏模式
            // 检查是否是单机游戏
            if (mc.hasSingleplayerServer()) {
                serverIP = "Singleplayer";
                LOGGER.debug("Singleplayer mode detected");
            } else {
                // 局域网游戏：尝试从连接信息获取
                // 局域网服务器通常使用 localhost 或 LAN
                serverIP = "LAN";
                LOGGER.debug("LAN mode detected");
            }
        }

        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        Path dimDir = serverDir.resolve("null");

        // 如果目录不存在，尝试查找已存在的 Xaero 目录
        if (!dimDir.toFile().exists()) {
            // 尝试扫描 world-map 目录找到匹配的服务器目录
            try {
                if (worldMapDir.toFile().exists() && worldMapDir.toFile().isDirectory()) {
                    Files.list(worldMapDir)
                        .filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                        .filter(p -> Files.isDirectory(p))
                        .forEach(p -> {
                            Path candidateDim = p.resolve("null");
                            if (candidateDim.toFile().exists()) {
                                LOGGER.debug("Found existing Xaero directory: {}", candidateDim);
                            }
                        });
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to scan world-map directory: {}", e.getMessage());
            }

            // 单机游戏模式下，自动创建目录（首次同步）
            if (serverIP.equals("Singleplayer") || serverIP.equals("LAN")) {
                LOGGER.info("Creating Xaero directory for {} mode: {}", serverIP, dimDir);
                try {
                    Files.createDirectories(dimDir);
                } catch (IOException e) {
                    LOGGER.warn("Failed to create Xaero directory: {}", e.getMessage());
                }
            }
        }

        LOGGER.debug("Server base directory: {}", dimDir);
        return dimDir;
    }

    /**
     * 获取当前连接服务器的服务器目录（Multiplayer_<serverIP>）。
     * 这是包含所有维度文件夹的父目录。
     * 路径结构：xaero/world-map/Multiplayer_<server>/
     *
     * @return 服务器目录路径，如果未连接返回 null
     */
    public static Path getCurrentServerDirectory() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            LOGGER.warn("getCurrentServerDirectory: connection is null");
            return null;
        }

        ServerData serverData = connection.getServerData();
        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = gameDir.resolve("xaero").resolve("world-map");

        String serverIP;

        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            serverIP = serverData.ip;

            // Clean up server IP
            int portDivider = serverIP.lastIndexOf(":");
            if (portDivider > 0 && serverIP.indexOf(":") != serverIP.lastIndexOf(":")) {
                portDivider = serverIP.lastIndexOf("]:") + 1;
            }
            if (portDivider > 0) {
                serverIP = serverIP.substring(0, portDivider);
            }
            serverIP = serverIP.replace("[", "").replace("]", "");
            serverIP = serverIP.replaceAll(":", ".");
            while (serverIP.endsWith(".")) {
                serverIP = serverIP.substring(0, serverIP.length() - 1);
            }
            if (serverIP.isEmpty()) {
                serverIP = "Empty Address";
            }
        } else {
            if (mc.hasSingleplayerServer()) {
                serverIP = "Singleplayer";
            } else {
                serverIP = "LAN";
            }
        }

        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        LOGGER.debug("Server directory: {}", serverDir);
        return serverDir;
    }

    /**
     * 写入服务端接收的地图数据到正确位置。
     * 使用服务端提供的 worldId 确保目录路径正确。
     * 返回 mw 目录路径供后续处理。
     * 同时保存服务端时间戳到本地缓存供未来同步比较。
     *
     * @param chunks 接收的区块数据列表
     * @param serverWorldId 服务端的 worldId
     * @return 最后写入的 mw 目录路径，如果写入失败返回 null
     */
    public static Path writeMapDataAndReturnDir(List<ChunkMapData> chunks, int serverWorldId) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            LOGGER.error("Not connected to server");
            return null;
        }

        ServerData serverData = connection.getServerData();
        if (serverData == null) {
            LOGGER.error("No server data available");
            return null;
        }

        // Get server address
        String serverIP = serverData.ip;
        if (serverIP == null || serverIP.isEmpty()) {
            serverIP = "Unknown";
        }

        // Clean up server IP
        int portDivider = serverIP.lastIndexOf(":");
        if (portDivider > 0 && serverIP.indexOf(":") != serverIP.lastIndexOf(":")) {
            portDivider = serverIP.lastIndexOf("]:") + 1;
        }
        if (portDivider > 0) {
            serverIP = serverIP.substring(0, portDivider);
        }
        serverIP = serverIP.replace("[", "").replace("]", "");
        serverIP = serverIP.replaceAll(":", ".");
        while (serverIP.endsWith(".")) {
            serverIP = serverIP.substring(0, serverIP.length() - 1);
        }
        if (serverIP.isEmpty()) {
            serverIP = "Empty Address";
        }

        LOGGER.info("Using server worldId: {}", serverWorldId);

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = gameDir.resolve("xaero").resolve("world-map");
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);

        // Get timestamp cache for this server
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);

        Path lastMwDir = null;
        for (ChunkMapData chunk : chunks) {
            lastMwDir = writeChunkDataAndGetDir(chunk, serverDir, serverWorldId);

            // Update timestamp cache with server's timestamp and computed hash
            String relativePath = buildRelativePathForCache(chunk);
            String hash = HashUtils.computeHash(chunk.data);
            tsCache.update(relativePath, chunk.timestampSeconds, hash);
            LOGGER.debug("Updated timestamp cache for {}: ts={}s, hash={}",
                    relativePath, chunk.timestampSeconds, hash);
        }

        // Save timestamp cache after all chunks written
        tsCache.save();
        LOGGER.info("Saved timestamp cache for {} regions", chunks.size());

        return lastMwDir;
    }

    /**
     * 构建时间戳缓存的服务器格式相对路径。
     *
     * <p>格式（匹配服务端 GenerationCache 格式）：</p>
     * <ul>
     *   <li>地表：xaeroDim/regionX_regionZ（如 twilightforest$twilight_forest/0_0）</li>
     *   <li>洞穴：xaeroDim/caves/layer/regionX_regionZ</li>
     * </ul>
     *
     * <p>注意：chunk.dimension 已经是 Xaero 格式，直接使用即可，无需转换。</p>
     *
     * @param chunk 区块数据
     * @return 相对路径字符串
     */
    private static String buildRelativePathForCache(ChunkMapData chunk) {
        // chunk.dimension 已经是 Xaero 格式（如 twilightforest$twilight_forest）
        // 直接使用，与服务端 GenerationCache 的 key 格式保持一致
        String xaeroDim = chunk.dimension;

        if (chunk.caveLayer == Integer.MAX_VALUE) {
            // 地表层
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            // 洞穴层
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    /**
     * 构建时间戳缓存的服务器格式相对路径。
     * 支持 caves/<layer> 目录结构：
     * <ul>
     *   <li>地表：Multiplayer_<server>/<xaero_dimension>/mw$<worldId>/<regionX_regionZ>.zip</li>
     *   <li>洞穴：Multiplayer_<server>/<xaero_dimension>/mw$<worldId>/caves/<layer>/<regionX_regionZ>.zip</li>
     * </ul>
     *
     * @param chunk 区块数据
     * @param worldId worldId
     * @return mw 目录路径
     */
    public static Path writeChunkDataAndGetMwDir(ChunkMapData chunk, int worldId) {
        Path serverDir = getCurrentServerDirectory();
        if (serverDir == null) {
            LOGGER.warn("无法获取服务器目录");
            return null;
        }
        return writeChunkDataAndGetDir(chunk, serverDir, worldId);
    }

    /**
     * 写入区块数据并返回 mw 目录路径。
     * 支持 caves/<layer> 目录结构：
     * <ul>
     *   <li>地表：Multiplayer_<server>/<xaero_dimension>/mw$<worldId>/<regionX_regionZ>.zip</li>
     *   <li>洞穴：Multiplayer_<server>/<xaero_dimension>/mw$<worldId>/caves/<layer>/<regionX_regionZ>.zip</li>
     * </ul>
     *
     * @param chunk 区块数据
     * @param serverDir 服务器目录
     * @param worldId worldId
     * @return mw 目录路径
     */
    private static Path writeChunkDataAndGetDir(ChunkMapData chunk, Path serverDir, int worldId) {
        // chunk.dimension 已经是 Xaero 格式，直接使用
        // 注意：服务端发送的 dimension 就是 Xaero 格式（如 null, DIM-1, DIM1, twilightforest$twilight_forest）
        String xaeroDim = chunk.dimension;
        Path dimDir = serverDir.resolve(xaeroDim);
        Path mwDir = dimDir.resolve("mw$" + worldId);

        // 根据 caveLayer 决定最终目录
        Path targetDir;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            // 地表层：直接在 mw$<worldId> 目录
            targetDir = mwDir;
        } else {
            // 洞穴层：存放到 caves/<layer> 子目录
            targetDir = mwDir.resolve("caves").resolve(String.valueOf(chunk.caveLayer));
        }

        Path outputFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip");
        Path tempFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip.temp");

        try {
            Files.createDirectories(targetDir);

            // Direct write: replace existing file with server data (no incremental merge)
            Files.write(tempFile, chunk.data);
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Wrote map file: {} (layer={}, {} bytes)", outputFile,
                chunk.isSurfaceLayer() ? "surface" : chunk.caveLayer, chunk.data.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write map file: {}", outputFile, e);
        }

        return mwDir;
    }
}