package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.UpdateMode;
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

                LOGGER.info("Loaded client config from: {} (hashThreads={})", configFile, hashThreads);
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

                Properties props = new Properties();
                props.setProperty("hashThreads", String.valueOf(hashThreads));

                try (OutputStream os = Files.newOutputStream(configFile)) {
                    props.store(os, "MapSyncer Client Configuration\nHash computation thread count for map sync");
                }

                LOGGER.info("Saved client config to: {} (hashThreads={})", configFile, hashThreads);
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

        /**
         * 设置哈希计算线程数
         *
         * @param value 线程数
         */
        public void setHashThreads(int value) {
            int maxThreads = Runtime.getRuntime().availableProcessors();
            hashThreads = Math.max(1, Math.min(maxThreads, value));
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
        private volatile int maxConcurrentRegions = 4;
        private volatile int maxSyncPacketSize = 262144;
        private volatile int syncSpeedLimitKBps = 1024;

        // ========== 增量更新设置 ==========
        private volatile UpdateMode incrementalUpdateMode = UpdateMode.DISABLED;
        private volatile int incrementalUpdateIntervalTicks = 200;
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
        }

        /**
         * 获取原版维度的默认配置
         */
        private List<String> getDefaultDimensionConfigStrings() {
            List<String> defaults = new ArrayList<>();
            defaults.add("minecraft:overworld|SURFACE|63|true|false|-64|384|384");
            defaults.add("minecraft:the_nether|CAVE|63|false|true|0|256|256");
            defaults.add("minecraft:the_end|SURFACE|63|false|false|0|256|256");
            return defaults;
        }

        /**
         * 从文件加载配置
         */
        private void load() {
            if (!Files.exists(configFile)) {
                LOGGER.info("Config file not found, using defaults");
                return;
            }

            try (InputStream is = Files.newInputStream(configFile)) {
                Properties props = new Properties();
                props.load(is);

                // 通用设置
                enableDebugLogging = Boolean.parseBoolean(props.getProperty("enableDebugLogging", "false"));
                maxConcurrentRegions = Integer.parseInt(props.getProperty("maxConcurrentRegions", "4"));
                maxSyncPacketSize = Integer.parseInt(props.getProperty("maxSyncPacketSize", "262144"));
                syncSpeedLimitKBps = Integer.parseInt(props.getProperty("syncSpeedLimitKBps", "1024"));

                // 增量更新设置
                incrementalUpdateMode = UpdateMode.valueOf(props.getProperty("incrementalUpdateMode", "DISABLED"));
                incrementalUpdateIntervalTicks = Integer.parseInt(props.getProperty("incrementalUpdateIntervalTicks", "200"));
                scheduledUpdateHour = Integer.parseInt(props.getProperty("scheduledUpdateHour", "4"));
                scheduledUpdateMinute = Integer.parseInt(props.getProperty("scheduledUpdateMinute", "0"));

                // 维度扫描配置
                defaultScanMode = ScanMode.valueOf(props.getProperty("defaultScanMode", "SURFACE"));
                defaultCaveStart = Integer.parseInt(props.getProperty("defaultCaveStart", "63"));

                String dimsStr = props.getProperty("dimensionConfigs", "");
                dimensionConfigs.clear();
                if (!dimsStr.isEmpty()) {
                    for (String dim : dimsStr.split(";")) {
                        if (!dim.trim().isEmpty()) {
                            dimensionConfigs.add(dim.trim());
                        }
                    }
                }

                LOGGER.info("Loaded config from: {}", configFile);
            } catch (Exception e) {
                LOGGER.warn("Failed to load config, using defaults: {}", e.getMessage());
            }
        }

        /**
         * 保存配置到文件
         */
        public void save() {
            try {
                Files.createDirectories(configFile.getParent());

                Properties props = new Properties();

                // 通用设置
                props.setProperty("enableDebugLogging", String.valueOf(enableDebugLogging));
                props.setProperty("maxConcurrentRegions", String.valueOf(maxConcurrentRegions));
                props.setProperty("maxSyncPacketSize", String.valueOf(maxSyncPacketSize));
                props.setProperty("syncSpeedLimitKBps", String.valueOf(syncSpeedLimitKBps));

                // 增量更新设置
                props.setProperty("incrementalUpdateMode", incrementalUpdateMode.name());
                props.setProperty("incrementalUpdateIntervalTicks", String.valueOf(incrementalUpdateIntervalTicks));
                props.setProperty("scheduledUpdateHour", String.valueOf(scheduledUpdateHour));
                props.setProperty("scheduledUpdateMinute", String.valueOf(scheduledUpdateMinute));

                // 维度扫描配置
                props.setProperty("defaultScanMode", defaultScanMode.name());
                props.setProperty("defaultCaveStart", String.valueOf(defaultCaveStart));
                props.setProperty("dimensionConfigs", String.join(";", dimensionConfigs));

                try (OutputStream os = Files.newOutputStream(configFile)) {
                    props.store(os, "MapSyncer Server Configuration");
                }

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
            maxConcurrentRegions = Math.max(1, Math.min(16, value));
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
            incrementalUpdateIntervalTicks = Math.max(20, Math.min(72000, value));
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

            // 原版维度的内置默认配置
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

            // 尝试匹配配置列表中的维度
            for (DimensionScanConfig config : parseDimensionConfigs()) {
                String configDim = config.dimension();
                if (configDim.equalsIgnoreCase(dimensionPath) ||
                    configDim.equalsIgnoreCase("minecraft:" + dimensionPath) ||
                    configDim.replace("minecraft:", "").equalsIgnoreCase(dimensionPath)) {
                    return config;
                }
            }

            // 未匹配则返回默认配置
            DimensionTypeInfo inferredDimType = DimensionTypeInfo.fromDimensionId(dimensionPath);
            return new DimensionScanConfig(dimensionPath, defaultScanMode, defaultCaveStart, inferredDimType);
        }
    }
}