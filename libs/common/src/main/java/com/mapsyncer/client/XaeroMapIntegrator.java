package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.platform.XaeroReflectionHelper;
import com.mapsyncer.util.XaeroPathResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <p>纯数据操作（文件写入、区域追踪）已移至 {@link XaeroMapDataHandler}。
 * 此类仅保留依赖 Minecraft API 的功能。</p>
 *
 * <p>目录结构（自动检测，兼容 1.20.x 和 1.21.x+）：</p>
 * <ul>
 *   <li>多人游戏：&lt;worldMapDir&gt;/Multiplayer_&lt;serverIP&gt;/&lt;dimension&gt;/mw$&lt;worldId&gt;/</li>
 *   <li>单机游戏：&lt;worldMapDir&gt;/Multiplayer_Singleplayer/&lt;dimension&gt;/mw$&lt;worldId&gt;/</li>
 *   <li>局域网游戏：&lt;worldMapDir;/Multiplayer_LAN/&lt;dimension&gt;/mw$&lt;worldId&gt;/</li>
 * </ul>
 */
public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);

    /**
     * 自动检测 Xaero's World Map 数据目录。
     * 委托给 {@link XaeroPathResolver}，兼容 1.20.x 和 1.21.x+ 路径。
     */
    public static Path getWorldMapDir(Path gameDir) {
        return XaeroPathResolver.getWorldMapDir(gameDir);
    }

    /**
     * 计算视距范围内的区域坐标。
     * 根据玩家的位置和视距设置，计算需要关注的区域集合。
     * 默认使用地表层 (Integer.MAX_VALUE)。
     *
     * @return 视距范围内的区域坐标集合（地表层）
     */
    public static Set<XaeroMapDataHandler.RegionCoord> getViewDistanceRegions() {
        return getViewDistanceRegions(Integer.MAX_VALUE);
    }

    /**
     * 计算视距范围内的区域坐标。
     * 根据玩家的位置和视距设置，计算需要关注的区域集合。
     *
     * @param caveLayer 洞穴层编号，地表层使用 Integer.MAX_VALUE
     * @return 视距范围内的区域坐标集合
     */
    public static Set<XaeroMapDataHandler.RegionCoord> getViewDistanceRegions(int caveLayer) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return new HashSet<>();
        }

        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int viewDistance = mc.options.renderDistance().get();

        int minChunkX = playerChunkX - viewDistance;
        int maxChunkX = playerChunkX + viewDistance;
        int minChunkZ = playerChunkZ - viewDistance;
        int maxChunkZ = playerChunkZ + viewDistance;

        int minRegionX = minChunkX >> 5;
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;

        Set<XaeroMapDataHandler.RegionCoord> viewRegions = new HashSet<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                viewRegions.add(new XaeroMapDataHandler.RegionCoord(rx, rz, caveLayer));
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
        Set<XaeroMapDataHandler.RegionCoord> viewRegions = getViewDistanceRegions();
        if (viewRegions.isEmpty()) {
            LOGGER.info("No view distance regions to unload");
            return 0;
        }

        LOGGER.info("Unloading {} view distance regions before sync", viewRegions.size());
        return resetSpecificRegionLoadStates(viewRegions);
    }

    /**
     * 仅重置指定区域的加载状态。
     *
     * @param regionsToReset 需要重置的区域集合
     * @return 重置的区域数量
     */
    public static int resetSpecificRegionLoadStates(Set<XaeroMapDataHandler.RegionCoord> regionsToReset) {
        int resetCount = 0;

        if (!XaeroReflectionHelper.isInitialized()) {
            LOGGER.warn("XaeroReflectionHelper not initialized for selective reset");
            return 0;
        }

        java.util.Map<Integer, Set<XaeroMapDataHandler.RegionCoord>> byLayer = new java.util.HashMap<>();
        for (XaeroMapDataHandler.RegionCoord coord : regionsToReset) {
            byLayer.computeIfAbsent(coord.caveLayer(), k -> new java.util.HashSet<>()).add(coord);
        }

        try {
            for (var entry : byLayer.entrySet()) {
                int caveLayer = entry.getKey();
                Set<XaeroMapDataHandler.RegionCoord> layerTargets = entry.getValue();
                Object regionTextureMap = XaeroReflectionHelper.getRegionTextureMap(caveLayer);
                if (regionTextureMap == null) {
                    LOGGER.warn("Could not get regionTextureMap for layer {}", caveLayer);
                    continue;
                }

                if (regionTextureMap instanceof Map<?, ?> map) {
                    for (Object columnEntry : map.values()) {
                        if (columnEntry instanceof Map<?, ?> column) {
                            for (Object regionEntry : column.values()) {
                                resetCount += selectiveResetLeafRegions(regionEntry, layerTargets, caveLayer);
                            }
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
     * 记录原本已加载的 region 到 preUnloadedRegions。
     */
    private static int selectiveResetLeafRegions(Object region, Set<XaeroMapDataHandler.RegionCoord> regionsToReset, int caveLayer) {
        int count = 0;
        try {
            if (XaeroReflectionHelper.isMapRegion(region)) {
                int rx = XaeroReflectionHelper.getRegionX(region);
                int rz = XaeroReflectionHelper.getRegionZ(region);

                if (rx == -1 || rz == -1) {
                    LOGGER.warn("无法获取区域坐标 (region={})", region);
                    return 0;
                }

                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(rx, rz, caveLayer);

                if (regionsToReset.contains(coord)) {
                    byte currentLoadState = XaeroReflectionHelper.getLoadState(region);

                    if (currentLoadState == -1) {
                        LOGGER.warn("无法获取区域 ({}, {}) 的 loadState", rx, rz);
                        return 0;
                    }

                    if (currentLoadState == XaeroReflectionHelper.LOAD_STATE_LOADED) {
                        XaeroMapDataHandler.getPreUnloadedRegionsInternal().add(coord);

                        boolean success = XaeroReflectionHelper.setLoadState(region, XaeroReflectionHelper.LOAD_STATE_UNLOADED);
                        if (success) {
                            count++;
                            LOGGER.debug("Pre-unloaded region ({}, {}) layer={} was loaded, recorded for loadState=4", rx, rz, caveLayer);
                        } else {
                            LOGGER.warn("设置区域 ({}, {}) loadState 失败", rx, rz);
                        }
                    } else if (currentLoadState == XaeroReflectionHelper.LOAD_STATE_CLEARED) {
                        XaeroMapDataHandler.getPreUnloadedRegionsInternal().add(coord);
                        boolean success = XaeroReflectionHelper.setLoadState(region, XaeroReflectionHelper.LOAD_STATE_UNLOADED);
                        if (success) {
                            count++;
                        }
                    }
                }
            } else if (XaeroReflectionHelper.isBranchLeveledRegion(region)) {
                Object childrenArray = XaeroReflectionHelper.getBranchChildren(region);

                if (childrenArray != null && childrenArray.getClass().isArray()) {
                    int outerLength = Array.getLength(childrenArray);
                    for (int i = 0; i < outerLength; i++) {
                        Object innerArray = Array.get(childrenArray, i);
                        if (innerArray != null && innerArray.getClass().isArray()) {
                            int innerLength = Array.getLength(innerArray);
                            for (int j = 0; j < innerLength; j++) {
                                Object child = Array.get(innerArray, j);
                                if (child != null) {
                                    count += selectiveResetLeafRegions(child, regionsToReset, caveLayer);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error in selective reset: {}", e.getMessage(), e);
        }
        return count;
    }

    /**
     * 清理服务器 IP 地址，去除端口和方括号。
     *
     * @param rawIP 原始 IP 字符串
     * @return 清理后的 IP
     */
    public static String cleanServerIP(String rawIP) {
        int portDivider = rawIP.lastIndexOf(":");
        if (portDivider > 0 && rawIP.indexOf(":") != rawIP.lastIndexOf(":")) {
            portDivider = rawIP.lastIndexOf("]:") + 1;
        }
        if (portDivider > 0) {
            rawIP = rawIP.substring(0, portDivider);
        }
        rawIP = rawIP.replace("[", "").replace("]", "");
        rawIP = rawIP.replaceAll(":", ".");
        while (rawIP.endsWith(".")) {
            rawIP = rawIP.substring(0, rawIP.length() - 1);
        }
        if (rawIP.isEmpty()) {
            rawIP = "Empty Address";
        }
        return rawIP;
    }

    /**
     * 获取当前服务器 IP（已清理）。
     * 多人游戏返回清理后的 IP，单机返回 "Singleplayer"，局域网返回 "LAN"。
     *
     * @return 服务器 IP 字符串，如果未连接返回 null
     */
    public static String getCurrentServerIP() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return null;
        }

        ServerData serverData = connection.getServerData();
        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            return cleanServerIP(serverData.ip);
        }

        if (mc.hasSingleplayerServer()) {
            return "Singleplayer";
        }
        return "LAN";
    }

    /**
     * 获取当前服务器的统一标识名（多入口复用同一地图缓存）。
     * 优先使用服务端握手下发的 serverName，否则回退到 IP 命名。
     *
     * @return 清理后的服务器标识名，可直接用于目录名
     */
    private static String getCurrentServerName() {
        // 优先使用服务端统一标识
        String serverName = ClientSyncSession.get().getServerName();
        if (serverName != null && !serverName.isEmpty()) {
            return sanitizeForFileName(serverName);
        }
        // 回退到 IP
        String ip = getCurrentServerIP();
        if (ip == null || ip.isEmpty()) {
            return "Unknown";
        }
        return cleanServerIP(ip);
    }

    /**
     * 将服务端标识名清理为安全的文件名字符串。
     * 替换文件系统不允许的字符，截断过长名称。
     */
    private static String sanitizeForFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "Server";
        }
        // 替换 Windows/Linux 文件系统不允许的字符
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        // 去除首尾空格和点
        safe = safe.replaceAll("^[ .]+|[ .]+$", "");
        // 截断过长名称（限制 64 字符）
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        return safe.isEmpty() ? "Server" : safe;
    }

    /**
     * 获取当前服务器基础目录（null 目录）。
     * 路径结构：&lt;worldMapDir&gt;/Multiplayer_&lt;serverIP&gt;/&lt;dimension&gt;/mw$&lt;worldId&gt;/
     * &lt;p&gt;注意：使用 IP 而非 serverName，因为 Xaero 地图模组本身按 IP 读取目录。&lt;/p&gt;
     * &lt;p&gt;worldId 优先使用客户端 Xaero 已有的 mw$ 目录，避免与服务端 worldId 不匹配导致地图不显示。&lt;/p&gt;
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

        ServerData serverData = connection.getServerData();
        LOGGER.debug("getCurrentServerBaseDirectory: serverData={}, serverData.ip={}",
                serverData, serverData != null ? serverData.ip : "N/A");

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = getWorldMapDir(gameDir);

        // 使用 IP 命名目录（Xaero 按 IP 读取）
        String serverIP = getCurrentServerIP();
        if (serverIP == null || serverIP.isEmpty()) {
            return null;
        }

        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        Path dimDir = serverDir.resolve("null");

        if (!dimDir.toFile().exists()) {
            try {
                if (worldMapDir.toFile().exists() && worldMapDir.toFile().isDirectory()) {
                    try (var stream = Files.list(worldMapDir)) {
                        stream.filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                            .filter(Files::isDirectory)
                            .forEach(p -> {
                                Path candidateDim = p.resolve("null");
                                if (candidateDim.toFile().exists()) {
                                    LOGGER.debug("Found existing Xaero directory: {}", candidateDim);
                                }
                            });
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to scan world-map directory: {}", e.getMessage());
            }

            if ("Singleplayer".equals(serverIP) || "LAN".equals(serverIP)) {
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
     * 获取当前连接服务器的服务器目录（Multiplayer_&lt;serverIP&gt;）。
     * 使用 IP 命名，与 Xaero 地图模组保持一致。
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

        // 使用 IP 命名目录（Xaero 按 IP 读取）
        String serverIP = getCurrentServerIP();
        if (serverIP == null || serverIP.isEmpty()) {
            return null;
        }

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = getWorldMapDir(gameDir);
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        LOGGER.debug("Server directory: {}", serverDir);
        return serverDir;
    }

    /**
     * 写入服务端接收的地图数据到正确位置。
     * 委托给 {@link XaeroMapDataHandler#writeMapData}，同时获取 MC 上下文。
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

        // 使用 IP 命名目录（Xaero 按 IP 读取）
        String serverIP = getCurrentServerIP();
        if (serverIP == null || serverIP.isEmpty()) {
            return null;
        }

        LOGGER.info("Using server worldId: {}, serverIP: {}", serverWorldId, serverIP);

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = getWorldMapDir(gameDir);
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);

        return XaeroMapDataHandler.writeMapData(chunks, serverDir, serverWorldId);
    }

    /**
     * 写入单个区块数据并返回写入结果。
     *
     * @param chunk 区块数据
     * @param worldId 服务端 worldId
     * @return 写入结果，失败或无法获取服务器目录时返回 null
     */
    public static XaeroMapDataHandler.RegionWriteResult writeChunkDataResult(ChunkMapData chunk, int worldId) {
        Path serverDir = getCurrentServerDirectory();
        if (serverDir == null) {
            LOGGER.warn("无法获取服务器目录");
            return null;
        }
        return XaeroMapDataHandler.writeChunkData(chunk, serverDir, worldId);
    }

    /**
     * 写入单个区块数据并返回 mw 目录路径。
     * 委托给 {@link XaeroMapDataHandler#writeChunkData}，同时获取 MC 上下文。
     *
     * @param chunk 区块数据
     * @param worldId 服务端 worldId
     * @return mw 目录路径，如果获取服务器目录失败返回 null
     */
    public static Path writeChunkDataAndGetMwDir(ChunkMapData chunk, int worldId) {
        XaeroMapDataHandler.RegionWriteResult result = writeChunkDataResult(chunk, worldId);
        return result != null ? result.mwDir() : null;
    }
}
