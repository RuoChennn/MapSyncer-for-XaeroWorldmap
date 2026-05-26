package com.mapsyncer.platform.impl;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.BlockProperties;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformType;
import com.mapsyncer.platform.UpdateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * NeoForge 26.x 平台实现（预留）
 *
 * 预估与 NeoForge 1.21 高度兼容，
 * 待 MC 26.x 正式发布后验证 API 变化。
 *
 * 可能需要调整的 API：
 * - 网络系统 StreamCodec
 * - 注册表 BuiltInRegistries
 * - 方块属性查询
 */
public class NeoForgeFuturePlatform implements Platform {

    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeFuturePlatform.class);

    @Override
    public PlatformType getType() {
        return PlatformType.NEO_FORGE;
    }

    @Override
    public String getMinecraftVersion() {
        return "26.x";
    }

    @Override
    public int getMajorVersion() {
        return 26;
    }

    @Override
    public String getPlatformName() {
        return "NeoForge 26.x";
    }

    // ===== 方块属性（预估与 1.21 相同）=====

    @Override
    public BlockProperties getBlockProperties(String blockName) {
        // TODO: 验证 26.x API 变化后实现
        // 预估可直接复用 NeoForge 1.21 实现
        return BlockProperties.EMPTY;
    }

    @Override
    public int getPatternColor(String blockName) {
        // TODO: 实现方块颜色映射
        return 0x808080;
    }

    // ===== 世界信息（预估与 1.21 相同）=====

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
        String normalized = dimensionId.replace("minecraft:", "").toLowerCase();
        switch (normalized) {
            case "overworld": return "null";
            case "the_nether": return "DIM-1";
            case "the_end": return "DIM1";
            default: return normalized.replace(':', '$');
        }
    }

    @Override
    public DimensionTypeInfo getDimensionTypeInfo(String dimensionId) {
        return DimensionTypeInfo.fromDimensionId(dimensionId);
    }

    // ===== 配置系统（预估与 1.21 相同）=====

    @Override
    public int getSyncSpeedLimitKBps() {
        return 1024;
    }

    @Override
    public int getMaxSyncPacketSize() {
        return 262144;
    }

    @Override
    public int getMaxConcurrentRegions() {
        return 4;
    }

    @Override
    public boolean isDebugLoggingEnabled() {
        return false;
    }

    @Override
    public UpdateMode getIncrementalUpdateMode() {
        return UpdateMode.DISABLED;
    }

    @Override
    public int getIncrementalUpdateIntervalTicks() {
        return 200;
    }

    @Override
    public int getScheduledUpdateHour() {
        return 4;
    }

    @Override
    public int getScheduledUpdateMinute() {
        return 0;
    }

    // ===== 文件路径 =====

    @Override
    public Path getServerMapCacheDir() {
        return Path.of("server_map_cache");
    }

    @Override
    public Path getClientXaeroWorldMapDir() {
        // TODO: 验证 26.x 客户端路径
        return null;
    }

    @Override
    public String getCurrentServerDirectoryName() {
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
        LOGGER.debug("Recording {} updated regions (26.x)", regions.size());
    }
}