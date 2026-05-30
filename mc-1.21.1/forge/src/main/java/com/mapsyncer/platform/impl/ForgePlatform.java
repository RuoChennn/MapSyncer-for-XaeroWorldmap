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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Forge 1.21.1 骞冲彴瀹炵幇
 *
 * 瀹炵幇 Platform 鎺ュ彛锛岄€傞厤 Forge 52.x 鐨?API銆?
 * 涓昏宸紓鐐癸細
 * - Forge 1.21.1 浣跨敤 Java 21
 * - SimpleNetworkWrapper 缃戠粶锛堥潪 StreamCodec锛?
 * - BuiltInRegistries 娉ㄥ唽琛?
 */
public class ForgePlatform implements Platform {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForgePlatform.class);

    // 缂撳瓨鏂瑰潡灞炴€ф煡璇㈢粨鏋?
    private static final Map<String, BlockProperties> blockPropertiesCache = new HashMap<>();

    @Override
    public PlatformType getType() {
        return PlatformType.FORGE_MODERN;
    }

    @Override
    public String getMinecraftVersion() {
        return "1.21.1";
    }

    @Override
    public int getMajorVersion() {
        return 21;
    }

    @Override
    public String getPlatformName() {
        return "Forge 1.21.1";
    }

    @Override
    public boolean isClientEnvironment() {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT;
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
            // Forge 1.21: BuiltInRegistries 鍙敤
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

            // 浣跨敤 BlockPropertyResolver 鑾峰彇灞炴€?
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
    public int getClientHashThreads() {
        return ModConfig.CLIENT.getHashThreads();
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
        ModConfig.SERVER.incrementalUpdateMode.set(mode);
    }

    @Override
    public void setIncrementalUpdateIntervalTicks(int interval) {
        ModConfig.SERVER.incrementalUpdateIntervalTicks.set(interval);
    }

    @Override
    public void setScheduledUpdateHour(int hour) {
        ModConfig.SERVER.scheduledUpdateHour.set(hour);
    }

    @Override
    public void setScheduledUpdateMinute(int minute) {
        ModConfig.SERVER.scheduledUpdateMinute.set(minute);
    }

    @Override
    public void saveConfig() {
        // Forge 鑷姩绠＄悊閰嶇疆鎸佷箙鍖栵紝鏃犻渶鎵嬪姩淇濆瓨
    }

    @Override
    public java.util.List<String> getDimensionConfigs() {
        return new java.util.ArrayList<>(ModConfig.SERVER.dimensionConfigs.get());
    }

    @Override
    public void setDimensionConfigs(java.util.List<String> configs) {
        ModConfig.SERVER.dimensionConfigs.set(configs);
    }

    @Override
    public java.util.List<DimensionScanConfig> parseDimensionConfigs() {
        return ModConfig.SERVER.parseDimensionConfigs();
    }

    @Override
    public DimensionScanConfig getConfigForDimension(String dimensionPath) {
        return ModConfig.SERVER.getConfigForDimension(dimensionPath);
    }

    // ===== 鏂囦欢璺緞 =====

    @Override
    public Path getServerMapCacheDir() {
        return Path.of("server_map_cache");
    }

    @Override
    public Path getClientXaeroWorldMapDir() {
        try {
            Path serverDir = com.mapsyncer.client.XaeroMapIntegrator.getCurrentServerDirectory();
            if (serverDir != null) {
                return serverDir;
            }

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