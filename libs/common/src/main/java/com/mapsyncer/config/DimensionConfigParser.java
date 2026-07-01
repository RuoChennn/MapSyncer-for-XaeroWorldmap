package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度配置解析工具类。
 *
 * 将 parseConfigString / getConfigForDimension / getDefaultDimensionConfigStrings
 * 等重复逻辑从各平台 ModConfig 中提取到此处，消除 10 份拷贝。
 */
public final class DimensionConfigParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionConfigParser.class);

    /** 默认洞穴起始高度（单层模式） */
    public static final int DEFAULT_CAVE_START = CaveSpec.DEFAULT_CAVE_START;

    /** 单键缓存：避免重复解析相同的配置列表 */
    private static volatile String cachedKey;
    private static volatile List<DimensionScanConfig> cachedResult;

    private DimensionConfigParser() {}

    public static List<String> getDefaultDimensionConfigStrings() {
        List<String> defaults = new ArrayList<>(3);
        defaults.add("minecraft:overworld|SURFACE|63|true|false|-64|384|384");
        defaults.add("minecraft:the_nether|CAVE|SPLIT|false|true|0|256|128");
        defaults.add("minecraft:the_end|SURFACE|63|false|false|0|256|256");
        return defaults;
    }

    /**
     * 解析维度配置列表，带单键缓存。
     * 配置在服务端运行期间通常不变，缓存命中时 O(1) 返回。
     */
    public static List<DimensionScanConfig> parseDimensionConfigs(List<? extends String> dimensionConfigs) {
        String key = String.join("\0", dimensionConfigs);
        if (key.equals(cachedKey)) {
            List<DimensionScanConfig> r = cachedResult;
            if (r != null) return r;
        }
        List<DimensionScanConfig> result = new ArrayList<>(dimensionConfigs.size());
        for (String configStr : dimensionConfigs) {
            DimensionScanConfig config = parseConfigString(configStr);
            if (config != null) result.add(config);
        }
        cachedKey = key;
        cachedResult = result;
        return result;
    }

    public static DimensionScanConfig parseConfigString(String configStr) {
        if (configStr == null || configStr.isEmpty()) {
            return null;
        }

        String[] parts = configStr.split("\\|");
        if (parts.length < 1) {
            return null;
        }

        String dimension = parts[0];
        CaveSpec caveSpec = CaveSpec.single(DEFAULT_CAVE_START);
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimension);

        boolean isNewFormat = parts.length > 1 &&
            (parts[1].equalsIgnoreCase("SURFACE") || parts[1].equalsIgnoreCase("CAVE"));

        int scanModeIndex = isNewFormat ? 1 : 2;
        int caveSpecIndex = isNewFormat ? 2 : 3;
        int dimTypeStartIndex = isNewFormat ? 3 : 4;

        String modeStr = parts.length > scanModeIndex ? parts[scanModeIndex] : "SURFACE";

        if (parts.length > caveSpecIndex) {
            caveSpec = CaveSpec.parse(parts[caveSpecIndex]);
        }

        if (parts.length >= dimTypeStartIndex + 5) {
            try {
                boolean hasSkylight = Boolean.parseBoolean(parts[dimTypeStartIndex].trim());
                boolean hasCeiling = Boolean.parseBoolean(parts[dimTypeStartIndex + 1].trim());
                int minY = Integer.parseInt(parts[dimTypeStartIndex + 2].trim());
                int height = Integer.parseInt(parts[dimTypeStartIndex + 3].trim());
                int logicalHeight = Integer.parseInt(parts[dimTypeStartIndex + 4].trim());
                dimTypeInfo = new DimensionTypeInfo(hasSkylight, hasCeiling, minY, height, logicalHeight);
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid dim_type_info in dimension config [{}], using runtime type info", configStr);
            }
        }

        ScanMode mode;
        try {
            mode = ScanMode.valueOf(modeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid scan_mode '{}' in dimension config [{}], falling back to SURFACE",
                modeStr, configStr);
            mode = ScanMode.SURFACE;
        }

        return new DimensionScanConfig(dimension, mode, caveSpec, dimTypeInfo);
    }

    /**
     * 获取特定维度的扫描配置。
     */
    public static DimensionScanConfig getConfigForDimension(String dimensionPath,
            List<? extends String> dimensionConfigs, ScanMode defaultMode, int defaultCave) {
        List<DimensionScanConfig> parsed = parseDimensionConfigs(dimensionConfigs);

        String normalizedPath = dimensionPath.replace("minecraft:", "").toLowerCase();
        boolean isVanilla = normalizedPath.equals("the_nether")
                         || normalizedPath.equals("overworld")
                         || normalizedPath.equals("the_end");

        for (DimensionScanConfig config : parsed) {
            String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
            if (configDim.equals(normalizedPath)) return config;
            if (configDim.equalsIgnoreCase(dimensionPath)
                || configDim.equalsIgnoreCase("minecraft:" + dimensionPath)) return config;
        }

        if (isVanilla) {
            switch (normalizedPath) {
                case "the_nether":
                    return new DimensionScanConfig("minecraft:the_nether", ScanMode.CAVE,
                        CaveSpec.splitOnly(), DimensionTypeInfo.nether());
                case "overworld":
                    return new DimensionScanConfig("minecraft:overworld", ScanMode.SURFACE,
                        CaveSpec.single(defaultCave), DimensionTypeInfo.overworld());
                default:
                    return new DimensionScanConfig("minecraft:the_end", ScanMode.SURFACE,
                        CaveSpec.single(defaultCave), DimensionTypeInfo.theEnd());
            }
        }

        return new DimensionScanConfig(dimensionPath, defaultMode, CaveSpec.single(defaultCave),
            DimensionTypeInfo.fromDimensionId(dimensionPath));
    }
}
