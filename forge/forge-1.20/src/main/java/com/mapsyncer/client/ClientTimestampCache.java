package com.mapsyncer.client;

import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 缓存服务端同步过来的 region 时间戳和同步状态。
 * 用于下次同步时比较，避免因客户端文件修改时间变化导致的误同步。
 *
 * <p>缓存格式（合并文件）：</p>
 * <pre>
 * # Sync timestamps cache
 * _state = in_progress
 * _dimensions = null, DIM-1
 * _command = /mapsyncer sync all
 * null/0_0 = 1234567890:abc12345
 * null/1_0 = 1234567891:def45678
 * </pre>
 *
 * <p>同步状态设计（简化版）：</p>
 * <ul>
 *   <li>只有两种状态：in_progress（同步进行中）和 completed（同步完成）</li>
 *   <li>开始同步时标记为 in_progress</li>
 *   <li>完成同步后标记为 completed</li>
 *   <li>断开连接不改变状态（保持 in_progress）</li>
 *   <li>加入游戏时如果状态为 in_progress，显示断点续传提示</li>
 *   <li>如果缓存文件不存在，说明从未同步过，跳过检测</li>
 * </ul>
 */
public class ClientTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTimestampCache.class);

    /** 缓存文件名称（合并状态和时间戳） */
    private static final String CACHE_FILE_NAME = "sync_timestamps.cache";

    /** 状态键前缀（用于区分状态信息和区域缓存） */
    private static final String KEY_STATE = "_state";
    private static final String KEY_DIMENSIONS = "_dimensions";
    private static final String KEY_COMMAND = "_command";

    /** 同步状态：同步进行中（断点续传可用） */
    public static final String SYNC_STATE_IN_PROGRESS = "in_progress";

    /** 同步状态：同步完成 */
    public static final String SYNC_STATE_COMPLETED = "completed";

    /** 单例实例 */
    private static volatile ClientTimestampCache instance;

    /** 上次使用的服务器目录 */
    private static volatile Path lastBaseDir = null;

    /**
     * 获取上次使用的服务器目录。
     *
     * @return 上次使用的服务器目录，如果不存在返回 null
     */
    public static Path getLastBaseDir() {
        return lastBaseDir;
    }

    /** 缓存文件路径 */
    private final Path cacheFile;

    /** 缓存数据，键为相对路径（如 "null/0_0"），值为缓存条目 */
    private final Map<String, CacheEntry> cache = new HashMap<>();

    /** 当前同步状态（null 表示从未同步过） */
    private volatile String syncState = null;

    /** 同步涉及的维度列表 */
    private volatile Set<String> syncDimensions = new HashSet<>();

    /** 同步指令（用于断点续传提示） */
    private volatile String syncCommand = "";

    /**
     * 缓存条目：时间戳(秒) + CRC32哈希。
     *
     * @param timestampSeconds 时间戳（秒）
     * @param hash CRC32哈希值（8位十六进制）
     */
    public record CacheEntry(long timestampSeconds, String hash) {
        /**
         * 从字符串解析缓存条目。
         */
        public static CacheEntry parse(String value) {
            TimestampHashEntry entry = PropertiesCacheIO.parseTimestampHash(value);
            return entry != null ? new CacheEntry(entry.timestampSeconds(), entry.hash()) : null;
        }

        /**
         * 将缓存条目格式化为字符串。
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }

    /**
     * 私有构造函数，初始化缓存实例。
     */
    private ClientTimestampCache(Path baseDir) {
        this.cacheFile = baseDir.resolve(CACHE_FILE_NAME);
        load();
    }

    /**
     * 获取缓存实例。
     */
    public static ClientTimestampCache getInstance(Path baseDir) {
        if (baseDir == null) {
            return instance;
        }

        if (instance == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
            synchronized (ClientTimestampCache.class) {
                if (instance == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
                    instance = new ClientTimestampCache(baseDir);
                    lastBaseDir = baseDir;
                    LOGGER.info("ClientTimestampCache initialized for baseDir: {}", baseDir);
                }
            }
        }
        return instance;
    }

    /**
     * 重置实例。
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.cache.clear();
            instance = null;
            lastBaseDir = null;
            LOGGER.info("ClientTimestampCache instance reset");
        }
    }

    /**
     * 从文件加载缓存（包含状态和区域时间戳）。
     */
    private void load() {
        if (!Files.exists(cacheFile)) {
            syncState = null;
            LOGGER.info("Cache file not found, never synced before");
            return;
        }

        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(cacheFile)) {
                props.load(in);
            }

            // 加载状态信息（特殊键）
            syncState = props.getProperty(KEY_STATE, null);
            String dimsStr = props.getProperty(KEY_DIMENSIONS, "");
            syncDimensions = new HashSet<>();
            if (!dimsStr.isEmpty()) {
                for (String dim : dimsStr.split(",")) {
                    syncDimensions.add(dim.trim());
                }
            }
            syncCommand = props.getProperty(KEY_COMMAND, "");

            // 加载区域缓存（非特殊键）
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("_")) {
                    CacheEntry entry = CacheEntry.parse(props.getProperty(key));
                    if (entry != null) {
                        cache.put(key, entry);
                    }
                }
            }

            LOGGER.info("Loaded cache: state={}, regions={}, file={}", syncState, cache.size(), cacheFile.getFileName());
        } catch (Exception e) {
            LOGGER.warn("Failed to load cache file: {}", e.getMessage());
            syncState = null;
        }
    }

    /**
     * 保存缓存到文件（包含状态和区域时间戳）。
     */
    public void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();

            // 保存状态信息
            if (syncState != null) {
                props.setProperty(KEY_STATE, syncState);
            }
            props.setProperty(KEY_DIMENSIONS, String.join(",", syncDimensions));
            props.setProperty(KEY_COMMAND, syncCommand);

            // 保存区域缓存
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue().format());
            }

            try (var out = Files.newOutputStream(cacheFile)) {
                // 先写入状态信息
                StringBuilder content = new StringBuilder();
                content.append("# Sync timestamps cache\n");
                content.append("# ==================== STATE ====================\n");
                if (syncState != null) {
                    content.append(KEY_STATE).append("=").append(syncState).append("\n");
                }
                content.append(KEY_DIMENSIONS).append("=").append(String.join(",", syncDimensions)).append("\n");
                content.append(KEY_COMMAND).append("=").append(syncCommand).append("\n");
                content.append("\n");
                content.append("# ==================== TIMESTAMP CACHE ====================\n");
                content.append("# Format: dimension/region_x_z = timestamp_seconds:hash\n");

                // 写入区域缓存
                for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                    content.append(entry.getKey()).append("=").append(entry.getValue().format()).append("\n");
                }

                out.write(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            LOGGER.debug("Saved cache: state={}, regions={}", syncState, cache.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to save cache file: {}", e.getMessage());
        }
    }

    /**
     * 标记同步开始。
     */
    public void markSyncStart(Set<String> dimensions, String command) {
        syncState = SYNC_STATE_IN_PROGRESS;
        syncDimensions = new HashSet<>(dimensions);
        syncCommand = command;
        save();
        LOGGER.info("Marked sync start: dimensions={}, command={}", dimensions, command);
    }

    /**
     * 标记同步完成。
     */
    public void markSyncComplete() {
        syncState = SYNC_STATE_COMPLETED;
        save();
        LOGGER.info("Marked sync complete");
    }

    /**
     * 清除同步状态（用户忽略断点续传提示时调用）。
     */
    public void clearSyncState() {
        syncState = SYNC_STATE_COMPLETED;
        syncDimensions.clear();
        syncCommand = "";
        save();
        LOGGER.info("Cleared sync state (marked as completed)");
    }

    /**
     * 获取当前同步状态。
     */
    public String getSyncState() {
        return syncState;
    }

    /**
     * 获取同步指令。
     */
    public String getSyncCommand() {
        return syncCommand;
    }

    /**
     * 检查是否需要断点续传。
     */
    public boolean needsResume() {
        return SYNC_STATE_IN_PROGRESS.equals(syncState);
    }

    /**
     * 获取同步涉及的维度集合。
     */
    public Set<String> getSyncDimensions() {
        return new HashSet<>(syncDimensions);
    }

    /**
     * 更新区域的缓存信息。
     */
    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new CacheEntry(timestampSeconds, hash));
    }

    /**
     * 获取区域的缓存信息。
     */
    public CacheEntry get(String relativePath) {
        return cache.get(relativePath);
    }

    /**
     * 获取所有缓存数据。
     *
     * <p>返回不可修改视图，避免创建完整副本浪费内存。</p>
     * <p>如果需要修改数据，请使用 update() 方法。</p>
     */
    public Map<String, CacheEntry> getAll() {
        return Collections.unmodifiableMap(cache);
    }

    /**
     * 清空缓存数据。
     */
    public void clear() {
        cache.clear();
        syncState = null;
        syncDimensions.clear();
        syncCommand = "";
        try {
            Files.deleteIfExists(cacheFile);
            LOGGER.info("Cleared cache");
        } catch (Exception e) {
            LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    /**
     * 检查指定维度是否已同步过。
     */
    public boolean hasDimensionSynced(String xaeroDim) {
        String prefix = xaeroDim + "/";
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查缓存文件是否存在。
     */
    public boolean cacheFileExists() {
        return Files.exists(cacheFile);
    }

    /**
     * 获取缓存文件路径。
     */
    public Path getCacheFile() {
        return cacheFile;
    }
}