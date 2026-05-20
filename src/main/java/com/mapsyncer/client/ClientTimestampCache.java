package com.mapsyncer.client;

import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存服务端同步过来的 region 时间戳
 * 用于下次同步时比较，避免因客户端文件修改时间变化导致的误同步
 *
 * 格式：dimension/regionX_regionZ = timestamp_seconds:hash
 */
public class ClientTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTimestampCache.class);
    private static final String CACHE_FILE_NAME = "sync_timestamps.cache";

    private static volatile ClientTimestampCache instance;
    private static volatile Path lastBaseDir = null;

    private final Path cacheFile;
    private final Map<String, CacheEntry> cache = new HashMap<>();

    /**
     * 缓存条目：时间戳(秒) + CRC32哈希
     * 与 TimestampHashEntry 功能相同，保留此类型用于兼容
     */
    public record CacheEntry(long timestampSeconds, String hash) {
        public static CacheEntry parse(String value) {
            TimestampHashEntry entry = PropertiesCacheIO.parseEntry(value);
            return entry != null ? new CacheEntry(entry.timestampSeconds(), entry.hash()) : null;
        }

        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }

    private ClientTimestampCache(Path baseDir) {
        this.cacheFile = baseDir.resolve(CACHE_FILE_NAME);
        load();
    }

    /**
     * 获取实例（使用 PropertiesCacheIO 加载）
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
     * 重置实例
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
     * 从文件加载缓存（使用 PropertiesCacheIO）
     */
    private void load() {
        Map<String, TimestampHashEntry> loaded = PropertiesCacheIO.load(cacheFile, PropertiesCacheIO::parseEntry);
        for (Map.Entry<String, TimestampHashEntry> entry : loaded.entrySet()) {
            cache.put(entry.getKey(), new CacheEntry(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
    }

    /**
     * 保存缓存到文件（使用 PropertiesCacheIO）
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
     * 更新 region 的缓存信息
     */
    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new CacheEntry(timestampSeconds, hash));
    }

    /**
     * 获取 region 的缓存信息
     */
    public CacheEntry get(String relativePath) {
        return cache.get(relativePath);
    }

    /**
     * 获取所有缓存数据
     */
    public Map<String, CacheEntry> getAll() {
        return new HashMap<>(cache);
    }

    /**
     * 清空缓存
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
     * 检查指定维度是否已同步过
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
     * 检查缓存文件是否存在
     */
    public boolean cacheFileExists() {
        return Files.exists(cacheFile);
    }

    /**
     * 获取缓存文件路径
     */
    public Path getCacheFile() {
        return cacheFile;
    }
}