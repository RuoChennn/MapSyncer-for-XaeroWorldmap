package com.mapsyncer.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ModConfig {

    public static final ModConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;

    public static final ModConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;

    static {
        var commonPair = new ModConfigSpec.Builder().configure(CommonConfig::new);
        COMMON = commonPair.getLeft();
        COMMON_SPEC = commonPair.getRight();

        var serverPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    public static class CommonConfig {
        public final BooleanValue enableDebugLogging;
        public final IntValue maxConcurrentRegions;
        public final IntValue maxSyncPacketSize;
        public final IntValue syncSpeedLimitKBps;
        public final BooleanValue enableResumeSync;

        public CommonConfig(ModConfigSpec.Builder builder) {
            builder.push("general");
            enableDebugLogging = builder
                    .comment("Enable debug logging for map generation")
                    .define("enableDebugLogging", false);
            maxConcurrentRegions = builder
                    .comment("Maximum number of regions to convert concurrently")
                    .defineInRange("maxConcurrentRegions", 4, 1, 16);
            maxSyncPacketSize = builder
                    .comment("Maximum sync packet size in bytes (default 1MB)")
                    .defineInRange("maxSyncPacketSize", 1048576, 65536, 10485760);
            syncSpeedLimitKBps = builder
                    .comment("Sync speed limit in KB/s (0 = unlimited, recommended 500-2000)")
                    .defineInRange("syncSpeedLimitKBps", 0, 0, 10000);
            enableResumeSync = builder
                    .comment("Enable resume sync when player reconnects")
                    .define("enableResumeSync", true);
            builder.pop();
        }
    }

    public enum UpdateMode {
        DISABLED,   // 禁用
        TICK,       // tick周期模式
        SCHEDULED   // 每日定时模式
    }

    /**
     * 扫描模式枚举
     */
    public enum ScanMode {
        SURFACE,  // 地表模式：从高度图向下扫描
        CAVE      // 洞穴模式：从固定高度向下扫描
    }

    /**
     * 维度扫描配置记录
     *
     * @param dimension 维度 ID（如 "minecraft:the_nether"）
     * @param xaeroFolder Xaero 文件夹名称（如 "DIM-1"），若为空则使用默认映射
     * @param scanMode 扫描模式
     * @param caveStart 洞穴起始高度（SURFACE 模式忽略）
     */
    public record DimensionScanConfig(String dimension, String xaeroFolder, ScanMode scanMode, int caveStart) {
        /**
         * 计算洞穴层号
         * 参考 Xaero MapProcessor.getCaveLayer():
         * - SURFACE 模式返回 Integer.MAX_VALUE（地表）
         * - CAVE 模式返回 caveStart >> 4（除以16）
         * - 支持负高度：-64 → layer -4
         */
        public int getCaveLayer() {
            if (scanMode == ScanMode.SURFACE) {
                return Integer.MAX_VALUE;
            }
            if (caveStart == Integer.MAX_VALUE || caveStart == Integer.MIN_VALUE) {
                return caveStart;
            }
            return caveStart >> 4;
        }

        /**
         * 获取洞穴深度（覆盖到世界底部）
         */
        public int getCaveDepth(int minBuildHeight) {
            if (scanMode == ScanMode.SURFACE) {
                return 0;
            }
            return Math.max(30, caveStart - minBuildHeight);
        }
    }

    public static class ServerConfig {
        public final EnumValue<UpdateMode> incrementalUpdateMode;
        public final IntValue incrementalUpdateIntervalTicks;
        public final IntValue scheduledUpdateHour;
        public final IntValue scheduledUpdateMinute;

        // 维度扫描配置
        public final EnumValue<ScanMode> defaultScanMode;
        public final IntValue defaultCaveStart;
        public final ConfigValue<List<? extends Map<String, Object>>> dimensionConfigs;

        public ServerConfig(ModConfigSpec.Builder builder) {
            builder.push("incremental_update");

            incrementalUpdateMode = builder
                    .comment("Incremental update mode: DISABLED (off), TICK (periodic by ticks), SCHEDULED (daily at specific time)")
                    .defineEnum("incrementalUpdateMode", UpdateMode.DISABLED);

            incrementalUpdateIntervalTicks = builder
                    .comment("Interval in server ticks for TICK mode (20 ticks = 1 second, default 200 = 10 seconds)")
                    .defineInRange("incrementalUpdateIntervalTicks", 200, 20, 72000);

            scheduledUpdateHour = builder
                    .comment("Hour of day for SCHEDULED mode (0-23, uses server's local timezone)")
                    .defineInRange("scheduledUpdateHour", 4, 0, 23);

            scheduledUpdateMinute = builder
                    .comment("Minute of hour for SCHEDULED mode (0-59)")
                    .defineInRange("scheduledUpdateMinute", 0, 0, 59);

            builder.pop();

            builder.push("dimension_scan");

            defaultScanMode = builder
                    .comment("Default scan mode for dimensions not in the dimension_configs list")
                    .defineEnum("default_scan_mode", ScanMode.SURFACE);

            defaultCaveStart = builder
                    .comment("Default cave start height for CAVE mode (ignored for SURFACE mode)")
                    .defineInRange("default_cave_start", 63, -512, 512);

            dimensionConfigs = builder
                    .comment("Per-dimension scan configuration list",
                             "Each entry should have: dimension (string), xaero_folder (string, optional), scan_mode (SURFACE/CAVE), cave_start (int)",
                             "xaero_folder overrides default mapping (e.g., 'DIM-1' for nether)")
                    .defineList("dimension_configs", List.of(),
                        obj -> obj instanceof Map);

            builder.pop();
        }

        /**
         * 解析维度配置列表为 DimensionScanConfig 对象
         */
        @SuppressWarnings("unchecked")
        public List<DimensionScanConfig> parseDimensionConfigs() {
            List<DimensionScanConfig> result = new ArrayList<>();
            for (Object entry : dimensionConfigs.get()) {
                Map<String, Object> map = (Map<String, Object>) entry;
                String dim = (String) map.getOrDefault("dimension", "overworld");
                String xaeroFolder = (String) map.getOrDefault("xaero_folder", "");
                String modeStr = (String) map.getOrDefault("scan_mode", "SURFACE");
                ScanMode mode = ScanMode.valueOf(modeStr.toUpperCase());
                Object caveStartObj = map.getOrDefault("cave_start", 63);
                int caveStart = caveStartObj instanceof Number ? ((Number) caveStartObj).intValue() : 63;
                result.add(new DimensionScanConfig(dim, xaeroFolder, mode, caveStart));
            }
            return result;
        }

        /**
         * 获取特定维度的扫描配置
         * @param dimensionPath 维度路径（如 "the_nether" 或 "minecraft:the_nether"）
         */
        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            // 尝试匹配配置列表中的维度
            for (DimensionScanConfig config : parseDimensionConfigs()) {
                String configDim = config.dimension();
                // 支持多种格式匹配：完整命名空间、简写、路径
                if (configDim.equalsIgnoreCase(dimensionPath) ||
                    configDim.equalsIgnoreCase("minecraft:" + dimensionPath) ||
                    configDim.replace("minecraft:", "").equalsIgnoreCase(dimensionPath)) {
                    return config;
                }
            }
            // 未匹配则返回默认配置（xaeroFolder 为空，使用默认映射）
            return new DimensionScanConfig(dimensionPath, "", defaultScanMode.get(), defaultCaveStart.get());
        }
    }
}
