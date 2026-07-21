package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.UpdateMode;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.ScanMode;

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
 * <p>使用 Forge 的 ForgeConfigSpec 进行配置管理</p>
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
    public static final ForgeConfigSpec CLIENT_SPEC;

    /**
     * 客户端配置实例
     */
    public static final ClientConfig CLIENT;

    /**
     * 服务端配置规范对象
     */
    public static final ForgeConfigSpec SERVER_SPEC;

    /**
     * 服务端配置实例
     */
    public static final ServerConfig SERVER;

    /**
     * 获取原版维度的默认配置（系统预设）
     *
     * <p>使用字符串格式避免 NightConfig 序列化问题</p>
     * <p>推荐格式：{@code dimension = layerPlan}（layerPlan 为 SURFACE / ALL / Y 或其组合）</p>
     * <p>例如：{@code minecraft:the_nether = SURFACE,63}</p>
     * <p>旧管道格式 {@code dimension|layerPlan} 仍可读取</p>
     *
     * @return 默认维度配置字符串列表
     */
    private static List<String> getDefaultDimensionConfigStrings() {
                return DimensionConfigParser.getDefaultDimensionConfigStrings();
    }

    /**
     * 初始化配置的静态代码块
     */
    static {
        var clientPair = new ForgeConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();

        var serverPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    public static void saveClientConfig() {
        CLIENT_SPEC.save();
    }

    /** 从磁盘重新加载服务端 TOML 配置 */
    public static void bindServerConfig(net.minecraftforge.fml.config.ModConfig config) {
        if (config.getType() == net.minecraftforge.fml.config.ModConfig.Type.SERVER) {
            boundServerConfig = config;
        }
    }

    public static void reloadServerFromDisk() {
        if (boundServerConfig != null) {
            Path path = boundServerConfig.getFullPath();
            CommentedFileConfig disk = CommentedFileConfig.of(path);
            disk.load();
            try {
                SERVER_SPEC.acceptConfig(disk);
            } finally {
                disk.close();
            }
        }
        DimensionConfigParser.invalidateCache();
    }

    private static volatile net.minecraftforge.fml.config.ModConfig boundServerConfig;

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

        public final IntValue mapRegionLoadIntervalTicks;

        public final BooleanValue autoSyncEnabled;

        /**
         * 构造客户端配置
         *
         * <p>定义所有配置选项及其默认值、范围和注释</p>
         *
         * @param builder ForgeConfigSpec 构建器
         */
        public ClientConfig(ForgeConfigSpec.Builder builder) {
            builder.push("client");
            builder.comment("客户端设置 / Client settings");

            // 计算默认线程数：可用处理器数的一半，最少 1 个
            int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            int maxThreads = Runtime.getRuntime().availableProcessors();

            hashThreads = builder
                    .comment("哈希计算线程数（用于地图同步时的并行计算）",
                             "Number of threads for hash computation during map sync",
                             "",
                             "默认使用可用处理器数的一半，避免阻塞游戏主线程",
                             "Default uses half of available processors to avoid blocking game main thread",
                             "",
                             "线程数选择建议：",
                             "  1-2 核：使用 1 线程",
                             "  4 核：使用 2 线程（大多数配置的默认值）",
                             "  8+ 核：使用 4-8 线程加快同步速度",
                             "Thread count recommendations:",
                             "  1-2 cores: use 1 thread",
                             "  4 cores: use 2 threads (default for most setups)",
                             "  8+ cores: use 4-8 threads for faster sync",
                             "",
                             "默认：" + defaultThreads + "（可用 " + maxThreads + " 个处理器的一半）",
                             "Default: " + defaultThreads + " (half of " + maxThreads + " available processors)",
                             "范围：1 - " + maxThreads,
                             "Range: 1 - " + maxThreads)
                    .defineInRange("hashThreads", defaultThreads, 1, maxThreads);

            mapRegionLoadIntervalTicks = builder
                    .comment("视距外 region 传入 Xaero 的客户端 tick 间隔（1 = 每 tick 一个）。",
                             "Tick interval between loading each out-of-view region into Xaero (1 = every tick).",
                             "",
                             "  -1 = 不限制（一次排空）",
                             "  0  = 仅加载视距内",
                             "  1-100 = 每 N tick 加载 1 个（默认：1）",
                             "  -1 = Unlimited (drain all at once)",
                             "  0  = View-distance only",
                             "  1-100 = one region every N ticks (default: 1)")
                    .defineInRange("mapRegionLoadIntervalTicks", 1, -1, 100);

            autoSyncEnabled = builder
                    .comment("服务端 TICK/SCHEDULED 模式下启用进服自动同步；TICK 模式另启在线周期同步",
                             "Enable join auto-sync when server uses TICK or SCHEDULED; online periodic sync when TICK",
                             "手动 /mapsyncer sync 始终可用",
                             "Manual /mapsyncer sync is always available")
                    .define("autoSyncEnabled", true);

            builder.pop();
        }

        public int getHashThreads() {
            return hashThreads.get();
        }

        public int getMapRegionLoadIntervalTicks() {
            return mapRegionLoadIntervalTicks.get();
        }

        public boolean isAutoSyncEnabled() {
            return autoSyncEnabled.get();
        }

        public void setAutoSyncEnabled(boolean enabled) {
            autoSyncEnabled.set(enabled);
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
         * @param builder ForgeConfigSpec 构建器
         */
        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            builder.comment("通用设置 / General settings");

            enableDebugLogging = builder
                    .comment("启用调试日志记录（用于地图生成过程调试）",
                             "Enable debug logging for map generation")
                    .define("enableDebugLogging", false);
            maxConcurrentRegions = builder
                    .comment("同时转换的最大区域数；0 = 自动（逻辑处理器数 - 2，最小 1，最大 16）",
                             "Max regions to convert concurrently; 0 = auto (logical CPUs - 2, min 1, max 16)")
                    .defineInRange("maxConcurrentRegions", 0, 0, 16);
            maxSyncPacketSize = builder
                    .comment("同步数据包最大字节数",
                             "Maximum sync packet size in bytes",
                             "",
                             "大小选项供快速参考（均能被 1024KB/s 整除）：",
                             "  65536  = 64KB  （保守，1024KB/s 时每秒 16 包）",
                             "  131072 = 128KB （平衡，1024KB/s 时每秒 8 包）",
                             "  262144 = 256KB （推荐，1024KB/s 时每秒 4 包）",
                             "  524288 = 512KB （高效，1024KB/s 时每秒 2 包）",
                             "  1048576 = 1MB  （最大，1024KB/s 时每秒 1 包）",
                             "默认：256KB（推荐），范围：64KB - 1MB",
                             "",
                             "Size options for quick reference (all divide 1024KB/s evenly):",
                             "  65536  = 64KB  (conservative, 16 packets/s at 1024KB/s)",
                             "  131072 = 128KB (balanced, 8 packets/s at 1024KB/s)",
                             "  262144 = 256KB (recommended, 4 packets/s at 1024KB/s)",
                             "  524288 = 512KB (efficient, 2 packets/s at 1024KB/s)",
                             "  1048576 = 1MB  (maximum, 1 packet/s at 1024KB/s)",
                             "Default: 256KB (recommended), Range: 64KB - 1MB")
                    .defineInRange("maxSyncPacketSize", 262144, 65536, 1048576);
            syncSpeedLimitKBps = builder
                    .comment("同步速度限制 KB/s（0 = 无限制）",
                             "Sync speed limit in KB/s (0 = unlimited)",
                             "",
                             "速度选项供快速参考：",
                             "  100  = 100KB/s  （慢速，适合带宽受限）",
                             "  512  = 512KB/s  （中等，半 MiB）",
                             "  1024 = 1024KB/s = 1MiB/s （默认，推荐）",
                             "  5120 = 5120KB/s = 5MiB/s （快速，适合局域网）",
                             "  10240 = 10240KB/s = 10MiB/s （非常快）",
                             "",
                             "Speed options for quick reference:",
                             "  100  = 100KB/s  (slow, suitable for limited bandwidth)",
                             "  512  = 512KB/s  (moderate, half MiB)",
                             "  1024 = 1024KB/s = 1MiB/s (default, recommended)",
                             "  5120 = 5120KB/s = 5MiB/s (fast, suitable for LAN)",
                             "  10240 = 10240KB/s = 10MiB/s (very fast)",
                             "",
                             "默认：1024（1MiB/s），范围：0 - 10240",
                             "Default: 1024 (1MiB/s), Range: 0 - 10240")
                    .defineInRange("syncSpeedLimitKBps", 1024, 0, 10240);

            builder.pop();

            builder.push("incremental_update");
            builder.comment("增量更新设置 / Incremental update settings");

            incrementalUpdateMode = builder
                    .comment("增量更新模式：DISABLED（禁用），TICK（按 tick 周期更新），SCHEDULED（每日定时更新）",
                             "Incremental update mode: DISABLED (off), TICK (periodic by ticks), SCHEDULED (daily at specific time)")
                    .defineEnum("incrementalUpdateMode", UpdateMode.DISABLED);

            incrementalUpdateIntervalTicks = builder
                    .comment("TICK 模式的更新间隔（20 ticks = 1 秒，默认 6000 = 5 分钟）",
                             "Interval in server ticks for TICK mode (20 ticks = 1 second, default 6000 = 5 minutes)")
                    .defineInRange("incrementalUpdateIntervalTicks", 6000, 2400, 72000);

            scheduledUpdateHour = builder
                    .comment("SCHEDULED 模式的更新时间（小时，0-23，使用服务器本地时区）",
                             "Hour of day for SCHEDULED mode (0-23, uses server's local timezone)")
                    .defineInRange("scheduledUpdateHour", 4, 0, 23);

            scheduledUpdateMinute = builder
                    .comment("SCHEDULED 模式的更新时间（分钟，0-59）",
                             "Minute of hour for SCHEDULED mode (0-59)")
                    .defineInRange("scheduledUpdateMinute", 0, 0, 59);

            builder.pop();

            builder.push("dimension_scan");
            builder.comment("维度扫描设置 / Dimension scan settings");

            defaultScanMode = builder
                    .comment("未在 dimension_configs 中的维度的默认层计划（SURFACE=仅地表，CAVE=单层洞穴）",
                             "Default layer plan fallback for dimensions not in dimension_configs",
                             "SURFACE = surface only; CAVE = single cave layer at default_cave_start")
                    .defineEnum("default_scan_mode", ScanMode.SURFACE);

            defaultCaveStart = builder
                    .comment("default_scan_mode=CAVE 时的 caveStart Y（对应 caves(Y) 层计划）",
                             "Cave start Y when default_scan_mode=CAVE (maps to layerPlan caves(Y))")
                    .defineInRange("default_cave_start", 63, -512, 512);

            dimensionConfigs = builder
                    .comment("维度扫描配置列表（每个维度一条字符串）",
                             "推荐：\"dimension = layerPlan\"",
                             "layerPlan：SURFACE、ALL、显式 Y（如 63）或组合（如 SURFACE,63）",
                             "示例：\"minecraft:the_nether = SURFACE,63\"",
                             "旧格式 \"dimension|layerPlan\" / \"dimension|SURFACE|63|…\" 仍可读取",
                             "Per-dimension scan configuration list (one string per dimension)",
                             "Preferred: \"dimension = layerPlan\"",
                             "layerPlan: SURFACE, ALL, explicit Y (e.g. 63), or combos (e.g. SURFACE,63)",
                             "Example: \"minecraft:the_nether = SURFACE,63\"",
                             "Legacy \"dimension|layerPlan\" and \"dimension|SURFACE|63|…\" still accepted")
                    .defineList("dimension_configs", getDefaultDimensionConfigStrings(),
                        obj -> obj instanceof String);

            builder.pop();
        }

        /**
         * 解析维度配置列表
         *
         * <p>将字符串格式的配置转换为 DimensionScanConfig 对象列表</p>
         * <p>字符串格式："dimension|layerPlan|dim_type_info"</p>
         *
         * @return DimensionScanConfig 对象列表
         */
        public List<DimensionScanConfig> parseDimensionConfigs() {
                    return DimensionConfigParser.parseDimensionConfigs(dimensionConfigs.get());
        }

        /**
         * 解析单个配置字符串
         *
         * <p>格式："dimension|layerPlan|dim_type_info"</p>
         * <p>旧格式："dimension|scan_mode|cave_start|dim_type_info" 仍可读取并合并为 layerPlan</p>
         * <p>dim_type_info："hasSkylight|hasCeiling|minY|height|logicalHeight"</p>
         *
         * @param configStr 配置字符串
         * @return DimensionScanConfig 对象，如果无效则返回 null
         */
        private DimensionScanConfig parseConfigString(String configStr) {
            return DimensionConfigParser.parseConfigString(configStr);
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
            return DimensionConfigParser.getConfigForDimension(
                dimensionPath, dimensionConfigs.get(), defaultScanMode.get(), defaultCaveStart.get());
        }
    }
}
