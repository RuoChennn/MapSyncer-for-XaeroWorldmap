package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

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
    private static volatile Path lastBaseDir = null;  // Track the last used baseDir

    private final Path cacheFile;
    private final Map<String, CacheEntry> cache = new HashMap<>();

    /**
     * 缓存条目：时间戳(秒) + CRC32哈希
     */
    public record CacheEntry(long timestampSeconds, String hash) {
        public static CacheEntry parse(String value) {
            String[] parts = value.split(":");
            if (parts.length == 2) {
                try {
                    long ts = Long.parseLong(parts[0]);
                    return new CacheEntry(ts, parts[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
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
     * 获取实例，baseDir 通常是 Multiplayer_<server> 目录
     * 如果路径发生变化，会重新初始化单例
     */
    public static ClientTimestampCache getInstance(Path baseDir) {
        if (baseDir == null) {
            return instance;
        }

        // 如果路径变化，重置单例
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
     * 重置实例（用于切换服务器时）
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
     * 从文件加载缓存
     */
    private void load() {
        if (!Files.exists(cacheFile)) {
            LOGGER.info("No existing sync timestamp cache found");
            return;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                CacheEntry entry = CacheEntry.parse(props.getProperty(key));
                if (entry != null) {
                    cache.put(key, entry);
                } else {
                    LOGGER.warn("Invalid cache entry for {}: {}", key, props.getProperty(key));
                }
            }

            LOGGER.info("Loaded {} region entries from sync timestamp cache", cache.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load sync timestamp cache", e);
        }
    }

    /**
     * 保存缓存到文件
     */
    public void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue().format());
            }

            try (OutputStream os = Files.newOutputStream(cacheFile)) {
                props.store(os, "Sync timestamps cache\nFormat: dimension/region_x_z = timestamp_seconds:hash\nUsed to compare with server for sync decisions");
            }

            LOGGER.info("Saved {} region entries to sync timestamp cache", cache.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save sync timestamp cache", e);
        }
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
        } catch (IOException e) {
            LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    /**
     * 检查指定维度是否已同步过
     * @param xaeroDim Xaero 格式的维度名（如 "null", "DIM-1", "DIM1"）
     * @return true 如果该维度至少有一个已同步的 region
     */
    public boolean hasDimensionSynced(String xaeroDim) {
        // 检查 cache 中是否有以该维度开头的 key
        // key 格式：xaeroDim/regionX_regionZ 或 xaeroDim/caves/layer/regionX_regionZ
        String prefix = xaeroDim + "/";
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查缓存文件是否存在（表示至少运行过一次同步）
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