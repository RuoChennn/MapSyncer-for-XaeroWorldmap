package com.mapsyncer.client;

import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 缓存服务端同步过来的 region 时间戳。
 * 用于下次同步时比较，避免因客户端文件修改时间变化导致的误同步。
 *
 * <p>缓存格式：dimension/regionX_regionZ = timestamp_seconds:hash</p>
 *
 * <p>缓存文件位置：位于服务器目录（Multiplayer_<server>）下的 sync_timestamps.cache 文件中。</p>
 *
 * <p>用途说明：</p>
 * <ul>
 *   <li>当客户端从服务器同步区域数据后，记录该区域的时间戳和哈希值</li>
 *   <li>下次同步时，使用缓存的值而非文件修改时间进行比较，避免误同步</li>
 *   <li>服务端和客户端使用相同格式，便于直接比较</li>
 * </ul>
 */
public class ClientTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTimestampCache.class);

    /** 缓存文件名称 */
    private static final String CACHE_FILE_NAME = "sync_timestamps.cache";

    /** 同步状态文件名称 */
    private static final String SYNC_STATE_FILE_NAME = "sync_state.cache";

    /** 同步状态：无同步进行 */
    public static final String SYNC_STATE_IDLE = "idle";

    /** 同步状态：同步进行中 */
    public static final String SYNC_STATE_IN_PROGRESS = "in_progress";

    /** 同步状态：同步完成 */
    public static final String SYNC_STATE_COMPLETED = "completed";

    /** 同步状态：同步中断（断点续传可用） */
    public static final String SYNC_STATE_INTERRUPTED = "interrupted";

    /** 单例实例 */
    private static volatile ClientTimestampCache instance;

    /** 上次使用的服务器目录 */
    private static volatile Path lastBaseDir = null;

    /** 缓存文件路径 */
    private final Path cacheFile;

    /** 同步状态文件路径 */
    private final Path syncStateFile;

    /** 缓存数据，键为相对路径（如 "null/0_0"），值为缓存条目 */
    private final Map<String, CacheEntry> cache = new HashMap<>();

    /** 当前同步状态 */
    private volatile String syncState = SYNC_STATE_IDLE;

    /** 同步开始时间（毫秒） */
    private volatile long syncStartTime = 0;

    /** 同步涉及的维度列表 */
    private volatile Set<String> syncDimensions = new HashSet<>();

    /** 同步指令（用于断点续传提示） */
    private volatile String syncCommand = "";

    /**
     * 缓存条目：时间戳(秒) + CRC32哈希。
     * 与 TimestampHashEntry 功能相同，保留此类型用于兼容。
     *
     * @param timestampSeconds 时间戳（秒）
     * @param hash CRC32哈希值（8位十六进制）
     */
    public record CacheEntry(long timestampSeconds, String hash) {
        /**
         * 从字符串解析缓存条目。
         *
         * @param value 字符串值，格式为 "timestamp_seconds:hash"
         * @return 解析后的缓存条目，如果解析失败返回 null
         */
        public static CacheEntry parse(String value) {
            TimestampHashEntry entry = PropertiesCacheIO.parseEntry(value);
            return entry != null ? new CacheEntry(entry.timestampSeconds(), entry.hash()) : null;
        }

        /**
         * 将缓存条目格式化为字符串。
         *
         * @return 格式化的字符串，格式为 "timestamp_seconds:hash"
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }

    /**
     * 私有构造函数，初始化缓存实例。
     *
     * @param baseDir 服务器目录路径
     */
    private ClientTimestampCache(Path baseDir) {
        this.cacheFile = baseDir.resolve(CACHE_FILE_NAME);
        this.syncStateFile = baseDir.resolve(SYNC_STATE_FILE_NAME);
        load();
        loadSyncState();
    }

    /**
     * 获取缓存实例（使用 PropertiesCacheIO 加载）。
     * 采用单例模式，当服务器目录变化时重新创建实例。
     *
     * @param baseDir 服务器目录路径（Multiplayer_<server> 目录）
     * @return 缓存实例，如果 baseDir 为 null 则返回现有实例
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
     * 重置实例，清空缓存数据。
     * 用于切换服务器或强制重新加载时。
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
     * 从文件加载缓存（使用 PropertiesCacheIO）。
     * 如果缓存文件存在，读取其中的时间戳和哈希值。
     */
    private void load() {
        Map<String, TimestampHashEntry> loaded = PropertiesCacheIO.load(cacheFile, PropertiesCacheIO::parseEntry);
        for (Map.Entry<String, TimestampHashEntry> entry : loaded.entrySet()) {
            cache.put(entry.getKey(), new CacheEntry(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
    }

    /**
     * 从文件加载同步状态。
     * 用于断点续传检测。
     */
    private void loadSyncState() {
        if (!Files.exists(syncStateFile)) {
            syncState = SYNC_STATE_IDLE;
            return;
        }

        try {
            java.util.Properties props = new java.util.Properties();
            try (java.io.InputStream in = Files.newInputStream(syncStateFile)) {
                props.load(in);
            }

            syncState = props.getProperty("state", SYNC_STATE_IDLE);
            syncStartTime = Long.parseLong(props.getProperty("startTime", "0"));

            String dimsStr = props.getProperty("dimensions", "");
            syncDimensions = new HashSet<>();
            if (!dimsStr.isEmpty()) {
                for (String dim : dimsStr.split(",")) {
                    syncDimensions.add(dim.trim());
                }
            }

            syncCommand = props.getProperty("command", "");

            LOGGER.info("Loaded sync state: {}, startTime={}, dimensions={}, command={}", syncState, syncStartTime, syncDimensions, syncCommand);
        } catch (Exception e) {
            LOGGER.warn("Failed to load sync state file: {}", e.getMessage());
            syncState = SYNC_STATE_IDLE;
        }
    }

    /**
     * 保存同步状态到文件。
     */
    public void saveSyncState() {
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("state", syncState);
            props.setProperty("startTime", String.valueOf(syncStartTime));
            props.setProperty("dimensions", String.join(",", syncDimensions));
            props.setProperty("command", syncCommand);

            Files.createDirectories(syncStateFile.getParent());
            try (java.io.OutputStream out = Files.newOutputStream(syncStateFile)) {
                props.store(out, "Sync state for resume detection\nstate: idle/in_progress/completed/interrupted");
            }

            LOGGER.debug("Saved sync state: {}", syncState);
        } catch (Exception e) {
            LOGGER.warn("Failed to save sync state file: {}", e.getMessage());
        }
    }

    /**
     * 标记同步开始。
     *
     * @param dimensions 同步涉及的维度集合
     * @param command 同步指令（用于断点续传提示）
     */
    public void markSyncStart(Set<String> dimensions, String command) {
        syncState = SYNC_STATE_IN_PROGRESS;
        syncStartTime = System.currentTimeMillis();
        syncDimensions = new HashSet<>(dimensions);
        syncCommand = command;
        saveSyncState();
        LOGGER.info("Marked sync start for dimensions: {}, command: {}", dimensions, command);
    }

    /**
     * 标记同步完成。
     */
    public void markSyncComplete() {
        syncState = SYNC_STATE_COMPLETED;
        saveSyncState();
        LOGGER.info("Marked sync complete");
    }

    /**
     * 标记同步中断（断点续传可用）。
     */
    public void markSyncInterrupted() {
        syncState = SYNC_STATE_INTERRUPTED;
        saveSyncState();
        LOGGER.info("Marked sync interrupted - resume available");
    }

    /**
     * 清除同步状态（重置为空闲）。
     */
    public void clearSyncState() {
        syncState = SYNC_STATE_IDLE;
        syncStartTime = 0;
        syncDimensions.clear();
        syncCommand = "";
        saveSyncState();
        try {
            Files.deleteIfExists(syncStateFile);
        } catch (Exception e) {
            LOGGER.warn("Failed to delete sync state file: {}", e.getMessage());
        }
        LOGGER.info("Cleared sync state");
    }

    /**
     * 获取当前同步状态。
     *
     * @return 同步状态字符串
     */
    public String getSyncState() {
        return syncState;
    }

    /**
     * 获取同步指令。
     *
     * @return 同步指令字符串，空字符串表示无指令
     */
    public String getSyncCommand() {
        return syncCommand;
    }

    /**
     * 检查是否可以断点续传。
     * 状态为 INTERRUPTED 或 IN_PROGRESS（陈旧）时可以续传。
     *
     * @param staleTimeoutMs 陈旧超时时间（毫秒），超过此时间的进行中同步视为陈旧
     * @return true 表示可以断点续传
     */
    public boolean canResume(long staleTimeoutMs) {
        if (syncState == SYNC_STATE_INTERRUPTED) {
            return true;
        }

        // 进行中的同步如果超过超时时间，视为陈旧可续传
        if (syncState == SYNC_STATE_IN_PROGRESS && syncStartTime > 0) {
            long elapsed = System.currentTimeMillis() - syncStartTime;
            return elapsed > staleTimeoutMs;
        }

        return false;
    }

    /**
     * 获取同步涉及的维度集合。
     *
     * @return 维度集合副本
     */
    public Set<String> getSyncDimensions() {
        return new HashSet<>(syncDimensions);
    }

    /**
     * 获取同步开始时间。
     *
     * @return 同步开始时间（毫秒），0表示无进行中同步
     */
    public long getSyncStartTime() {
        return syncStartTime;
    }

    /**
     * 保存缓存到文件（使用 PropertiesCacheIO）。
     * 将所有缓存条目写入磁盘文件。
     */
    public void save() {
        Map<String, TimestampHashEntry> toSave = new HashMap<>();
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            toSave.put(entry.getKey(), new TimestampHashEntry(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
        PropertiesCacheIO.save(cacheFile, toSave, TimestampHashEntry::format,
            "Sync timestamps cache\nFormat: dimension/region_x_z = timestamp_seconds:hash\nUsed to compare with server for sync decisions");
    }

    /**
     * 更新区域的缓存信息。
     *
     * @param relativePath 相对路径（如 "null/0_0" 或 "twilightforest$twilight_forest/0_0"）
     * @param timestampSeconds 时间戳（秒）
     * @param hash CRC32哈希值
     */
    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new CacheEntry(timestampSeconds, hash));
    }

    /**
     * 获取区域的缓存信息。
     *
     * @param relativePath 相对路径（如 "null/0_0"）
     * @return 缓存条目，如果不存在返回 null
     */
    public CacheEntry get(String relativePath) {
        return cache.get(relativePath);
    }

    /**
     * 获取所有缓存数据。
     * 返回一个新的 HashMap 副本，避免外部修改影响内部数据。
     *
     * @return 所有缓存条目的副本
     */
    public Map<String, CacheEntry> getAll() {
        return new HashMap<>(cache);
    }

    /**
     * 清空缓存数据。
     * 同时删除磁盘上的缓存文件。
     */
    public void clear() {
        cache.clear();
        try {
            Files.deleteIfExists(cacheFile);
            LOGGER.info("Cleared sync timestamp cache");
        } catch (Exception e) {
            LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    /**
     * 检查指定维度是否已同步过。
     * 通过检查缓存中是否有以该维度为前缀的键来判断。
     *
     * @param xaeroDim Xaero格式的维度名称（如 "null"、"DIM-1"、"twilightforest$twilight_forest"）
     * @return 如果该维度有同步记录，返回 true；否则返回 false
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
     *
     * @return 如果缓存文件存在，返回 true；否则返回 false
     */
    public boolean cacheFileExists() {
        return Files.exists(cacheFile);
    }

    /**
     * 获取缓存文件路径。
     *
     * @return 缓存文件的完整路径
     */
    public Path getCacheFile() {
        return cacheFile;
    }
}