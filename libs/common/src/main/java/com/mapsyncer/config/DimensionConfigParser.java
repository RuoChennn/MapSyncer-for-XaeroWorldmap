package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 维度配置解析工具类。
 *
 * <p>新格式：{@code dimension|layerPlan|dim_type_info}（layerPlan 为 SURFACE / Y 坐标 / 组合）</p>
 * <p>旧格式：{@code dimension|scanMode|caveField|dim_type_info} 仍可读，合并为 {@link LayerPlan}</p>
 */
public final class DimensionConfigParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionConfigParser.class);

    public static final int DEFAULT_CAVE_START = LayerPlan.DEFAULT_CAVE_START;

    private static volatile String cachedKey;
    private static volatile List<DimensionScanConfig> cachedResult;

    private DimensionConfigParser() {}

    public static List<String> getDefaultDimensionConfigStrings() {
        List<String> defaults = new ArrayList<>(3);
        defaults.add("minecraft:overworld|SURFACE");
        defaults.add("minecraft:the_nether|SURFACE,63");
        defaults.add("minecraft:the_end|SURFACE");
        return defaults;
    }

    public static void invalidateCache() {
        cachedKey = null;
        cachedResult = null;
    }

    public static List<DimensionScanConfig> parseDimensionConfigs(List<? extends String> dimensionConfigs) {
        String key = String.join("\0", dimensionConfigs);
        synchronized (DimensionConfigParser.class) {
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
            cachedResult = List.copyOf(result);
            return cachedResult;
        }
    }

    public static DimensionScanConfig parseConfigString(String configStr) {
        if (configStr == null || configStr.isEmpty()) {
            return null;
        }

        String[] parts = configStr.split("\\|", -1);
        if (parts.length < 1) {
            return null;
        }

        String dimension = parts[0];
        LayerPlan layerPlan = LayerPlan.empty();
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimension);

        int dimTypeStartIndex;
        if (parts.length > 2 && isLegacyScanModeToken(parts[1]) && !looksLikeDimTypeField(parts[2])) {
            ScanMode legacyMode;
            try {
                legacyMode = ScanMode.valueOf(parts[1].trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid legacy scan_mode '{}' in [{}], treating as layer plan",
                    parts[1], configStr);
                layerPlan = LayerPlan.parse(parts[1]);
                dimTypeStartIndex = 2;
                return finishParse(dimension, layerPlan, dimTypeInfo, parts, dimTypeStartIndex, configStr);
            }
            layerPlan = LayerPlan.fromLegacy(legacyMode, parts.length > 2 ? parts[2] : "");
            dimTypeStartIndex = 3;
        } else {
            layerPlan = parts.length > 1 ? LayerPlan.parse(parts[1]) : LayerPlan.empty();
            dimTypeStartIndex = 2;
        }

        return finishParse(dimension, layerPlan, dimTypeInfo, parts, dimTypeStartIndex, configStr);
    }

    private static DimensionScanConfig finishParse(String dimension, LayerPlan layerPlan,
            DimensionTypeInfo dimTypeInfo, String[] parts, int dimTypeStartIndex, String configStr) {
        return new DimensionScanConfig(dimension, layerPlan, dimTypeInfo);
    }

    private static boolean isLegacyScanModeToken(String s) {
        return "SURFACE".equalsIgnoreCase(s.trim()) || "CAVE".equalsIgnoreCase(s.trim());
    }

    private static boolean looksLikeDimTypeField(String s) {
        String t = s.trim();
        return "true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t);
    }

    public static DimensionScanConfig getConfigForDimension(String dimensionPath,
            List<? extends String> dimensionConfigs, ScanMode defaultMode, int defaultCave) {
        LayerPlan defaultPlan = defaultMode == ScanMode.SURFACE
            ? LayerPlan.surfaceOnly()
            : LayerPlan.caves(defaultCave);

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
                    return new DimensionScanConfig("minecraft:the_nether",
                        LayerPlan.mixed(DEFAULT_CAVE_START), DimensionTypeInfo.nether());
                case "overworld":
                    return new DimensionScanConfig("minecraft:overworld",
                        LayerPlan.surfaceOnly(), DimensionTypeInfo.overworld());
                default:
                    return new DimensionScanConfig("minecraft:the_end",
                        LayerPlan.surfaceOnly(), DimensionTypeInfo.theEnd());
            }
        }

        return new DimensionScanConfig(dimensionPath, defaultPlan,
            DimensionTypeInfo.fromDimensionId(dimensionPath));
    }
}
