package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度配置解析工具类。
 *
 * 将 parseConfigString / getConfigForDimension / getDefaultDimensionConfigStrings
 * 等重复逻辑从各平台 ModConfig 中提取到此处，消除 10 份拷贝。
 */
public final class DimensionConfigParser {

    /** 默认洞穴起始高度 */
    public static final int DEFAULT_CAVE_START = 63;

    private DimensionConfigParser() {}

    public static List<String> getDefaultDimensionConfigStrings() {
        List<String> defaults = new ArrayList<>();
        defaults.add("minecraft:overworld|SURFACE|63|true|false|-64|384|384");
        defaults.add("minecraft:the_nether|CAVE|63|false|true|0|256|256");
        defaults.add("minecraft:the_end|SURFACE|63|false|false|0|256|256");
        return defaults;
    }

    public static List<DimensionScanConfig> parseDimensionConfigs(List<String> dimensionConfigs) {
        List<DimensionScanConfig> result = new ArrayList<>();
        for (String configStr : dimensionConfigs) {
            DimensionScanConfig config = parseConfigString(configStr);
            if (config != null) {
                result.add(config);
            }
        }
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
        int caveStart = DEFAULT_CAVE_START;
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimension);

        try {
            boolean isNewFormat = parts.length > 1 &&
                (parts[1].equalsIgnoreCase("SURFACE") || parts[1].equalsIgnoreCase("CAVE"));

            int scanModeIndex = isNewFormat ? 1 : 2;
            int caveStartIndex = isNewFormat ? 2 : 3;
            int dimTypeStartIndex = isNewFormat ? 3 : 4;

            String modeStr = parts.length > scanModeIndex ? parts[scanModeIndex] : "SURFACE";

            if (parts.length > caveStartIndex) {
                caveStart = Integer.parseInt(parts[caveStartIndex]);
            }

            if (parts.length >= dimTypeStartIndex + 5) {
                boolean hasSkylight = Boolean.parseBoolean(parts[dimTypeStartIndex]);
                boolean hasCeiling = Boolean.parseBoolean(parts[dimTypeStartIndex + 1]);
                int minY = Integer.parseInt(parts[dimTypeStartIndex + 2]);
                int height = Integer.parseInt(parts[dimTypeStartIndex + 3]);
                int logicalHeight = Integer.parseInt(parts[dimTypeStartIndex + 4]);
                dimTypeInfo = new DimensionTypeInfo(hasSkylight, hasCeiling, minY, height, logicalHeight);
            }

            ScanMode mode = ScanMode.valueOf(modeStr.toUpperCase());
            return new DimensionScanConfig(dimension, mode, caveStart, dimTypeInfo);
        } catch (NumberFormatException e) {
            return new DimensionScanConfig(dimension, ScanMode.SURFACE, DEFAULT_CAVE_START, dimTypeInfo);
        } catch (IllegalArgumentException e) {
            return new DimensionScanConfig(dimension, ScanMode.SURFACE, caveStart, dimTypeInfo);
        }
    }

    /**
     * 获取特定维度的扫描配置。
     *
     * @param dimensionPath 维度路径
     * @param dimensionConfigs 配置字符串列表
     * @param defaultMode 默认扫描模式（未匹配时使用）
     * @param defaultCave 默认洞穴起始高度（未匹配时使用）
     */
    public static DimensionScanConfig getConfigForDimension(String dimensionPath,
            List<String> dimensionConfigs, ScanMode defaultMode, int defaultCave) {
        List<DimensionScanConfig> parsed = parseDimensionConfigs(dimensionConfigs);

        String normalizedPath = dimensionPath.replace("minecraft:", "").toLowerCase();

        // 原版维度的内置默认配置
        if (normalizedPath.equals("the_nether")) {
            for (DimensionScanConfig config : parsed) {
                String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                if (configDim.equals("the_nether")) return config;
            }
            return new DimensionScanConfig("minecraft:the_nether", ScanMode.CAVE, DEFAULT_CAVE_START,
                DimensionTypeInfo.nether());
        }
        if (normalizedPath.equals("overworld")) {
            for (DimensionScanConfig config : parsed) {
                String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                if (configDim.equals("overworld")) return config;
            }
            return new DimensionScanConfig("minecraft:overworld", ScanMode.SURFACE, DEFAULT_CAVE_START,
                DimensionTypeInfo.overworld());
        }
        if (normalizedPath.equals("the_end")) {
            for (DimensionScanConfig config : parsed) {
                String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                if (configDim.equals("the_end")) return config;
            }
            return new DimensionScanConfig("minecraft:the_end", ScanMode.SURFACE, DEFAULT_CAVE_START,
                DimensionTypeInfo.theEnd());
        }

        // 尝试匹配配置列表中的维度
        for (DimensionScanConfig config : parsed) {
            String configDim = config.dimension();
            if (configDim.equalsIgnoreCase(dimensionPath) ||
                configDim.equalsIgnoreCase("minecraft:" + dimensionPath) ||
                configDim.replace("minecraft:", "").equalsIgnoreCase(dimensionPath)) {
                return config;
            }
        }

        DimensionTypeInfo inferredDimType = DimensionTypeInfo.fromDimensionId(dimensionPath);
        return new DimensionScanConfig(dimensionPath, defaultMode, defaultCave, inferredDimType);
    }
}
