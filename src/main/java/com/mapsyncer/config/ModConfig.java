package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

import java.util.ArrayList;
import java.util.List;

public class ModConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;

    /**
     * 原版维度的默认配置（系统预设）
     * 使用字符串格式避免 NightConfig 序列化问题
     * 格式："dimension|region_folder|scan_mode|cave_start|dim_type_info"
     * dim_type_info 格式："hasSkylight|hasCeiling|minY|height|logicalHeight"
     * 例如："minecraft:the_nether|DIM-1|CAVE|63|false|true|0|256|256"
     */
    private static List<String> getDefaultDimensionConfigStrings() {
        List<String> defaults = new ArrayList<>();

        // 主世界：地表模式，有天空光照
        // hasSkylight=true, hasCeiling=false, minY=-64, height=384
        defaults.add("minecraft:overworld||SURFACE|63|true|false|-64|384|384");

        // 地狱：洞穴模式，有顶棚，无天空光照
        // hasSkylight=false, hasCeiling=true, minY=0, height=256
        defaults.add("minecraft:the_nether|DIM-1|CAVE|63|false|true|0|256|256");

        // 末地：地表模式，无天空光照，无顶棚
        // hasSkylight=false, hasCeiling=false, minY=0, height=256
        defaults.add("minecraft:the_end|DIM1|SURFACE|63|false|false|0|256|256");

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
     * @param dimTypeInfo 维度类型信息（可选，用于离线解析时确定高度范围和光照属性）
     */
    public record DimensionScanConfig(
        String dimension,
        String regionFolder,
        ScanMode scanMode,
        int caveStart,
        DimensionTypeInfo dimTypeInfo
    ) {
        /**
         * 简化构造函数（不包含维度类型信息）
         */
        public DimensionScanConfig(String dimension, String regionFolder, ScanMode scanMode, int caveStart) {
            this(dimension, regionFolder, scanMode, caveStart, null);
        }

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

        /**
         * 获取维度类型信息（如果有配置则返回配置值，否则根据维度 ID 推断）
         */
        public DimensionTypeInfo getDimensionTypeInfo() {
            if (dimTypeInfo != null) {
                return dimTypeInfo;
            }
            return DimensionTypeInfo.fromDimensionId(dimension);
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
        public final ConfigValue<List<? extends String>> dimensionConfigs;

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
                    .comment("Per-dimension scan configuration list (string format)",
                             "Format: \"dimension|region_folder|scan_mode|cave_start|dim_type_info\"",
                             "dim_type_info format: \"hasSkylight|hasCeiling|minY|height|logicalHeight\"",
                             "Example: \"minecraft:the_nether|DIM-1|CAVE|63|false|true|0|256|256\"",
                             "region_folder specifies where MCA files are stored, empty means default path",
                             "维度扫描配置列表（字符串格式）",
                             "格式：\"dimension|region_folder|scan_mode|cave_start|dim_type_info\"",
                             "dim_type_info 格式：\"hasSkylight|hasCeiling|minY|height|logicalHeight\"",
                             "例如：\"minecraft:the_nether|DIM-1|CAVE|63|false|true|0|256|256\"",
                             "region_folder 指定 MCA 文件存放目录，空表示使用默认路径")
                    .defineList("dimension_configs", getDefaultDimensionConfigStrings(),
                        obj -> obj instanceof String);

            builder.pop();
        }

        /**
         * 解析维度配置列表为 DimensionScanConfig 对象
         * 字符串格式：dimension|region_folder|scan_mode|cave_start|dim_type_info
         */
        public List<DimensionScanConfig> parseDimensionConfigs() {
            List<DimensionScanConfig> result = new ArrayList<>();
            for (String configStr : dimensionConfigs.get()) {
                DimensionScanConfig config = parseConfigString(configStr);
                if (config != null) {
                    result.add(config);
                }
            }
            return result;
        }

        /**
         * 解析单个配置字符串
         * 格式：dimension|region_folder|scan_mode|cave_start|dim_type_info
         * dim_type_info 格式：hasSkylight|hasCeiling|minY|height|logicalHeight
         * 向后兼容：不包含 dim_type_info 时自动推断
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
            String regionFolder = parts.length > 1 ? parts[1] : "";
            String modeStr = parts.length > 2 ? parts[2] : "SURFACE";
            int caveStart = 63;
            // 默认从维度 ID 推断维度类型信息
            DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimension);

            try {
                // 解析 caveStart（第4个字段）
                if (parts.length > 3) {
                    caveStart = Integer.parseInt(parts[3]);
                }

                // 解析维度类型信息（第5-9个字段，可选）
                // 格式：hasSkylight|hasCeiling|minY|height|logicalHeight
                if (parts.length >= 9) {
                    boolean hasSkylight = Boolean.parseBoolean(parts[4]);
                    boolean hasCeiling = Boolean.parseBoolean(parts[5]);
                    int minY = Integer.parseInt(parts[6]);
                    int height = Integer.parseInt(parts[7]);
                    int logicalHeight = Integer.parseInt(parts[8]);
                    dimTypeInfo = new DimensionTypeInfo(hasSkylight, hasCeiling, minY, height, logicalHeight);
                }

                ScanMode mode = ScanMode.valueOf(modeStr.toUpperCase());
                return new DimensionScanConfig(dimension, regionFolder, mode, caveStart, dimTypeInfo);
            } catch (NumberFormatException e) {
                // 无效的数字格式，使用默认值
                return new DimensionScanConfig(dimension, regionFolder, ScanMode.SURFACE, 63, dimTypeInfo);
            } catch (IllegalArgumentException e) {
                // 无效的扫描模式，使用默认值
                return new DimensionScanConfig(dimension, regionFolder, ScanMode.SURFACE, caveStart, dimTypeInfo);
            }
        }

        /**
         * 获取特定维度的扫描配置
         * @param dimensionPath 维度路径（如 "the_nether" 或 "minecraft:the_nether"）
         */
        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            // 规范化维度路径（移除 minecraft: 前缀）
            String normalizedPath = dimensionPath.replace("minecraft:", "").toLowerCase();

            // 原版维度的内置默认配置（使用标准名称）
            if (normalizedPath.equals("the_nether")) {
                // 检查用户是否自定义了地狱配置
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_nether")) {
                        return config;
                    }
                }
                // 返回内置默认配置（地狱：洞穴模式，有顶棚）
                return new DimensionScanConfig("minecraft:the_nether", "DIM-1", ScanMode.CAVE, 63,
                    DimensionTypeInfo.nether());
            }

            // 主世界：地表模式，有天空光照
            if (normalizedPath.equals("overworld")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("overworld")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:overworld", "", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.overworld());
            }

            // 末地：地表模式，无天空光照，无顶棚
            if (normalizedPath.equals("the_end")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_end")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:the_end", "DIM1", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.theEnd());
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
            // 未匹配则返回默认配置（regionFolder 为空，使用默认路径，维度类型信息自动推断）
            DimensionTypeInfo inferredDimType = DimensionTypeInfo.fromDimensionId(dimensionPath);
            return new DimensionScanConfig(dimensionPath, "", defaultScanMode.get(), defaultCaveStart.get(), inferredDimType);
        }
    }
}
