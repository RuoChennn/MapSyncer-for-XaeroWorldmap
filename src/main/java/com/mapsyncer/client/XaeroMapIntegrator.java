package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
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

public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);

    // Flag to control whether chunk updates are enabled during sync
    private static volatile boolean chunkUpdatesDisabled = false;

    // Store updated regions for selective reset
    private static volatile Set<RegionCoord> updatedRegions = new HashSet<>();

    /**
     * Get the set of regions that were updated during sync.
     * @return A copy of the updated regions set
     */
    public static Set<RegionCoord> getUpdatedRegions() {
        return new HashSet<>(updatedRegions);
    }

    /**
     * Region coordinate record for tracking which regions were updated.
     * 包含 caveLayer 信息，用于区分地表层和洞穴层。
     */
    public record RegionCoord(int x, int z, int caveLayer) {
        // 兼容旧代码的构造器（默认地表层）
        public RegionCoord(int x, int z) {
            this(x, z, Integer.MAX_VALUE);
        }

        /**
         * 判断是否为地表层
         */
        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    /**
     * Disable Xaero's chunk update processing during sync.
     * This prevents Xaero from writing new chunk data while we're replacing files.
     */
    public static void disableChunkUpdates() {
        // Skip if already disabled (avoid duplicate messages)
        if (chunkUpdatesDisabled) {
            return;
        }

        chunkUpdatesDisabled = true;
        LOGGER.info("Chunk updates disabled for sync");

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.translatable("mapsyncer.chunk.paused"), false);
            }

            // Try to pause Xaero's MapWriter thread via reflection
            pauseMapWriter();
        } catch (Exception e) {
            LOGGER.warn("Could not pause Xaero chunk processing: {}", e.getMessage());
        }
    }

    /**
     * Enable Xaero's chunk update processing after sync completes.
     */
    public static void enableChunkUpdates() {
        chunkUpdatesDisabled = false;
        LOGGER.info("Chunk updates re-enabled");

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.translatable("mapsyncer.chunk.resumed"), false);
            }

            // Resume Xaero's MapWriter thread
            resumeMapWriter();
        } catch (Exception e) {
            LOGGER.warn("Could not resume Xaero chunk processing: {}", e.getMessage());
        }
    }

    /**
     * Check if chunk updates are currently disabled.
     */
    public static boolean isChunkUpdatesDisabled() {
        return chunkUpdatesDisabled;
    }

    /**
     * Record regions that were updated during sync.
     * These will be selectively reset during reload.
     * 包含 caveLayer 信息，用于区分地表层和洞穴层。
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
     * Calculate view distance range in region coordinates.
     * @return Set of region coordinates within view distance from player
     */
    public static Set<RegionCoord> getViewDistanceRegions() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return new HashSet<>();
        }

        // Get player position
        int playerChunkX = player.getBlockX() >> 4;  // 16 blocks per chunk
        int playerChunkZ = player.getBlockZ() >> 4;

        // Get view distance (render distance)
        int viewDistance = mc.options.renderDistance().get();
        // Convert to region radius: 32 chunks per region, add buffer
        int regionRadius = (viewDistance >> 5) + 2;  // +2 for safety margin

        // Player's current region
        int playerRegionX = playerChunkX >> 5;  // 32 chunks per region
        int playerRegionZ = playerChunkZ >> 5;

        Set<RegionCoord> viewRegions = new HashSet<>();

        // Add all regions within view distance
        for (int rx = playerRegionX - regionRadius; rx <= playerRegionX + regionRadius; rx++) {
            for (int rz = playerRegionZ - regionRadius; rz <= playerRegionZ + regionRadius; rz++) {
                viewRegions.add(new RegionCoord(rx, rz));
            }
        }

        LOGGER.debug("View distance regions: player at ({}, {}), radius {}, total {} regions",
                playerRegionX, playerRegionZ, regionRadius, viewRegions.size());

        return viewRegions;
    }

    /**
     * Selectively reset loadState only for regions that need it:
     * - Regions that were updated during sync
     * - Regions within player's view distance
     * - Player's current region
     */
    public static int selectiveResetRegionLoadStates() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            LOGGER.warn("No player for selective reset");
            return 0;
        }

        // Calculate player's current region
        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int playerRegionX = playerChunkX >> 5;
        int playerRegionZ = playerChunkZ >> 5;
        RegionCoord playerRegion = new RegionCoord(playerRegionX, playerRegionZ);

        // Get regions within view distance
        Set<RegionCoord> viewRegions = getViewDistanceRegions();

        // Combine: regions that need reset = updated regions AND view regions
        Set<RegionCoord> regionsToReset = new HashSet<>();

        // Always add player's current region
        regionsToReset.add(playerRegion);

        // Add updated regions that are in view distance
        for (RegionCoord updated : updatedRegions) {
            if (viewRegions.contains(updated)) {
                regionsToReset.add(updated);
            }
        }

        // Also add nearby regions in view distance for smoother reload
        // (even if not updated, they may need refresh for continuity)
        int nearbyLimit = 10;  // Limit to avoid too many resets
        for (RegionCoord viewRegion : viewRegions) {
            if (regionsToReset.size() >= nearbyLimit) break;
            // Add regions close to player position
            if (Math.abs(viewRegion.x() - playerRegionX) <= 1 &&
                Math.abs(viewRegion.z() - playerRegionZ) <= 1) {
                regionsToReset.add(viewRegion);
            }
        }

        LOGGER.info("Selective reset: {} updated regions, {} view regions, {} to reset",
                updatedRegions.size(), viewRegions.size(), regionsToReset.size());

        // Perform the reset only for selected regions
        return resetSpecificRegionLoadStates(regionsToReset);
    }

    /**
     * Reset loadState for specific regions only.
     * Public method for external callers.
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
     * Traverse regions and selectively reset only those in the target set.
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
                        loadStateField.setByte(region, (byte) 0);
                        count++;

                        // Reset hasHadTerrain
                        try {
                            Field hasHadTerrainField = regionClass.getDeclaredField("hasHadTerrain");
                            hasHadTerrainField.setAccessible(true);
                            hasHadTerrainField.setBoolean(region, false);
                        } catch (NoSuchFieldException ignored) {}

                        LOGGER.debug("Reset region ({}, {}) loadState", rx, rz);
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
     * Force reset loadState of all loaded regions in current dimension.
     * This ensures regions will be fully reloaded from new files instead of just refreshed.
     * @deprecated Use selectiveResetRegionLoadStates() for better performance
     */
    @Deprecated
    public static void forceResetRegionLoadStates() {
        try {
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Method getCurrentSession = worldMapSessionClass.getMethod("getCurrentSession");
            Object session = getCurrentSession.invoke(null);

            if (session == null) {
                LOGGER.warn("Could not get WorldMapSession for region reset");
                return;
            }

            Method getMapProcessor = worldMapSessionClass.getMethod("getMapProcessor");
            Object mapProcessor = getMapProcessor.invoke(session);

            if (mapProcessor == null) {
                LOGGER.warn("Could not get MapProcessor for region reset");
                return;
            }

            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Method getMapWorld = mapProcessorClass.getMethod("getMapWorld");
            Object mapWorld = getMapWorld.invoke(mapProcessor);

            if (mapWorld == null) {
                LOGGER.warn("Could not get MapWorld for region reset");
                return;
            }

            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            Method getCurrentDimension = mapWorldClass.getMethod("getCurrentDimension");
            Object mapDimension = getCurrentDimension.invoke(mapWorld);

            if (mapDimension == null) {
                LOGGER.warn("Could not get current dimension for region reset");
                return;
            }

            // Get the LayeredRegionManager which holds all regions
            Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            Method getLayeredMapRegions = mapDimensionClass.getMethod("getLayeredMapRegions");
            Object layeredRegionManager = getLayeredMapRegions.invoke(mapDimension);

            if (layeredRegionManager == null) {
                LOGGER.warn("Could not get LayeredRegionManager");
                return;
            }

            // Get the surface layer (Integer.MAX_VALUE for surface)
            Class<?> layeredRegionManagerClass = Class.forName("xaero.map.region.LayeredRegionManager");
            Method getLayer = layeredRegionManagerClass.getMethod("getLayer", int.class);
            Object mapLayer = getLayer.invoke(layeredRegionManager, Integer.MAX_VALUE);

            if (mapLayer == null) {
                LOGGER.warn("Could not get surface MapLayer");
                return;
            }

            // Get MapRegions (LeveledRegionManager) from MapLayer
            Class<?> mapLayerClass = Class.forName("xaero.map.region.MapLayer");
            Method getMapRegions = mapLayerClass.getMethod("getMapRegions");
            Object leveledRegionManager = getMapRegions.invoke(mapLayer);

            if (leveledRegionManager == null) {
                LOGGER.warn("Could not get LeveledRegionManager from MapLayer");
                return;
            }

            // Access the internal regionTextureMap via reflection
            Class<?> leveledRegionManagerClass = Class.forName("xaero.map.region.LeveledRegionManager");
            Field regionTextureMapField = leveledRegionManagerClass.getDeclaredField("regionTextureMap");
            regionTextureMapField.setAccessible(true);
            Object regionTextureMap = regionTextureMapField.get(leveledRegionManager);

            LOGGER.info("regionTextureMap type: {}, size: {}",
                regionTextureMap != null ? regionTextureMap.getClass().getName() : "null",
                regionTextureMap != null ? ((java.util.Map<?, ?>) regionTextureMap).size() : 0);

            if (regionTextureMap != null && regionTextureMap instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) regionTextureMap;
                int resetCount = 0;

                for (Object columnEntry : map.values()) {
                    if (columnEntry instanceof java.util.Map) {
                        java.util.Map<?, ?> column = (java.util.Map<?, ?>) columnEntry;
                        LOGGER.debug("Column size: {}", column.size());
                        for (Object regionEntry : column.values()) {
                            LOGGER.debug("Region entry type: {}", regionEntry.getClass().getName());
                            // This is a BranchLeveledRegion, need to traverse to find leaf MapRegions
                            resetCount += resetLeafRegionsLoadState(regionEntry);
                        }
                    }
                }

                LOGGER.info("Reset loadState for {} regions", resetCount);
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to reset region loadStates: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Recursively traverse branch regions to find leaf MapRegions and reset their loadState.
     */
    private static int resetLeafRegionsLoadState(Object region) {
        int count = 0;
        try {
            Class<?> regionClass = region.getClass();

            // Check if this is a MapRegion (leaf, level 0)
            if (regionClass.getName().equals("xaero.map.region.MapRegion")) {
                Field loadStateField = regionClass.getDeclaredField("loadState");
                loadStateField.setAccessible(true);
                byte currentLoadState = loadStateField.getByte(region);

                if (currentLoadState == 2) { // Only reset loaded regions
                    loadStateField.setByte(region, (byte) 0); // Reset to unloaded
                    count++;

                    // Also reset hasHadTerrain to force fresh load
                    try {
                        Field hasHadTerrainField = regionClass.getDeclaredField("hasHadTerrain");
                        hasHadTerrainField.setAccessible(true);
                        hasHadTerrainField.setBoolean(region, false);
                    } catch (NoSuchFieldException e) {
                        // Field might not exist, ignore
                    }

                    LOGGER.debug("Reset region loadState from 2 to 0");
                }
            } else if (regionClass.getName().equals("xaero.map.region.BranchLeveledRegion")) {
                // This is a BranchLeveledRegion, traverse its children
                // children is a 2x2 array: LeveledRegion<?>[][] children
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
                                    count += resetLeafRegionsLoadState(child);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error resetting region: {}", e.getMessage());
        }
        return count;
    }

    private static void pauseMapWriter() {
        try {
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Method getCurrentSession = worldMapSessionClass.getMethod("getCurrentSession");
            Object session = getCurrentSession.invoke(null);

            if (session == null) return;

            Method getMapProcessor = worldMapSessionClass.getMethod("getMapProcessor");
            Object mapProcessor = getMapProcessor.invoke(session);

            if (mapProcessor == null) return;

            // Try to get and pause the MapWriter
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Method getMapWriter = mapProcessorClass.getMethod("getMapWriter");
            Object mapWriter = getMapWriter.invoke(mapProcessor);

            if (mapWriter != null) {
                // Try to set paused flag or interrupt the thread
                Class<?> mapWriterClass = Class.forName("xaero.map.writer.MapWriter");
                try {
                    Field pausedField = mapWriterClass.getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    pausedField.setBoolean(mapWriter, true);
                    LOGGER.info("Paused Xaero MapWriter via reflection");
                } catch (NoSuchFieldException e) {
                    // Alternative: try to get the thread and interrupt
                    try {
                        Method getThread = mapWriterClass.getMethod("getThread");
                        Object thread = getThread.invoke(mapWriter);
                        if (thread instanceof Thread) {
                            ((Thread) thread).interrupt();
                            LOGGER.info("Interrupted Xaero MapWriter thread");
                        }
                    } catch (Exception ex) {
                        LOGGER.debug("Could not interrupt MapWriter thread: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to pause MapWriter: {}", e.getMessage());
        }
    }

    private static void resumeMapWriter() {
        try {
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Method getCurrentSession = worldMapSessionClass.getMethod("getCurrentSession");
            Object session = getCurrentSession.invoke(null);

            if (session == null) return;

            Method getMapProcessor = worldMapSessionClass.getMethod("getMapProcessor");
            Object mapProcessor = getMapProcessor.invoke(session);

            if (mapProcessor == null) return;

            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Method getMapWriter = mapProcessorClass.getMethod("getMapWriter");
            Object mapWriter = getMapWriter.invoke(mapProcessor);

            if (mapWriter != null) {
                Class<?> mapWriterClass = Class.forName("xaero.map.writer.MapWriter");
                try {
                    Field pausedField = mapWriterClass.getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    pausedField.setBoolean(mapWriter, false);
                    LOGGER.info("Resumed Xaero MapWriter via reflection");
                } catch (NoSuchFieldException e) {
                    LOGGER.debug("No paused field found in MapWriter");
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to resume MapWriter: {}", e.getMessage());
        }
    }

    /**
     * Get the current Xaero WorldMap directory for the connected server.
     * Path structure: xaero/world-map/Multiplayer_<server>/null/mw$<worldId>/
     */
    public static Path getCurrentMapDirectory() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return null;
        }

        ServerData serverData = connection.getServerData();
        if (serverData == null) {
            return null;
        }

        // Get server address
        String serverIP = serverData.ip;
        if (serverIP == null || serverIP.isEmpty()) {
            serverIP = "Unknown";
        }

        // Clean up server IP (remove port, brackets, etc.)
        int portDivider = serverIP.lastIndexOf(":");
        if (portDivider > 0 && serverIP.indexOf(":") != serverIP.lastIndexOf(":")) {
            // IPv6 address with port
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

        // Get world ID from the level
        int worldId = 0;
        if (mc.level != null) {
            worldId = mc.level.getLevelData().hashCode();
        }

        // Build path: xaero/world-map/Multiplayer_<server>/null/mw$<worldId>
        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = gameDir.resolve("xaero").resolve("world-map");
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        Path dimDir = serverDir.resolve("null");
        Path mwDir = dimDir.resolve("mw$" + worldId);

        LOGGER.debug("Map directory: {}", mwDir);
        return mwDir;
    }

    /**
     * Get the base directory for the current server (null directory).
     * Path structure: xaero/world-map/Multiplayer_<server>/null/
     *
     * 支持多种游戏模式：
     * - 多人游戏：Multiplayer_<serverIP>/
     * - 单机游戏/局域网：单机游戏使用 "Singleplayer" 目录名，局域网使用 LAN server 特殊处理
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
     * Write map data received from server to the correct location.
     * Uses the server-provided worldId to ensure correct directory path.
     * Returns the mw directory path for further processing.
     * Also saves server timestamps to local cache for future sync comparison.
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
            String hash = computeHash(chunk.data);
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
     * Build relative path for timestamp cache in server format.
     *
     * 格式（匹配服务端 GenerationCache 格式）：
     * - 地表：xaeroDim/regionX_regionZ（如 twilightforest$twilight_forest/0_0）
     * - 洞穴：xaeroDim/caves/layer/regionX_regionZ
     *
     * 注意：chunk.dimension 已经是 Xaero 格式，直接使用即可，无需转换。
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
     * Compute CRC32 hash of data.
     */
    private static String computeHash(byte[] data) {
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        crc32.update(data);
        return String.format("%08x", crc32.getValue());
    }

    /**
     * Write map data received from server to the correct location.
     * Uses the server-provided worldId to ensure correct directory path.
     */
    public static void writeMapData(List<ChunkMapData> chunks, int serverWorldId) {
        writeMapDataAndReturnDir(chunks, serverWorldId);
    }

    /**
     * Write map data received from server to the correct location.
     * @deprecated Use writeMapData(chunks, serverWorldId) instead.
     */
    @Deprecated
    public static void writeMapData(List<ChunkMapData> chunks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            int worldId = mc.level.getLevelData().hashCode();
            writeMapData(chunks, worldId);
        } else {
            writeMapData(chunks, 0);
        }
    }

    /**
     * Convert server dimension name to Xaero's directory name.
     * Xaero uses "null" for the overworld dimension.
     *
     * 支持 caves/<layer> 目录结构：
     * - 地表：Multiplayer_<server>/<xaero_dimension>/mw$<worldId>/<regionX_regionZ>.zip
     * - 洞穴：Multiplayer_<server>/<xaero_dimension>/mw$<worldId>/caves/<layer>/<regionX_regionZ>.zip
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

    private static void writeChunkData(ChunkMapData chunk, Path serverDir, int worldId) {
        writeChunkDataAndGetDir(chunk, serverDir, worldId);
    }

    public static void reloadMap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.execute(() -> {
                LOGGER.info("Map reload triggered");
            });
        }
    }
}