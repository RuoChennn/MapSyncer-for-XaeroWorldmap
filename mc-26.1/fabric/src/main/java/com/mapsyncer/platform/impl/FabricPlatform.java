package com.mapsyncer.platform.impl;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.BlockProperties;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformType;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.server.BlockPropertyResolver;
import com.mapsyncer.util.BlockColorMapper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fabric 26.1.x 骞冲彴瀹炵幇
 *
 * 瀹炵幇 Platform 鎺ュ彛锛岄€傞厤 Fabric 26.1.x 鐨?API銆?
 * 鏀寔 Minecraft 26.1, 26.1.1, 26.1.2 绛夌増鏈€?
 */
public class FabricPlatform implements Platform {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricPlatform.class);

    private MinecraftServer server;

    // 缂撳瓨鏂瑰潡灞炴€ф煡璇㈢粨鏋?
    private static final Map<String, BlockProperties> blockPropertiesCache = new HashMap<>();

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public PlatformType getType() {
        return PlatformType.FABRIC;
    }

    @Override
    public String getMinecraftVersion() {
        return "26.1";
    }

    @Override
    public int getMajorVersion() {
        return 26;
    }

    @Override
    public String getPlatformName() {
        return "Fabric 26.x";
    }

    @Override
    public boolean isClientEnvironment() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
    }

    // ===== 鏂瑰潡灞炴€?=====

    @Override
    public BlockProperties getBlockProperties(String blockName) {
        // 妫€鏌ョ紦瀛?
        BlockProperties cached = blockPropertiesCache.get(blockName);
        if (cached != null) {
            return cached;
        }

        try {
            ResourceLocation loc = ResourceLocation.parse(blockName);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(loc);

            if (blockOpt.isEmpty()) {
                LOGGER.debug("Block not found: {}, using pattern color", blockName);
                BlockProperties fallback = new BlockProperties(
                    false, false, false, false, false, false, false, false,
                    false, false, 15, 0, false, getPatternColor(blockName)
                );
                blockPropertiesCache.put(blockName, fallback);
                return fallback;
            }

            Block block = blockOpt.get();
            BlockState state = block.defaultBlockState();

            // 浣跨敤 BlockPropertyResolver 鑾峰彇灞炴€э紙鏈嶅姟绔彲鐢級
            BlockPropertyResolver.BlockProperties props = BlockPropertyResolver.getProperties(blockName);

            BlockProperties result = new BlockProperties(
                props.isAir(),
                props.isWater(),
                props.isLava(),
                props.isFluid(),
                props.isTransparent(),
                props.isInvisible(),
                props.isFlower(),
                props.isPlant(),
                props.isGrassBlock(),
                props.isGlowing(),
                props.lightBlock(),
                props.lightEmission(),
                props.canBeWaterlogged(),
                BlockColorMapper.getBlockColor(state)
            );

            blockPropertiesCache.put(blockName, result);
            return result;

        } catch (Exception e) {
            LOGGER.warn("Failed to get block properties for {}: {}", blockName, e.getMessage());
            return BlockProperties.EMPTY;
        }
    }

    @Override
    public int getPatternColor(String blockName) {
        return BlockColorMapper.getBlockColorByName(blockName);
    }

    // ===== 涓栫晫淇℃伅 =====

    @Override
    public int getDefaultMinBuildHeight() {
        return -64;
    }

    @Override
    public int getDefaultMaxBuildHeight() {
        return 320;
    }

    // ===== 缁村害淇℃伅 =====

    @Override
    public String getXaeroDimensionPath(String dimensionId) {
        return DimensionPathMapping.getInstance().toXaeroDimension(dimensionId);
    }

    @Override
    public DimensionTypeInfo getDimensionTypeInfo(String dimensionId) {
        return DimensionTypeInfo.fromDimensionId(dimensionId);
    }

    // ===== 閰嶇疆绯荤粺 =====

    @Override
    public int getSyncSpeedLimitKBps() {
        return ModConfig.SERVER.syncSpeedLimitKBps.get();
    }

    @Override
    public int getMaxSyncPacketSize() {
        return ModConfig.SERVER.maxSyncPacketSize.get();
    }

    @Override
    public int getMaxConcurrentRegions() {
        return ModConfig.SERVER.maxConcurrentRegions.get();
    }

    @Override
    public boolean isDebugLoggingEnabled() {
        return ModConfig.SERVER.enableDebugLogging.get();
    }

    @Override
    public UpdateMode getIncrementalUpdateMode() {
        return ModConfig.SERVER.incrementalUpdateMode.get();
    }

    @Override
    public int getIncrementalUpdateIntervalTicks() {
        return ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
    }

    @Override
    public int getScheduledUpdateHour() {
        return ModConfig.SERVER.scheduledUpdateHour.get();
    }

    @Override
    public int getScheduledUpdateMinute() {
        return ModConfig.SERVER.scheduledUpdateMinute.get();
    }

    @Override
    public void setIncrementalUpdateMode(UpdateMode mode) {
        ModConfig.SERVER().setIncrementalUpdateMode(mode);
    }

    @Override
    public void setIncrementalUpdateIntervalTicks(int interval) {
        ModConfig.SERVER().setIncrementalUpdateIntervalTicks(interval);
    }

    @Override
    public void setScheduledUpdateHour(int hour) {
        ModConfig.SERVER().setScheduledUpdateHour(hour);
    }

    @Override
    public void setScheduledUpdateMinute(int minute) {
        ModConfig.SERVER().setScheduledUpdateMinute(minute);
    }

    @Override
    public void saveConfig() {
        ModConfig.SERVER().save();
    }

    @Override
    public java.util.List<String> getDimensionConfigs() {
        return ModConfig.SERVER().getDimensionConfigs();
    }

    @Override
    public void setDimensionConfigs(java.util.List<String> configs) {
        ModConfig.SERVER().setDimensionConfigs(configs);
    }

    @Override
    public java.util.List<DimensionScanConfig> parseDimensionConfigs() {
        return ModConfig.SERVER().parseDimensionConfigs();
    }

    @Override
    public DimensionScanConfig getConfigForDimension(String dimensionPath) {
        return ModConfig.SERVER().getConfigForDimension(dimensionPath);
    }

    // ===== 鏂囦欢璺緞 =====

    @Override
    public Path getServerMapCacheDir() {
        if (server != null) {
            Path worldPath = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
            return worldPath.resolve("server_map_cache");
        }
        return Path.of("server_map_cache");
    }

    @Override
    public Path getClientXaeroWorldMapDir() {
        try {
            // 浣跨敤 XaeroMapIntegrator 鑾峰彇褰撳墠鏈嶅姟鍣ㄧ洰褰?
            Path serverDir = com.mapsyncer.client.XaeroMapIntegrator.getCurrentServerDirectory();
            if (serverDir != null) {
                return serverDir;
            }

            // 鍥為€€锛氳繑鍥為粯璁?Xaero 鐩綍
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameDirectory != null) {
                return mc.gameDirectory.toPath().resolve("xaero").resolve("world-map");
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get Xaero world map dir: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String getCurrentServerDirectoryName() {
        try {
            Path serverDir = com.mapsyncer.client.XaeroMapIntegrator.getCurrentServerDirectory();
            if (serverDir != null) {
                return serverDir.getFileName().toString();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get server directory name: {}", e.getMessage());
        }
        return "Multiplayer_Server";
    }

    // ===== 鏃ュ織 =====

    @Override
    public Logger getLogger() {
        return LOGGER;
    }

    // ===== 宸ュ叿鏂规硶 =====

    @Override
    public boolean matchesBlockPattern(String blockName, String pattern) {
        String name = blockName.toLowerCase();
        return name.endsWith(pattern.toLowerCase()) || name.contains(pattern.toLowerCase());
    }

    @Override
    public Map<String, String> parseBlockProperties(String blockStateString) {
        Map<String, String> props = new HashMap<>();

        int bracketStart = blockStateString.indexOf('[');
        int bracketEnd = blockStateString.lastIndexOf(']');

        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            String propsStr = blockStateString.substring(bracketStart + 1, bracketEnd);
            String[] pairs = propsStr.split(",");

            for (String pair : pairs) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    props.put(kv[0].trim(), kv[1].trim());
                }
            }
        }

        return props;
    }

    @Override
    public void recordUpdatedRegions(Set<RegionCoord> regions) {
        try {
            // 杞崲 Platform.RegionCoord 鍒?XaeroMapDataHandler.RegionCoord
            Set<com.mapsyncer.client.XaeroMapDataHandler.RegionCoord> xaeroRegions = new HashSet<>();
            for (RegionCoord coord : regions) {
                xaeroRegions.add(new com.mapsyncer.client.XaeroMapDataHandler.RegionCoord(
                    coord.x(), coord.z(), coord.caveLayer()
                ));
            }
            com.mapsyncer.client.XaeroMapDataHandler.recordUpdatedRegionCoords(xaeroRegions);
            LOGGER.debug("Recorded {} updated regions via XaeroMapIntegrator", regions.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to record updated regions: {}", e.getMessage());
        }
    }

    /**
     * 娓呴櫎鏂瑰潡灞炴€х紦瀛?
     */
    @Override
    public void clearBlockPropertiesCache() {
        blockPropertiesCache.clear();
    }

    /**
     * 鑾峰彇缂撳瓨澶у皬
     */
    public static int getCacheSize() {
        return blockPropertiesCache.size();
    }
}