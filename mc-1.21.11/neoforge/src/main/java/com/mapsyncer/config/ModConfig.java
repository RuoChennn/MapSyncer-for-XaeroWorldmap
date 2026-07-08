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
        return DimensionConfigParser.getDefaultDimensionConfigStrings();
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
         * 客户端同步模式
         */
        public final EnumValue<ClientSyncMode> clientSyncMode;

        /**
         * 后台元数据巡检间隔（分钟）
         */
        public final IntValue backgroundSyncIntervalMinutes;

        /**
         * 正常断开连接前是否尝试贡献本地地图。
         *
         * <p>仅对 BIDIRECTIONAL 客户端生效，无法保护崩溃、强制关闭进程或网络中断。</p>
         */
        public final BooleanValue syncBeforeDisconnect;

        /**
         * 退出前贡献同步等待的最大秒数。
         *
         * <p>0 表示禁用退出前等待（等同关闭该能力）。范围 0 - 60。</p>
         */
        public final IntValue disconnectSyncTimeoutSeconds;

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

            clientSyncMode = builder
                    .comment("Client sync mode.",
                             "客户端同步模式。",
                             "DISABLED disables automatic sync, background checks, manual receive sync, and upload contributions on this client.",
                             "DISABLED 会禁用此客户端的自动同步、后台巡检、手动接收同步和上传贡献。",
                             "RECEIVE_ONLY receives newer authoritative regions from the server but never uploads local regions.",
                             "RECEIVE_ONLY 只接收服务端较新的权威 region，不上传本地 region。",
                             "BIDIRECTIONAL receives server updates and uploads newer local regions when the server allows contributions.",
                             "BIDIRECTIONAL 会接收服务端更新，并在服务端允许时上传本地较新的 region。",
                             "Allowed values: DISABLED, RECEIVE_ONLY, BIDIRECTIONAL",
                             "可选值：DISABLED、RECEIVE_ONLY、BIDIRECTIONAL",
                             "Default: RECEIVE_ONLY. This is safe for public servers because clients do not contribute unless they opt in.",
                             "默认：RECEIVE_ONLY。这个默认值适合公开服务器，因为客户端不会在未主动开启时贡献数据。")
                    .defineEnum("clientSyncMode", ClientSyncMode.RECEIVE_ONLY);

            backgroundSyncIntervalMinutes = builder
                    .comment("Background metadata check interval in minutes.",
                             "后台元数据巡检间隔（分钟）。",
                             "0 disables periodic checks. Positive values periodically run metadata negotiation.",
                             "0 表示关闭周期巡检；正数表示周期执行元数据协商流程。",
                             "Default: 60 minutes to keep maps fresh without frequent network checks.",
                             "默认：60 分钟，在保持地图新鲜的同时避免过于频繁的网络检查。",
                             "Range: 0 - 1440",
                             "范围：0 - 1440")
                    .defineInRange("backgroundSyncIntervalMinutes", 60, 0, 1440);

            syncBeforeDisconnect = builder
                    .comment("Whether the client should try to upload local Xaero map contributions before a normal disconnect.",
                             "正常断开连接前是否尝试上传本地 Xaero 地图贡献。",
                             "This only runs for BIDIRECTIONAL clients and cannot protect crashes, force closes, or network loss.",
                             "仅对 BIDIRECTIONAL 客户端生效，无法保护崩溃、强制关闭进程或网络中断等异常退出。",
                             "Default: true",
                             "默认：开启")
                    .define("syncBeforeDisconnect", true);

            disconnectSyncTimeoutSeconds = builder
                    .comment("Maximum seconds to wait on the pre-disconnect contribution screen.",
                             "退出前贡献同步等待界面的最大秒数。",
                             "Set to 0 to disable the waiting flow (equivalent to syncBeforeDisconnect=false).",
                             "设为 0 会禁用退出前等待（等同于 syncBeforeDisconnect=false）。",
                             "Default: 15 seconds, Range: 0 - 60",
                             "默认：15 秒，范围：0 - 60")
                    .defineInRange("disconnectSyncTimeoutSeconds", 15, 0, 60);

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

        // ========== 客户端贡献设置 ==========

        /**
         * 服务端接受客户端贡献的权限范围
         */
        public final EnumValue<ContributionScope> contributionScope;

        /**
         * 每个贡献会话完成后的冷却期（秒）
         */
        public final IntValue contributionQueueCooldownSeconds;

        /**
         * 最大贡献会话排队数量
         */
        public final IntValue maxContributionQueueSize;

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


            builder.push("contribution");
            builder.comment("Client contribution settings / 客户端贡献设置");

            contributionScope = builder
                    .comment("Server contribution permission scope.",
                             "服务端接受客户端贡献的权限范围。",
                             "DISABLED refuses all client uploads.",
                             "DISABLED 拒绝所有客户端上传。",
                             "OPS allows server operators to contribute.",
                             "OPS 允许服务器管理员贡献。",
                             "WHITELIST allows UUIDs listed in <world>/serverconfig/mapsyncer-contributors.json.",
                             "WHITELIST 允许 <world>/serverconfig/mapsyncer-contributors.json 中记录的 UUID 贡献。",
                             "OPS_AND_WHITELIST allows either operators or whitelisted UUIDs.",
                             "OPS_AND_WHITELIST 允许管理员或白名单 UUID 贡献。",
                             "ALL allows every player to contribute. Use only on trusted servers.",
                             "ALL 允许所有玩家贡献。仅建议在可信服务器使用。",
                             "Allowed values: DISABLED, OPS, WHITELIST, OPS_AND_WHITELIST, ALL",
                             "可选值：DISABLED、OPS、WHITELIST、OPS_AND_WHITELIST、ALL",
                             "Default: WHITELIST. This keeps contribution opt-in and world-specific.",
                             "默认：WHITELIST。该默认值使贡献保持显式授权且按世界隔离。")
                    .defineEnum("contributionScope", ContributionScope.WHITELIST);

            contributionQueueCooldownSeconds = builder
                    .comment("Cooldown in seconds between completed contribution sessions.",
                             "每个贡献会话完成后的冷却期（秒）。",
                             "This serializes bursts from multiple players and reduces cache write contention.",
                             "用于串行化多玩家同时贡献，降低缓存写入竞争。",
                             "Default: 10 seconds, Range: 0 - 3600",
                             "默认：10 秒，范围：0 - 3600")
                    .defineInRange("contributionQueueCooldownSeconds", 10, 0, 3600);

            maxContributionQueueSize = builder
                    .comment("Maximum number of queued contribution sessions.",
                             "最大贡献会话排队数量。",
                             "New contribution requests are rejected with queue_full when this limit is reached.",
                             "达到该限制后，新贡献请求会以 queue_full 拒绝。",
                             "Default: 32 queued sessions, Range: 1 - 1024",
                             "默认：32 个排队会话，范围：1 - 1024")
                    .defineInRange("maxContributionQueueSize", 32, 1, 1024);

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
            return DimensionConfigParser.parseDimensionConfigs(dimensionConfigs.get());
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
