package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.UpdateMode;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod 配置类
 *
 * <p>管理 MapSyncer for XaeroWorldMap 的配置，包括:</p>
 * <ul>
 *   <li>客户端设置（哈希计算线程数等）</li>
 *   <li>服务器端设置（调试日志、并发限制等）</li>
 *   <li>增量更新设置（更新模式、时间间隔）</li>
 *   <li>维度扫描配置（扫描模式、起始高度等）</li>
 * </ul>
 *
 * <p>使用 NeoForge 的 ModConfigSpec 进行配置管理</p>
 *
 * @see ClientConfig 客户端配置内部类
 * @see ServerConfig 服务端配置内部类
 * @see DimensionScanConfig 维度扫描配置记录
 * @see ScanMode 扫描模式枚举
 * @see UpdateMode 更新模式枚举
 */
public class ModConfig {

    /**
     * 客户端配置规范对象
     */
    public static final ModConfigSpec CLIENT_SPEC;

    /**
     * 客户端配置实例
     */
    public static final ClientConfig CLIENT;

    /**
     * 服务端配置规范对象
     */
    public static final ModConfigSpec SERVER_SPEC;

    /**
     * 服务端配置实例
     */
    public static final ServerConfig SERVER;

    /**
     * 获取原版维度的默认配置（系统预设）
     *
     * <p>使用字符串格式避免 NightConfig 序列化问题</p>
     * <p>格式："dimension|scan_mode|cave_start|dim_type_info"</p>
     * <p>dim_type_info 格式："hasSkylight|hasCeiling|minY|height|logicalHeight"</p>
     *
     * <p>例如："minecraft:the_nether|CAVE|63|false|true|0|256|256"</p>
     *
     * @return 默认维度配置字符串列表
     */
    private static List<String> getDefaultDimensionConfigStrings() {
        List<String> defaults = new ArrayList<>();

        // 主世界：地表模式，有天空光照
        // hasSkylight=true, hasCeiling=false, minY=-64, height=384
        defaults.add("minecraft:overworld|SURFACE|63|true|false|-64|384|384");

        // 地狱：洞穴模式，有顶棚，无天空光照
        // hasSkylight=false, hasCeiling=true, minY=0, height=256
        defaults.add("minecraft:the_nether|CAVE|63|false|true|0|256|256");

        // 末地：地表模式，无天空光照，无顶棚
        // hasSkylight=false, hasCeiling=false, minY=0, height=256
        defaults.add("minecraft:the_end|SURFACE|63|false|false|0|256|256");

        return defaults;
    }

    /**
     * 初始化配置的静态代码块
     */
    static {
        var clientPair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();

        var serverPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    /**
     * 客户端配置内部类
     *
     * <p>包含所有客户端可配置的选项</p>
     */
    public static class ClientConfig {

        /**
         * 哈希计算线程数
         *
         * <p>用于 ClientHashManager 的 ForkJoinPool 并行计算区域文件哈希。</p>
         * <p>默认值使用 JVM 可用处理器数的一半，避免阻塞游戏主线程。</p>
         *
         * <p>线程数选择建议：</p>
         * <ul>
         *   <li>1-2 核：使用 1 线程</li>
         *   <li>4 核：使用 2 线程</li>
         *   <li>8 核及以上：使用 4-8 线程</li>
         *   <li>最大不超过可用处理器数</li>
         * </ul>
         */
        public final IntValue hashThreads;

        /**
         * 构造客户端配置
         *
         * <p>定义所有配置选项及其默认值、范围和注释</p>
         *
         * @param builder ModConfigSpec 构建器
         */
        public ClientConfig(ModConfigSpec.Builder builder) {
            builder.push("client");
            builder.comment("Client settings / 客户端设置");

            // 计算默认线程数：可用处理器数的一半，最少 1 个
            int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            int maxThreads = Runtime.getRuntime().availableProcessors();

            hashThreads = builder
                    .comment("Number of threads for hash computation during map sync",
                             "哈希计算线程数（用于地图同步时的并行计算）",
                             "",
                             "Default uses half of available processors to avoid blocking game main thread",
                             "默认使用可用处理器数的一半，避免阻塞游戏主线程",
                             "",
                             "Thread count recommendations:",
                             "线程数选择建议：",
                             "  1-2 cores: use 1 thread",
                             "  1-2 核：使用 1 线程",
                             "  4 cores: use 2 threads (default for most setups)",
                             "  4 核：使用 2 线程（大多数配置的默认值）",
                             "  8+ cores: use 4-8 threads for faster sync",
                             "  8+ 核：使用 4-8 线程加快同步速度",
                             "",
                             "Default: " + defaultThreads + " (half of " + maxThreads + " available processors)",
                             "默认：" + defaultThreads + "（可用 " + maxThreads + " 个处理器的一半）",
                             "Range: 1 - " + maxThreads,
                             "范围：1 - " + maxThreads)
                    .defineInRange("hashThreads", defaultThreads, 1, maxThreads);

            builder.pop();
        }

        /**
         * 获取哈希计算线程数
         *
         * @return 配置的线程数
         */
        public int getHashThreads() {
            return hashThreads.get();
        }
    }

    /**
     * 扫描模式枚举
     *
     * <p>定义维度地图的扫描方式</p>
     */
    public enum ScanMode {
        /**
         * 地表模式：从高度图向下扫描
         *
         * <p>适用于普通地表地图，使用高度图确定扫描起始位置</p>
         */
        SURFACE,

        /**
         * 洞穴模式：从固定高度向下扫描
         *
         * <p>适用于洞穴地图（如地狱），使用固定的起始高度向下扫描</p>
         */
        CAVE
    }

    /**
     * 维度扫描配置记录
     *
     * <p>存储单个维度的扫描参数</p>
     *
     * @param dimension 维度 ID（如 "minecraft:the_nether"）
     * @param scanMode 扫描模式（SURFACE 或 CAVE）
     * @param caveStart 洞穴起始高度（SURFACE 模式忽略此参数）
     * @param dimTypeInfo 维度类型信息（可选，用于离线解析时确定高度范围和光照属性）
     */
    public record DimensionScanConfig(
        String dimension,
        ScanMode scanMode,
        int caveStart,
        DimensionTypeInfo dimTypeInfo
    ) {
        /**
         * 简化构造函数（不包含维度类型信息）
         *
         * <p>维度类型信息将根据维度 ID 自动推断</p>
         *
         * @param dimension 维度 ID
         * @param scanMode 扫描模式
         * @param caveStart 洞穴起始高度
         */
        public DimensionScanConfig(String dimension, ScanMode scanMode, int caveStart) {
            this(dimension, scanMode, caveStart, null);
        }

        /**
         * 计算洞穴层号
         *
         * <p>参考 Xaero MapProcessor.getCaveLayer():</p>
         * <ul>
         *   <li>SURFACE 模式返回 Integer.MAX_VALUE（地表）</li>
         *   <li>CAVE 模式返回 caveStart >> 4（除以16）</li>
         *   <li>支持负高度：-64 → layer -4</li>
         * </ul>
         *
         * @return 洞穴层号
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
         *
         * @param minBuildHeight 世界最低建筑高度
         * @return 洞穴深度值
         */
        public int getCaveDepth(int minBuildHeight) {
            if (scanMode == ScanMode.SURFACE) {
                return 0;
            }
            return Math.max(30, caveStart - minBuildHeight);
        }

        /**
         * 获取维度类型信息
         *
         * <p>如果有配置则返回配置值，否则根据维度 ID 推断</p>
         *
         * @return DimensionTypeInfo 对象
         */
        public DimensionTypeInfo getDimensionTypeInfo() {
            if (dimTypeInfo != null) {
                return dimTypeInfo;
            }
            return DimensionTypeInfo.fromDimensionId(dimension);
        }
    }

    /**
     * 服务端配置内部类
     *
     * <p>包含所有服务端可配置的选项</p>
     */
    public static class ServerConfig {
        // ========== 通用设置 ==========

        /**
         * 启用调试日志记录
         */
        public final BooleanValue enableDebugLogging;

        /**
         * 最大并发区域转换数量
         */
        public final IntValue maxConcurrentRegions;

        /**
         * 最大同步数据包大小（字节）
         */
        public final IntValue maxSyncPacketSize;

        /**
         * 同步速度限制（KB/s）
         */
        public final IntValue syncSpeedLimitKBps;

        // ========== 增量更新设置 ==========

        /**
         * 增量更新模式
         */
        public final EnumValue<UpdateMode> incrementalUpdateMode;

        /**
         * TICK 模式的更新间隔（tick 数）
         */
        public final IntValue incrementalUpdateIntervalTicks;

        /**
         * SCHEDULED 模式的更新时间（小时）
         */
        public final IntValue scheduledUpdateHour;

        /**
         * SCHEDULED 模式的更新时间（分钟）
         */
        public final IntValue scheduledUpdateMinute;

        // ========== 维度扫描配置 ==========

        /**
         * 默认扫描模式
         */
        public final EnumValue<ScanMode> defaultScanMode;

        /**
         * 默认洞穴起始高度
         */
        public final IntValue defaultCaveStart;

        /**
         * 维度扫描配置列表
         */
        public final ConfigValue<List<? extends String>> dimensionConfigs;

        /**
         * 构造服务端配置
         *
         * <p>定义所有配置选项及其默认值、范围和注释</p>
         *
         * @param builder ModConfigSpec 构建器
         */
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
                    .comment("Maximum sync packet size in bytes",
                             "同步数据包最大字节数",
                             "",
                             "Size options for quick reference (all divide 1024KB/s evenly):",
                             "  65536  = 64KB  (conservative, 16 packets/s at 1024KB/s)",
                             "  131072 = 128KB (balanced, 8 packets/s at 1024KB/s)",
                             "  262144 = 256KB (recommended, 4 packets/s at 1024KB/s)",
                             "  524288 = 512KB (efficient, 2 packets/s at 1024KB/s)",
                             "  1048576 = 1MB  (maximum, 1 packet/s at 1024KB/s)",
                             "",
                             "大小选项供快速参考（均能被 1024KB/s 整除）：",
                             "  65536  = 64KB  （保守，1024KB/s 时每秒 16 包）",
                             "  131072 = 128KB （平衡，1024KB/s 时每秒 8 包）",
                             "  262144 = 256KB （推荐，1024KB/s 时每秒 4 包）",
                             "  524288 = 512KB （高效，1024KB/s 时每秒 2 包）",
                             "  1048576 = 1MB  （最大，1024KB/s 时每秒 1 包）",
                             "",
                             "Default: 256KB (recommended), Range: 64KB - 1MB",
                             "默认：256KB（推荐），范围：64KB - 1MB")
                    .defineInRange("maxSyncPacketSize", 262144, 65536, 1048576);
            syncSpeedLimitKBps = builder
                    .comment("Sync speed limit in KB/s (0 = unlimited)",
                             "同步速度限制 KB/s（0 = 无限制）",
                             "",
                             "Speed options for quick reference:",
                             "  100  = 100KB/s  (slow, suitable for limited bandwidth)",
                             "  512  = 512KB/s  (moderate, half MiB)",
                             "  1024 = 1024KB/s = 1MiB/s (default, recommended)",
                             "  5120 = 5120KB/s = 5MiB/s (fast, suitable for LAN)",
                             "  10240 = 10240KB/s = 10MiB/s (very fast)",
                             "",
                             "速度选项供快速参考：",
                             "  100  = 100KB/s  （慢速，适合带宽受限）",
                             "  512  = 512KB/s  （中等，半 MiB）",
                             "  1024 = 1024KB/s = 1MiB/s （默认，推荐）",
                             "  5120 = 5120KB/s = 5MiB/s （快速，适合局域网）",
                             "  10240 = 10240KB/s = 10MiB/s （非常快）",
                             "",
                             "Default: 1024 (1MiB/s), Range: 0 - 10240",
                             "默认：1024（1MiB/s），范围：0 - 10240")
                    .defineInRange("syncSpeedLimitKBps", 1024, 0, 10240);

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
                             "Format: \"dimension|scan_mode|cave_start|dim_type_info\"",
                             "dim_type_info format: \"hasSkylight|hasCeiling|minY|height|logicalHeight\"",
                             "Example: \"minecraft:the_nether|CAVE|63|false|true|0|256|256\"",
                             "维度扫描配置列表（字符串格式）",
                             "格式：\"dimension|scan_mode|cave_start|dim_type_info\"",
                             "dim_type_info 格式：\"hasSkylight|hasCeiling|minY|height|logicalHeight\"",
                             "例如：\"minecraft:the_nether|CAVE|63|false|true|0|256|256\"")
                    .defineList("dimension_configs", getDefaultDimensionConfigStrings(),
                        obj -> obj instanceof String);

            builder.pop();
        }

        /**
         * 解析维度配置列表
         *
         * <p>将字符串格式的配置转换为 DimensionScanConfig 对象列表</p>
         * <p>字符串格式："dimension|scan_mode|cave_start|dim_type_info"</p>
         *
         * @return DimensionScanConfig 对象列表
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
         *
         * <p>新格式："dimension|scan_mode|cave_start|dim_type_info"</p>
         * <p>旧格式（向后兼容）："dimension|region_folder|scan_mode|cave_start|dim_type_info"</p>
         * <p>dim_type_info 格式："hasSkylight|hasCeiling|minY|height|logicalHeight"</p>
         *
         * @param configStr 配置字符串
         * @return DimensionScanConfig 对象，如果无效则返回 null
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
                // 检测格式：如果 parts[1] 是扫描模式，则为新格式；否则为旧格式（跳过 region_folder）
                boolean isNewFormat = parts.length > 1 &&
                    (parts[1].equalsIgnoreCase("SURFACE") || parts[1].equalsIgnoreCase("CAVE"));

                int scanModeIndex = isNewFormat ? 1 : 2;
                int caveStartIndex = isNewFormat ? 2 : 3;
                int dimTypeStartIndex = isNewFormat ? 3 : 4;

                String modeStr = parts.length > scanModeIndex ? parts[scanModeIndex] : "SURFACE";

                if (parts.length > caveStartIndex) {
                    caveStart = Integer.parseInt(parts[caveStartIndex]);
                }

                // 解析维度类型信息
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
         *
         * <p>查找顺序:</p>
         * <ol>
         *   <li>首先检查配置列表中的自定义配置</li>
         *   <li>然后检查原版维度的内置默认配置</li>
         *   <li>最后返回通用默认配置</li>
         * </ol>
         *
         * @param dimensionPath 维度路径（如 "the_nether" 或 "minecraft:the_nether"）
         * @return DimensionScanConfig 对象
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
                return new DimensionScanConfig("minecraft:the_nether", ScanMode.CAVE, 63,
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
                return new DimensionScanConfig("minecraft:overworld", ScanMode.SURFACE, 63,
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
                return new DimensionScanConfig("minecraft:the_end", ScanMode.SURFACE, 63,
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
            // 未匹配则返回默认配置（维度类型信息自动推断）
            DimensionTypeInfo inferredDimType = DimensionTypeInfo.fromDimensionId(dimensionPath);
            return new DimensionScanConfig(dimensionPath, defaultScanMode.get(), defaultCaveStart.get(), inferredDimType);
        }
    }
}
