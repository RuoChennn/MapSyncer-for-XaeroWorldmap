package com.mapsyncer.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;

    /**
     * 原版维度的默认配置（系统预设）
     * 在配置文件首次生成时写入
     */
    private static List<Map<String, Object>> getDefaultDimensionConfigs() {
        List<Map<String, Object>> defaults = new ArrayList<>();

        // 主世界：地表模式
        Map<String, Object> overworld = new LinkedHashMap<>();
        overworld.put("dimension", "minecraft:overworld");
        overworld.put("region_folder", "");
        overworld.put("scan_mode", "SURFACE");
        overworld.put("cave_start", 63);
        defaults.add(overworld);

        // 地狱：洞穴模式（地狱 ceiling=128，从 Y=63 开始扫描）
        Map<String, Object> nether = new LinkedHashMap<>();
        nether.put("dimension", "minecraft:the_nether");
        nether.put("region_folder", "DIM-1");
        nether.put("scan_mode", "CAVE");
        nether.put("cave_start", 63);
        defaults.add(nether);

        // 末地：地表模式
        Map<String, Object> theEnd = new LinkedHashMap<>();
        theEnd.put("dimension", "minecraft:the_end");
        theEnd.put("region_folder", "DIM1");
        theEnd.put("scan_mode", "SURFACE");
        theEnd.put("cave_start", 63);
        defaults.add(theEnd);

        return defaults;
    }

    static {
        var serverPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
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
     * @param regionFolder MCA 文件存放目录（如 "DIM-1"，默认在 world 目录下）
     *                     用于适配 mod 修改维度 ID 后的文件路径
     * @param scanMode 扫描模式
     * @param caveStart 洞穴起始高度（SURFACE 模式忽略）
     */
    public record DimensionScanConfig(String dimension, String regionFolder, ScanMode scanMode, int caveStart) {
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
        // General settings (formerly common config)
        public final BooleanValue enableDebugLogging;
        public final IntValue maxConcurrentRegions;
        public final IntValue maxSyncPacketSize;
        public final IntValue syncSpeedLimitKBps;
        public final BooleanValue enableResumeSync;

        // Incremental update settings
        public final EnumValue<UpdateMode> incrementalUpdateMode;
        public final IntValue incrementalUpdateIntervalTicks;
        public final IntValue scheduledUpdateHour;
        public final IntValue scheduledUpdateMinute;

        // 维度扫描配置
        public final EnumValue<ScanMode> defaultScanMode;
        public final IntValue defaultCaveStart;
        public final ConfigValue<List<? extends Map<String, Object>>> dimensionConfigs;

        public ServerConfig(ModConfigSpec.Builder builder) {
            builder.push("general");
            builder.comment("General settings / 通用设置");

            enableDebugLogging = builder
                    .comment("Enable debug logging for map generation",
                             "启用调试日志记录（用于地图生成过程调试）")
                    .define("enableDebugLogging", false);
            maxConcurrentRegions = builder
                    .comment("Maximum number of regions to convert concurrently",
                             "同时转换的最大区域数量")
                    .defineInRange("maxConcurrentRegions", 4, 1, 16);
            maxSyncPacketSize = builder
                    .comment("Maximum sync packet size in bytes (default 1MB)",
                             "同步数据包最大字节数（默认 1MB）")
                    .defineInRange("maxSyncPacketSize", 1048576, 65536, 10485760);
            syncSpeedLimitKBps = builder
                    .comment("Sync speed limit in KB/s (0 = unlimited, recommended 500-2000)",
                             "同步速度限制 KB/s（0 = 无限制，建议 500-2000）")
                    .defineInRange("syncSpeedLimitKBps", 0, 0, 10000);
            enableResumeSync = builder
                    .comment("Enable resume sync when player reconnects",
                             "启用断点续传（玩家重新连接时恢复同步进度）")
                    .define("enableResumeSync", true);

            builder.pop();

            builder.push("incremental_update");
            builder.comment("Incremental update settings / 增量更新设置");

            incrementalUpdateMode = builder
                    .comment("Incremental update mode: DISABLED (off), TICK (periodic by ticks), SCHEDULED (daily at specific time)",
                             "增量更新模式：DISABLED（禁用），TICK（按 tick 周期更新），SCHEDULED（每日定时更新）")
                    .defineEnum("incrementalUpdateMode", UpdateMode.DISABLED);

            incrementalUpdateIntervalTicks = builder
                    .comment("Interval in server ticks for TICK mode (20 ticks = 1 second, default 200 = 10 seconds)",
                             "TICK 模式的更新间隔（20 ticks = 1 秒，默认 200 = 10 秒）")
                    .defineInRange("incrementalUpdateIntervalTicks", 200, 20, 72000);

            scheduledUpdateHour = builder
                    .comment("Hour of day for SCHEDULED mode (0-23, uses server's local timezone)",
                             "SCHEDULED 模式的更新时间（小时，0-23，使用服务器本地时区）")
                    .defineInRange("scheduledUpdateHour", 4, 0, 23);

            scheduledUpdateMinute = builder
                    .comment("Minute of hour for SCHEDULED mode (0-59)",
                             "SCHEDULED 模式的更新时间（分钟，0-59）")
                    .defineInRange("scheduledUpdateMinute", 0, 0, 59);

            builder.pop();

            builder.push("dimension_scan");
            builder.comment("Dimension scan settings / 维度扫描设置");

            defaultScanMode = builder
                    .comment("Default scan mode for dimensions not in the dimension_configs list",
                             "未在维度配置列表中的维度的默认扫描模式")
                    .defineEnum("default_scan_mode", ScanMode.SURFACE);

            defaultCaveStart = builder
                    .comment("Default cave start height for CAVE mode (ignored for SURFACE mode)",
                             "CAVE 模式的洞穴起始高度（SURFACE 模式忽略此项）")
                    .defineInRange("default_cave_start", 63, -512, 512);

            dimensionConfigs = builder
                    .comment("Per-dimension scan configuration list",
                             "Each entry should have: dimension (string), region_folder (string, optional), scan_mode (SURFACE/CAVE), cave_start (int)",
                             "region_folder specifies where MCA files are stored (e.g., 'DIM-1' for nether), defaults to standard Minecraft dimension path",
                             "System presets: overworld (SURFACE), nether (CAVE, cave_start=63), the_end (SURFACE)",
                             "维度扫描配置列表",
                             "每个配置项包含：dimension（字符串），region_folder（字符串，可选），scan_mode（SURFACE/CAVE），cave_start（整数）",
                             "region_folder 指定 MCA 文件存放目录（如地狱用 'DIM-1'），默认使用标准 Minecraft 维度路径",
                             "系统预设：主世界（SURFACE），地狱（CAVE，cave_start=63），末地（SURFACE）")
                    .defineList("dimension_configs", getDefaultDimensionConfigs(),
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
                String regionFolder = (String) map.getOrDefault("region_folder", "");
                String modeStr = (String) map.getOrDefault("scan_mode", "SURFACE");
                ScanMode mode = ScanMode.valueOf(modeStr.toUpperCase());
                Object caveStartObj = map.getOrDefault("cave_start", 63);
                int caveStart = caveStartObj instanceof Number ? ((Number) caveStartObj).intValue() : 63;
                result.add(new DimensionScanConfig(dim, regionFolder, mode, caveStart));
            }
            return result;
        }

        /**
         * 获取特定维度的扫描配置
         * @param dimensionPath 维度路径（如 "the_nether" 或 "minecraft:the_nether"）
         */
        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            // 规范化维度路径（移除 minecraft: 前缀）
            String normalizedPath = dimensionPath.replace("minecraft:", "").toLowerCase();

            // 原版维度的内置默认配置
            // 地狱默认使用洞穴模式（caveStart=63，地狱 ceiling=128）
            if (normalizedPath.equals("the_nether") || normalizedPath.equals("nether")) {
                // 检查用户是否自定义了地狱配置
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_nether") || configDim.equals("nether")) {
                        return config;
                    }
                }
                // 返回内置默认配置
                return new DimensionScanConfig("minecraft:the_nether", "DIM-1", ScanMode.CAVE, 63);
            }

            // 主世界和末地默认使用地表模式
            if (normalizedPath.equals("overworld")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("overworld")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:overworld", "", ScanMode.SURFACE, 63);
            }

            if (normalizedPath.equals("the_end") || normalizedPath.equals("end")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_end") || configDim.equals("end")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:the_end", "DIM1", ScanMode.SURFACE, 63);
            }

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
            // 未匹配则返回默认配置（regionFolder 为空，使用默认路径）
            return new DimensionScanConfig(dimensionPath, "", defaultScanMode.get(), defaultCaveStart.get());
        }
    }
}
