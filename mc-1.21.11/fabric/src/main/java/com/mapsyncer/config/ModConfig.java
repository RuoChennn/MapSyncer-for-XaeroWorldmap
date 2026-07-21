package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.util.PropertiesHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Properties;

import com.mapsyncer.config.DimensionConfigParser;
import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.ScanMode;

/**
 * Mod 配置类 - Fabric 版本
 *
 * 使用 Cloth Config API 进行配置管理，配置文件使用 Properties 格式存储。
 *
 * <p>管理 MapSyncer for XaeroWorldMap 的配置，包括:</p>
 * <ul>
 *   <li>客户端设置（哈希计算线程数等）</li>
 *   <li>服务器端设置（调试日志、并发限制等）</li>
 *   <li>增量更新设置（更新模式、时间间隔）</li>
 *   <li>维度扫描配置（扫描模式、起始高度等）</li>
 * </ul>
 */
public class ModConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConfig.class);

    /** 服务端配置文件名 */
    private static final String SERVER_CONFIG_FILE_NAME = "mapsyncer-server.properties";

    /** 客户端配置文件名 */
    private static final String CLIENT_CONFIG_FILE_NAME = "mapsyncer-client.properties";

    /** 服务端配置单例实例 */
    private static volatile ServerConfig serverInstance;

    /** 客户端配置单例实例 */
    private static volatile ClientConfig clientInstance;

    /** 服务端配置文件路径 */
    private static volatile Path serverConfigPath;

    /** 客户端配置文件路径 */
    private static volatile Path clientConfigPath;

    /**
     * 获取服务端配置实例
     *
     * @param configDir 配置目录路径（通常是世界目录下的 serverconfig 目录）
     * @return 服务端配置实例
     */
    public static ServerConfig getServerConfig(Path configDir) {
        if (serverInstance == null) {
            synchronized (ServerConfig.class) {
                if (serverInstance == null) {
                    serverConfigPath = configDir.resolve(SERVER_CONFIG_FILE_NAME);
                    serverInstance = new ServerConfig(serverConfigPath);
                    LOGGER.info("ServerConfig initialized with path: {}", serverConfigPath);
                }
            }
        }
        return serverInstance;
    }

    /**
     * 获取客户端配置实例
     *
     * @param configDir 配置目录路径（通常是游戏目录下的 config 目录）
     * @return 客户端配置实例
     */
    public static ClientConfig getClientConfig(Path configDir) {
        if (clientInstance == null) {
            synchronized (ClientConfig.class) {
                if (clientInstance == null) {
                    clientConfigPath = configDir.resolve(CLIENT_CONFIG_FILE_NAME);
                    clientInstance = new ClientConfig(clientConfigPath);
                    LOGGER.info("ClientConfig initialized with path: {}", clientConfigPath);
                }
            }
        }
        return clientInstance;
    }

    /**
     * 重置配置实例（用于测试或服务器重启）
     */
    public static void resetInstance() {
        if (serverInstance != null) {
            serverInstance = null;
            serverConfigPath = null;
            LOGGER.info("ServerConfig instance reset");
        }
        if (clientInstance != null) {
            clientInstance = null;
            clientConfigPath = null;
            LOGGER.info("ClientConfig instance reset");
        }
    }

    /**
     * 获取当前服务端配置实例
     */
    public static ServerConfig SERVER() {
        if (serverInstance == null) {
            throw new IllegalStateException("ServerConfig not initialized. Call getServerConfig() first.");
        }
        return serverInstance;
    }

    /**
     * 获取当前客户端配置实例
     */
    public static ClientConfig CLIENT() {
        if (clientInstance == null) {
            throw new IllegalStateException("ClientConfig not initialized. Call getClientConfig() first.");
        }
        return clientInstance;
    }

    /**
     * 客户端配置类
     *
     * 使用 Properties 格式存储配置，支持 Cloth Config GUI。
     */
    public static class ClientConfig {

        /**
         * 哈希计算线程数
         *
         * <p>用于 ClientHashManager 的 ForkJoinPool 并行计算区域文件哈希。</p>
         * <p>默认值使用 JVM 可用处理器数的一半，避免阻塞游戏主线程。</p>
         */
        private volatile int hashThreads;

        /**
         * 视距外 region 加载 tick 间隔：-1=一次排空，0=仅视距内，1-100=每 N tick 加载 1 个。
         */
        private volatile int mapRegionLoadIntervalTicks = 1;

        /** 客户端自动同步（进服：TICK/SCHEDULED；在线周期：仅 TICK） */
        private volatile boolean autoSyncEnabled = true;

        /** 配置文件路径 */
        private final Path configFile;

        /**
         * 构造客户端配置
         *
         * @param configFile 配置文件路径
         */
        public ClientConfig(Path configFile) {
            this.configFile = configFile;
            // 计算默认线程数：可用处理器数的一半，最少 1 个
            int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            this.hashThreads = defaultThreads;
            load();
            // 首次启动时自动生成默认配置文件
            if (!Files.exists(configFile)) {
                save();
            }
        }

        /**
         * 从文件加载配置
         */
        private void load() {
            if (!Files.exists(configFile)) {
                LOGGER.info("Client config file not found, using defaults (hashThreads={})", hashThreads);
                return;
            }

            try (InputStream is = Files.newInputStream(configFile)) {
                Properties props = new Properties();
                props.load(is);

                int maxThreads = Runtime.getRuntime().availableProcessors();
                int loadedThreads = Integer.parseInt(props.getProperty("hashThreads", String.valueOf(hashThreads)));
                // 确保线程数在有效范围内
                hashThreads = Math.max(1, Math.min(maxThreads, loadedThreads));

                String intervalProp = props.getProperty("mapRegionLoadIntervalTicks");
                if (intervalProp == null) {
                    intervalProp = props.getProperty("mapRegionLoadsPerTick", "1");
                }
                int loadedInterval = Integer.parseInt(intervalProp);
                mapRegionLoadIntervalTicks = Math.max(-1, Math.min(100, loadedInterval));

                autoSyncEnabled = Boolean.parseBoolean(
                        props.getProperty("autoSyncEnabled", "true"));

                LOGGER.info("Loaded client config from: {} (hashThreads={}, mapRegionLoadIntervalTicks={}, autoSyncEnabled={})", configFile, hashThreads, mapRegionLoadIntervalTicks, autoSyncEnabled);
            } catch (Exception e) {
                LOGGER.warn("Failed to load client config, using defaults: {}", e.getMessage());
            }
        }

        /**
         * 保存配置到文件
         */
        public void save() {
            try {
                Files.createDirectories(configFile.getParent());

                int maxThreads = Runtime.getRuntime().availableProcessors();

                StringBuilder sb = new StringBuilder();
                sb.append("# MapSyncer Client Configuration\n");
                sb.append("#\n");
                sb.append("# 客户端设置 / Client settings\n");
                sb.append("#\n");
                sb.append("# 哈希计算线程数（用于地图同步时的并行计算）\n");
                sb.append("# Number of threads for hash computation during map sync\n");
                sb.append("#\n");
                sb.append("# 默认使用可用处理器数的一半，避免阻塞游戏主线程\n");
                sb.append("# Default uses half of available processors to avoid blocking game main thread\n");
                sb.append("#\n");
                sb.append("# 线程数选择建议：\n");
                sb.append("#   1-2 核：使用 1 线程\n");
                sb.append("#   4 核：使用 2 线程（大多数配置的默认值）\n");
                sb.append("#   8+ 核：使用 4-8 线程加快同步速度\n");
                sb.append("# Thread count recommendations:\n");
                sb.append("#   1-2 cores: use 1 thread\n");
                sb.append("#   4 cores: use 2 threads (default for most setups)\n");
                sb.append("#   8+ cores: use 4-8 threads for faster sync\n");
                sb.append("#\n");
                sb.append("# 默认：" + hashThreads + "（可用 " + maxThreads + " 个处理器的一半）\n");
                sb.append("# Default: " + hashThreads + " (half of " + maxThreads + " available processors)\n");
                sb.append("# 范围：1 - " + maxThreads + "\n");
                sb.append("# Range: 1 - " + maxThreads + "\n");
                sb.append("hashThreads=" + hashThreads + "\n");
                sb.append("#\n");
                sb.append("# 视距外 region 传入 Xaero 的客户端 tick 间隔（1 = 每 tick 一个）\n");
                sb.append("# Tick interval between loading each out-of-view region into Xaero (1 = every tick)\n");
                sb.append("# 限制 Xaero MapProcessor 队列大小以防止内存溢出\n");
                sb.append("# Limits Xaero MapProcessor queue size to prevent OOM\n");
                sb.append("#\n");
                sb.append("#  -1 = 不限制（一次排空）\n");
                sb.append("#   0 = 仅加载视距内\n");
                sb.append("# 1-100 = 每 N tick 加载 1 个（默认：1）\n");
                sb.append("#  -1 = Unlimited (drain all at once)\n");
                sb.append("#   0 = View-distance only\n");
                sb.append("# 1-100 = one region every N ticks (default: 1)\n");
                sb.append("# 加载时仍兼容旧键名 mapRegionLoadsPerTick\n");
                sb.append("# Legacy key mapRegionLoadsPerTick is still accepted on load\n");
                sb.append("mapRegionLoadIntervalTicks=" + mapRegionLoadIntervalTicks + "\n");
                sb.append("#\n");
                sb.append("# 启用进服自动同步（TICK/SCHEDULED）与 TICK 在线周期同步\n");
                sb.append("# Enable join auto-sync (TICK/SCHEDULED) and online periodic sync (TICK only)\n");
                sb.append("# 手动 /mapsyncer sync 始终可用\n");
                sb.append("# Manual /mapsyncer sync is always available\n");
                sb.append("autoSyncEnabled=" + autoSyncEnabled + "\n");

                Files.writeString(configFile, sb.toString());

                LOGGER.info("Saved client config to: {} (hashThreads={}, mapRegionLoadIntervalTicks={}, autoSyncEnabled={})", configFile, hashThreads, mapRegionLoadIntervalTicks, autoSyncEnabled);
            } catch (Exception e) {
                LOGGER.error("Failed to save client config: {}", e.getMessage());
            }
        }

        /**
         * 获取哈希计算线程数
         *
         * @return 配置的线程数
         */
        public int getHashThreads() {
            return hashThreads;
        }

        public int getMapRegionLoadIntervalTicks() {
            return mapRegionLoadIntervalTicks;
        }

        /**
         * 设置哈希计算线程数
         *
         * @param value 线程数
         */
        public void setHashThreads(int value) {
            int maxThreads = Runtime.getRuntime().availableProcessors();
            hashThreads = Math.max(1, Math.min(maxThreads, value));
        }

        public boolean isAutoSyncEnabled() {
            return autoSyncEnabled;
        }

        public void setAutoSyncEnabled(boolean enabled) {
            autoSyncEnabled = enabled;
        }

        public void setMapRegionLoadIntervalTicks(int value) {
            mapRegionLoadIntervalTicks = Math.max(-1, Math.min(100, value));
        }
    }

    /**
     * 服务端配置类
     *
     * 使用 Properties 格式存储配置，支持 Cloth Config GUI。
     */
    public static class ServerConfig {

        // ========== 通用设置 ==========
        private volatile boolean enableDebugLogging = false;
        private volatile int maxConcurrentRegions = ConcurrentRegionsConfig.AUTO;
        private volatile int maxSyncPacketSize = 262144;
        private volatile int syncSpeedLimitKBps = 1024;

        // ========== 增量更新设置 ==========
        private volatile UpdateMode incrementalUpdateMode = UpdateMode.DISABLED;
        private volatile int incrementalUpdateIntervalTicks = 6000;
        private volatile int scheduledUpdateHour = 4;
        private volatile int scheduledUpdateMinute = 0;

        // ========== 维度扫描配置 ==========
        private volatile ScanMode defaultScanMode = ScanMode.SURFACE;
        private volatile int defaultCaveStart = 63;
        private volatile List<String> dimensionConfigs = new ArrayList<>();

        /** 配置文件路径 */
        private final Path configFile;

        /**
         * 构造服务端配置
         *
         * @param configFile 配置文件路径
         */
        public ServerConfig(Path configFile) {
            this.configFile = configFile;
            load();
            // 初始化默认维度配置
            if (dimensionConfigs.isEmpty()) {
                dimensionConfigs = getDefaultDimensionConfigStrings();
            }
            // 首次启动时自动生成默认配置文件
            if (!Files.exists(configFile)) {
                save();
            }
        }

        /**
         * 获取原版维度的默认配置
         */
        private List<String> getDefaultDimensionConfigStrings() {
            return DimensionConfigParser.getDefaultDimensionConfigStrings();
        }

        /**
         * 从文件加载配置
         */
        private void load() {
            if (!Files.exists(configFile)) {
                LOGGER.info("Config file not found, using defaults");
                return;
            }

            try {
                String fileText = Files.readString(configFile);
                String propsText = DimensionConfigParser.stripDimensionConfigsListBlock(fileText);
                Properties props = new Properties();
                try (StringReader reader = new StringReader(propsText)) {
                    props.load(reader);
                }

                // 通用设置
                enableDebugLogging = Boolean.parseBoolean(
                        PropertiesHelper.get(props, "enableDebugLogging", "enable_debug_logging", "false"));
                maxConcurrentRegions = ConcurrentRegionsConfig.clampConfigured(Integer.parseInt(
                        PropertiesHelper.get(props, "maxConcurrentRegions", "max_concurrent_regions", "0")));
                maxSyncPacketSize = Integer.parseInt(
                        PropertiesHelper.get(props, "maxSyncPacketSize", "max_sync_packet_size", "262144"));
                syncSpeedLimitKBps = Integer.parseInt(
                        PropertiesHelper.get(props, "syncSpeedLimitKBps", "sync_speed_limit_kbps", "1024"));

                incrementalUpdateMode = UpdateMode.valueOf(
                        PropertiesHelper.get(props, "incrementalUpdateMode", "incremental_update_mode", "DISABLED"));
                incrementalUpdateIntervalTicks = Integer.parseInt(
                        PropertiesHelper.get(props, "incrementalUpdateIntervalTicks", "incremental_update_interval_ticks", "6000"));
                scheduledUpdateHour = Integer.parseInt(
                        PropertiesHelper.get(props, "scheduledUpdateHour", "scheduled_update_hour", "4"));
                scheduledUpdateMinute = Integer.parseInt(
                        PropertiesHelper.get(props, "scheduledUpdateMinute", "scheduled_update_minute", "0"));

                defaultScanMode = ScanMode.valueOf(
                        PropertiesHelper.get(props, "defaultScanMode", "default_scan_mode", "SURFACE"));
                defaultCaveStart = Integer.parseInt(
                        PropertiesHelper.get(props, "defaultCaveStart", "default_cave_start", "63"));

                dimensionConfigs.clear();
                dimensionConfigs.addAll(DimensionConfigParser.loadDimensionConfigEntries(fileText, props));

                LOGGER.info("Loaded config from: {}", configFile);
            } catch (Exception e) {
                LOGGER.warn("Failed to load config, using defaults: {}", e.getMessage());
            }
        }

        /**
         * 从磁盘重新加载配置。
         */
        public void reload() {
            load();
            DimensionConfigParser.invalidateCache();
        }

        /**
         * 保存配置到文件
         */
        public void save() {
            try {
                Files.createDirectories(configFile.getParent());

                StringBuilder sb = new StringBuilder();
                sb.append("# MapSyncer Server Configuration\n");
                sb.append("#\n");

                // ========== 通用设置 ==========
                sb.append("# ========================================\n");
                sb.append("# 通用设置 / General settings\n");
                sb.append("# ========================================\n");
                sb.append("#\n");
                sb.append("# 启用调试日志记录（用于地图生成过程调试）\n");
                sb.append("# Enable debug logging for map generation\n");
                sb.append("enableDebugLogging=" + enableDebugLogging + "\n");
                sb.append("\n");
                sb.append("# 同时转换区域数；0 = 自动（逻辑处理器数 - 2，最小 1，最大 16）\n");
                sb.append("# Max concurrent region conversions; 0 = auto (logical CPUs - 2, min 1, max 16)\n");
                sb.append("# 范围：0 - 16（0=自动） / Range: 0 - 16 (0=auto)\n");
                sb.append("maxConcurrentRegions=" + maxConcurrentRegions + "\n");
                sb.append("\n");
                sb.append("# 同步数据包最大字节数\n");
                sb.append("# Maximum sync packet size in bytes\n");
                sb.append("#\n");
                sb.append("# 大小选项供快速参考（均能被 1024KB/s 整除）：\n");
                sb.append("#   65536  = 64KB  （保守，1024KB/s 时每秒 16 包）\n");
                sb.append("#   131072 = 128KB （平衡，1024KB/s 时每秒 8 包）\n");
                sb.append("#   262144 = 256KB （推荐，1024KB/s 时每秒 4 包）\n");
                sb.append("#   524288 = 512KB （高效，1024KB/s 时每秒 2 包）\n");
                sb.append("#   1048576 = 1MB  （最大，1024KB/s 时每秒 1 包）\n");
                sb.append("# 默认：256KB（推荐），范围：64KB - 1MB\n");
                sb.append("#\n");
                sb.append("# Size options for quick reference (all divide 1024KB/s evenly):\n");
                sb.append("#   65536  = 64KB  (conservative, 16 packets/s at 1024KB/s)\n");
                sb.append("#   131072 = 128KB (balanced, 8 packets/s at 1024KB/s)\n");
                sb.append("#   262144 = 256KB (recommended, 4 packets/s at 1024KB/s)\n");
                sb.append("#   524288 = 512KB (efficient, 2 packets/s at 1024KB/s)\n");
                sb.append("#   1048576 = 1MB  (maximum, 1 packet/s at 1024KB/s)\n");
                sb.append("# Default: 256KB (recommended), Range: 64KB - 1MB\n");
                sb.append("maxSyncPacketSize=" + maxSyncPacketSize + "\n");
                sb.append("\n");
                sb.append("# 同步速度限制 KB/s（0 = 无限制）\n");
                sb.append("# Sync speed limit in KB/s (0 = unlimited)\n");
                sb.append("#\n");
                sb.append("# 速度选项供快速参考：\n");
                sb.append("#   100  = 100KB/s  （慢速，适合带宽受限）\n");
                sb.append("#   512  = 512KB/s  （中等，半 MiB）\n");
                sb.append("#   1024 = 1024KB/s = 1MiB/s （默认，推荐）\n");
                sb.append("#   5120 = 5120KB/s = 5MiB/s （快速，适合局域网）\n");
                sb.append("#   10240 = 10240KB/s = 10MiB/s （非常快）\n");
                sb.append("#\n");
                sb.append("# Speed options for quick reference:\n");
                sb.append("#   100  = 100KB/s  (slow, suitable for limited bandwidth)\n");
                sb.append("#   512  = 512KB/s  (moderate, half MiB)\n");
                sb.append("#   1024 = 1024KB/s = 1MiB/s (default, recommended)\n");
                sb.append("#   5120 = 5120KB/s = 5MiB/s (fast, suitable for LAN)\n");
                sb.append("#   10240 = 10240KB/s = 10MiB/s (very fast)\n");
                sb.append("#\n");
                sb.append("# 默认：1024（1MiB/s），范围：0 - 10240\n");
                sb.append("# Default: 1024 (1MiB/s), Range: 0 - 10240\n");
                sb.append("syncSpeedLimitKBps=" + syncSpeedLimitKBps + "\n");
                sb.append("\n");

                // ========== 增量更新设置 ==========
                sb.append("# ========================================\n");
                sb.append("# 增量更新设置 / Incremental update settings\n");
                sb.append("# ========================================\n");
                sb.append("#\n");
                sb.append("# 增量更新模式：DISABLED（禁用），TICK（按 tick 周期更新），SCHEDULED（每日定时更新）\n");
                sb.append("# Incremental update mode: DISABLED (off), TICK (periodic by ticks), SCHEDULED (daily at specific time)\n");
                sb.append("incrementalUpdateMode=" + incrementalUpdateMode.name() + "\n");
                sb.append("\n");
                sb.append("# TICK 模式的更新间隔（20 ticks = 1 秒，默认 6000 = 5 分钟）\n");
                sb.append("# Interval in server ticks for TICK mode (20 ticks = 1 second, default 6000 = 5 minutes)\n");
                sb.append("# 范围：2400 - 72000 / Range: 2400 - 72000\n");
                sb.append("incrementalUpdateIntervalTicks=" + incrementalUpdateIntervalTicks + "\n");
                sb.append("\n");
                sb.append("# SCHEDULED 模式的更新时间（小时，0-23，使用服务器本地时区）\n");
                sb.append("# Hour of day for SCHEDULED mode (0-23, uses server's local timezone)\n");
                sb.append("scheduledUpdateHour=" + scheduledUpdateHour + "\n");
                sb.append("\n");
                sb.append("# SCHEDULED 模式的更新时间（分钟，0-59）\n");
                sb.append("# Minute of hour for SCHEDULED mode (0-59)\n");
                sb.append("scheduledUpdateMinute=" + scheduledUpdateMinute + "\n");
                sb.append("\n");

                // ========== 维度扫描配置 ==========
                sb.append("# ========================================\n");
                sb.append("# 维度扫描设置 / Dimension scan settings\n");
                sb.append("# ========================================\n");
                sb.append("#\n");
                sb.append("# 未在 dimension_configs 中的维度的默认层计划（SURFACE=仅地表，CAVE=单层洞穴）\n");
                sb.append("# Default layer plan fallback for dimensions not in dimension_configs\n");
                sb.append("# SURFACE = surface only; CAVE = single cave layer at defaultCaveStart\n");
                sb.append("defaultScanMode=" + defaultScanMode.name() + "\n");
                sb.append("\n");
                sb.append("# defaultScanMode=CAVE 时的 caveStart Y（对应 caves(Y) 层计划）\n");
                sb.append("# Cave start Y when defaultScanMode=CAVE (maps to layerPlan caves(Y))\n");
                sb.append("# 范围：-512 - 512 / Range: -512 - 512\n");
                sb.append("defaultCaveStart=" + defaultCaveStart + "\n");
                sb.append("\n");
                DimensionConfigParser.appendEntriesToPropertiesFile(sb, dimensionConfigs);

                Files.writeString(configFile, sb.toString());
                DimensionConfigParser.invalidateCache();

                LOGGER.info("Saved config to: {}", configFile);
            } catch (Exception e) {
                LOGGER.error("Failed to save config: {}", e.getMessage());
            }
        }

        // ========== Getter 方法 ==========

        public boolean getEnableDebugLogging() {
            return enableDebugLogging;
        }

        public int getMaxConcurrentRegions() {
            return maxConcurrentRegions;
        }

        public int getMaxSyncPacketSize() {
            return maxSyncPacketSize;
        }

        public int getSyncSpeedLimitKBps() {
            return syncSpeedLimitKBps;
        }

        public UpdateMode getIncrementalUpdateMode() {
            return incrementalUpdateMode;
        }

        public int getIncrementalUpdateIntervalTicks() {
            return incrementalUpdateIntervalTicks;
        }

        public int getScheduledUpdateHour() {
            return scheduledUpdateHour;
        }

        public int getScheduledUpdateMinute() {
            return scheduledUpdateMinute;
        }

        public ScanMode getDefaultScanMode() {
            return defaultScanMode;
        }

        public int getDefaultCaveStart() {
            return defaultCaveStart;
        }

        public List<String> getDimensionConfigs() {
            return new ArrayList<>(dimensionConfigs);
        }

        // ========== Setter 方法 ==========

        public void setEnableDebugLogging(boolean value) {
            enableDebugLogging = value;
        }

        public void setMaxConcurrentRegions(int value) {
            maxConcurrentRegions = ConcurrentRegionsConfig.clampConfigured(value);
        }

        public void setMaxSyncPacketSize(int value) {
            maxSyncPacketSize = Math.max(65536, Math.min(1048576, value));
        }

        public void setSyncSpeedLimitKBps(int value) {
            syncSpeedLimitKBps = Math.max(0, Math.min(10240, value));
        }

        public void setIncrementalUpdateMode(UpdateMode value) {
            incrementalUpdateMode = value;
        }

        public void setIncrementalUpdateIntervalTicks(int value) {
            incrementalUpdateIntervalTicks = Math.max(2400, Math.min(72000, value));
        }

        public void setScheduledUpdateHour(int value) {
            scheduledUpdateHour = Math.max(0, Math.min(23, value));
        }

        public void setScheduledUpdateMinute(int value) {
            scheduledUpdateMinute = Math.max(0, Math.min(59, value));
        }

        public void setDefaultScanMode(ScanMode value) {
            defaultScanMode = value;
        }

        public void setDefaultCaveStart(int value) {
            defaultCaveStart = Math.max(-512, Math.min(512, value));
        }

        public void setDimensionConfigs(List<String> value) {
            dimensionConfigs = new ArrayList<>(value);
            DimensionConfigParser.invalidateCache();
        }

        /**
         * 解析维度配置列表
         */
        public List<DimensionScanConfig> parseDimensionConfigs() {
            return DimensionConfigParser.parseDimensionConfigs(dimensionConfigs);
        }

        /**
         * 解析单个配置字符串
         */
        private DimensionScanConfig parseConfigString(String configStr) {
            return DimensionConfigParser.parseConfigString(configStr);
        }

        /**
         * 获取特定维度的扫描配置
         */
        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            return DimensionConfigParser.getConfigForDimension(
                dimensionPath, dimensionConfigs, defaultScanMode, defaultCaveStart);
        }
    }
}