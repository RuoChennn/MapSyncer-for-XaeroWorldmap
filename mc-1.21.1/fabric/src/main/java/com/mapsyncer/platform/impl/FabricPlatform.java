package com.mapsyncer.platform.impl;

import com.mapsyncer.config.ConcurrentRegionsConfig;
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
 * Fabric 1.21.1 平台实现
 */
public class FabricPlatform implements Platform {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricPlatform.class);

    private MinecraftServer server;

    private static final Map<String, BlockProperties> blockPropertiesCache = new HashMap<>();

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public PlatformType getType() {
        return PlatformType.FABRIC;
    }

    @Override
    public String getServerCommandPrefix() {
        return "mapsyncerserver";
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
        return "Fabric 1.21";
    }

    @Override
    public boolean isClientEnvironment() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
    }

    // ===== 方块属性 =====

    @Override
    public BlockProperties getBlockProperties(String blockName) {
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

    // ===== 世界信息 =====

    @Override
    public int getDefaultMinBuildHeight() {
        return -64;
    }

    @Override
    public int getDefaultMaxBuildHeight() {
        return 320;
    }

    // ===== 维度信息 =====

    @Override
    public String getXaeroDimensionPath(String dimensionId) {
        return DimensionPathMapping.getInstance().toXaeroDimension(dimensionId);
    }

    @Override
    public DimensionTypeInfo getDimensionTypeInfo(String dimensionId) {
        return DimensionTypeInfo.fromDimensionId(dimensionId);
    }

    // ===== 配置系统 =====

    @Override
    public int getSyncSpeedLimitKBps() {
        return ModConfig.SERVER().getSyncSpeedLimitKBps();
    }

    @Override
    public int getMaxSyncPacketSize() {
        return ModConfig.SERVER().getMaxSyncPacketSize();
    }

    @Override
    public int getMaxConcurrentRegions() {
        return ConcurrentRegionsConfig.resolve(ModConfig.SERVER().getMaxConcurrentRegions());
    }

    @Override
    public boolean isDebugLoggingEnabled() {
        return ModConfig.SERVER().getEnableDebugLogging();
    }

    @Override
    public int getClientHashThreads() {
        return ModConfig.CLIENT().getHashThreads();
    }

    @Override
    public int getMapRegionLoadIntervalTicks() {
        return ModConfig.CLIENT().getMapRegionLoadIntervalTicks();
    }

    @Override
    public boolean isClientAutoSyncEnabled() {
        return ModConfig.CLIENT().isAutoSyncEnabled();
    }

    @Override
    public void setClientAutoSyncEnabled(boolean enabled) {
        ModConfig.CLIENT().setAutoSyncEnabled(enabled);
        ModConfig.CLIENT().save();
    }

    @Override
    public UpdateMode getIncrementalUpdateMode() {
        return ModConfig.SERVER().getIncrementalUpdateMode();
    }

    @Override
    public int getIncrementalUpdateIntervalTicks() {
        return ModConfig.SERVER().getIncrementalUpdateIntervalTicks();
    }

    @Override
    public int getScheduledUpdateHour() {
        return ModConfig.SERVER().getScheduledUpdateHour();
    }

    @Override
    public int getScheduledUpdateMinute() {
        return ModConfig.SERVER().getScheduledUpdateMinute();
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
    public void reloadConfig() {
        ModConfig.SERVER().reload();
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

    // ===== 文件路径 =====

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
            if (isClientEnvironment()) {
                Path serverDir = com.mapsyncer.client.XaeroMapIntegrator.getCurrentServerDirectory();
                if (serverDir != null) {
                    return serverDir;
                }
            }

            Path gameDir = Path.of(System.getProperty("user.dir"));
            return com.mapsyncer.util.XaeroPathResolver.getWorldMapDir(gameDir);
        } catch (Exception e) {
            LOGGER.debug("Failed to get Xaero world map dir: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String getCurrentServerDirectoryName() {
        try {
            if (isClientEnvironment()) {
                Path serverDir = com.mapsyncer.client.XaeroMapIntegrator.getCurrentServerDirectory();
                if (serverDir != null) {
                    return serverDir.getFileName().toString();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get server directory name: {}", e.getMessage());
        }
        return "Multiplayer_Server";
    }

    // ===== 日志 =====

    @Override
    public Logger getLogger() {
        return LOGGER;
    }

    // ===== 工具方法 =====

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

    @Override
    public void clearBlockPropertiesCache() {
        blockPropertiesCache.clear();
    }

    public static int getCacheSize() {
        return blockPropertiesCache.size();
    }
}
