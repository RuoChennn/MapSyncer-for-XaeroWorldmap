package com.mapsyncer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mapsyncer.MapSyncer;
import com.mapsyncer.mca.DimensionTypeInfo;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mod 配置类 - Fabric 版本
 *
 * 使用 JSON 文件存储配置，路径：config/mapsyncer.json
 */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.TRANSIENT, Modifier.STATIC)
            .create();

    private static final String CONFIG_FILE = "mapsyncer.json";

    /** 服务端配置实例 */
    public static final ServerConfig SERVER = new ServerConfig();

    /** 配置文件路径（运行时设置） */
    private static Path configDir;

    /**
     * 初始化配置目录
     */
    public static void init(Path configDirectory) {
        configDir = configDirectory;
        load();
    }

    /**
     * 加载配置文件
     */
    public static void load() {
        if (configDir == null) return;
        Path configFile = configDir.resolve(CONFIG_FILE);
        if (Files.exists(configFile)) {
            try {
                String json = Files.readString(configFile);
                ServerConfig loaded = GSON.fromJson(json, ServerConfig.class);
                if (loaded != null) {
                    SERVER.copyFrom(loaded);
                }
                MapSyncer.LOGGER.info("Config loaded from {}", configFile);
            } catch (Exception e) {
                MapSyncer.LOGGER.error("Failed to load config, using defaults", e);
            }
        } else {
            save();
            MapSyncer.LOGGER.info("Config file created with defaults at {}", configFile);
        }
    }

    /**
     * 保存配置文件
     */
    public static void save() {
        if (configDir == null) return;
        Path configFile = configDir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(configDir);
            Files.writeString(configFile, GSON.toJson(SERVER));
        } catch (IOException e) {
            MapSyncer.LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * 更新模式枚举
     */
    public enum UpdateMode {
        DISABLED,
        TICK,
        SCHEDULED
    }

    /**
     * 扫描模式枚举
     */
    public enum ScanMode {
        SURFACE,
        CAVE
    }

    /**
     * 维度扫描配置记录
     */
    public record DimensionScanConfig(
        String dimension,
        ScanMode scanMode,
        int caveStart,
        DimensionTypeInfo dimTypeInfo
    ) {
        public DimensionScanConfig(String dimension, ScanMode scanMode, int caveStart) {
            this(dimension, scanMode, caveStart, null);
        }

        public int getCaveLayer() {
            if (scanMode == ScanMode.SURFACE) {
                return Integer.MAX_VALUE;
            }
            if (caveStart == Integer.MAX_VALUE || caveStart == Integer.MIN_VALUE) {
                return caveStart;
            }
            return caveStart >> 4;
        }

        public int getCaveDepth(int minBuildHeight) {
            if (scanMode == ScanMode.SURFACE) {
                return 0;
            }
            return Math.max(30, caveStart - minBuildHeight);
        }

        public DimensionTypeInfo getDimensionTypeInfo() {
            if (dimTypeInfo != null) {
                return dimTypeInfo;
            }
            return DimensionTypeInfo.fromDimensionId(dimension);
        }
    }

    /**
     * 服务端配置类 - JSON 序列化
     */
    public static class ServerConfig {
        // ========== 通用设置 ==========
        public boolean enableDebugLogging = false;
        public int maxConcurrentRegions = 4;
        public int maxSyncPacketSize = 262144;
        public int syncSpeedLimitKBps = 1024;

        // ========== 增量更新设置 ==========
        public UpdateMode incrementalUpdateMode = UpdateMode.DISABLED;
        public int incrementalUpdateIntervalTicks = 200;
        public int scheduledUpdateHour = 4;
        public int scheduledUpdateMinute = 0;

        // ========== 维度扫描配置 ==========
        public ScanMode defaultScanMode = ScanMode.SURFACE;
        public int defaultCaveStart = 63;
        public List<String> dimensionConfigs = getDefaultDimensionConfigStrings();

        /**
         * 从另一个配置对象复制值
         */
        void copyFrom(ServerConfig other) {
            this.enableDebugLogging = other.enableDebugLogging;
            this.maxConcurrentRegions = clamp(other.maxConcurrentRegions, 1, 16);
            this.maxSyncPacketSize = clamp(other.maxSyncPacketSize, 65536, 1048576);
            this.syncSpeedLimitKBps = clamp(other.syncSpeedLimitKBps, 0, 10240);
            this.incrementalUpdateMode = other.incrementalUpdateMode;
            this.incrementalUpdateIntervalTicks = clamp(other.incrementalUpdateIntervalTicks, 20, 72000);
            this.scheduledUpdateHour = clamp(other.scheduledUpdateHour, 0, 23);
            this.scheduledUpdateMinute = clamp(other.scheduledUpdateMinute, 0, 59);
            this.defaultScanMode = other.defaultScanMode;
            this.defaultCaveStart = clamp(other.defaultCaveStart, -512, 512);
            if (other.dimensionConfigs != null) {
                this.dimensionConfigs = other.dimensionConfigs;
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        // 以下是兼容旧代码的 getter 方法，保持 API 不变
        public boolean enableDebugLogging() { return enableDebugLogging; }
        public int maxConcurrentRegions() { return maxConcurrentRegions; }
        public int maxSyncPacketSize() { return maxSyncPacketSize; }
        public int syncSpeedLimitKBps() { return syncSpeedLimitKBps; }
        public UpdateMode incrementalUpdateMode() { return incrementalUpdateMode; }
        public int incrementalUpdateIntervalTicks() { return incrementalUpdateIntervalTicks; }
        public int scheduledUpdateHour() { return scheduledUpdateHour; }
        public int scheduledUpdateMinute() { return scheduledUpdateMinute; }
        public ScanMode defaultScanMode() { return defaultScanMode; }
        public int defaultCaveStart() { return defaultCaveStart; }
        public List<String> dimensionConfigs() { return dimensionConfigs; }

        /**
         * 获取原版维度的默认配置
         */
        private static List<String> getDefaultDimensionConfigStrings() {
            List<String> defaults = new ArrayList<>();
            defaults.add("minecraft:overworld|SURFACE|63|true|false|-64|384|384");
            defaults.add("minecraft:the_nether|CAVE|63|false|true|0|256|256");
            defaults.add("minecraft:the_end|SURFACE|63|false|false|0|256|256");
            return defaults;
        }

        /**
         * 解析维度配置列表
         */
        public List<DimensionScanConfig> parseDimensionConfigs() {
            List<DimensionScanConfig> result = new ArrayList<>();
            for (String configStr : dimensionConfigs) {
                DimensionScanConfig config = parseConfigString(configStr);
                if (config != null) {
                    result.add(config);
                }
            }
            return result;
        }

        /**
         * 解析单个配置字符串
         */
        private DimensionScanConfig parseConfigString(String configStr) {
            if (configStr == null || configStr.isEmpty()) {
                return null;
            }

            String[] parts = configStr.split("\\|");
            if (parts.length < 1) {
                return null;
            }

            String dimension = parts[0];
            int caveStart = 63;
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
                return new DimensionScanConfig(dimension, ScanMode.SURFACE, 63, dimTypeInfo);
            } catch (IllegalArgumentException e) {
                return new DimensionScanConfig(dimension, ScanMode.SURFACE, caveStart, dimTypeInfo);
            }
        }

        /**
         * 获取特定维度的扫描配置
         */
        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            String normalizedPath = dimensionPath.replace("minecraft:", "").toLowerCase();

            if (normalizedPath.equals("the_nether")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_nether")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:the_nether", ScanMode.CAVE, 63,
                    DimensionTypeInfo.nether());
            }

            if (normalizedPath.equals("overworld")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("overworld")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:overworld", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.overworld());
            }

            if (normalizedPath.equals("the_end")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_end")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:the_end", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.theEnd());
            }

            for (DimensionScanConfig config : parseDimensionConfigs()) {
                String configDim = config.dimension();
                if (configDim.equalsIgnoreCase(dimensionPath) ||
                    configDim.equalsIgnoreCase("minecraft:" + dimensionPath) ||
                    configDim.replace("minecraft:", "").equalsIgnoreCase(dimensionPath)) {
                    return config;
                }
            }

            DimensionTypeInfo inferredDimType = DimensionTypeInfo.fromDimensionId(dimensionPath);
            return new DimensionScanConfig(dimensionPath, defaultScanMode, defaultCaveStart, inferredDimType);
        }
    }
}
