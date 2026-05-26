package com.mapsyncer.platform.impl;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.BlockProperties;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformType;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.server.BlockPropertyResolver;
import com.mapsyncer.util.BlockColorMapper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NeoForge 1.21 平台实现
 *
 * 实现 Platform 接口，适配 NeoForge 1.21.x 的 API。
 */
public class NeoForgePlatform implements Platform {

    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgePlatform.class);

    @Override
    public PlatformType getType() {
        return PlatformType.NEO_FORGE;
    }

    @Override
    public String getMinecraftVersion() {
        return "1.21.x";
    }

    @Override
    public int getMajorVersion() {
        return 21;
    }

    @Override
    public String getPlatformName() {
        return "NeoForge 1.21";
    }

    // ===== 方块属性 =====

    @Override
    public BlockProperties getBlockProperties(String blockName) {
        try {
            ResourceLocation loc = ResourceLocation.parse(blockName);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(loc);

            if (blockOpt.isEmpty()) {
                LOGGER.debug("Block not found: {}, using pattern color", blockName);
                return new BlockProperties(
                    false, false, false, false, false, false, false, false,
                    false, false, 15, 0, false, getPatternColor(blockName)
                );
            }

            Block block = blockOpt.get();
            BlockState state = block.defaultBlockState();

            // 使用 BlockPropertyResolver 获取属性
            BlockPropertyResolver.BlockProperties props = BlockPropertyResolver.getProperties(blockName);

            return new BlockProperties(
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

    // ===== 文件路径 =====

    @Override
    public Path getServerMapCacheDir() {
        // 服务端缓存目录由 ConversionOrchestrator 管理
        return Path.of("server_map_cache");
    }

    @Override
    public Path getClientXaeroWorldMapDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameDirectory == null) {
            return null;
        }
        return mc.gameDirectory.toPath().resolve("xaero").resolve("world-map");
    }

    @Override
    public String getCurrentServerDirectoryName() {
        // 由 XaeroMapIntegrator 处理
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

        int bracketStart = blockStateString.indexOf '[';
        int bracketEnd = blockStateString.lastIndexOf ']';

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
        // 由 XaeroMapIntegrator 处理
        LOGGER.debug("Recording {} updated regions", regions.size());
    }
}