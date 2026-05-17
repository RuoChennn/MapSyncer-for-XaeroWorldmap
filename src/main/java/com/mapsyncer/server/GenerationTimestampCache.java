package com.mapsyncer.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 缓存每个region的生成时间戳
 * 用于同步时比对：客户端时间早于服务端则需要同步
 */
public class GenerationTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationTimestampCache.class);

    private static volatile GenerationTimestampCache instance;

    private final Path cacheFile;
    private final Map<String, Long> timestamps = new HashMap<>();
    private volatile boolean loaded = false;

    private GenerationTimestampCache(Path cacheDir) {
        this.cacheFile = cacheDir.resolve("generation_timestamps.cache");
        load();
    }

    public static GenerationTimestampCache getInstance(Path cacheDir) {
        if (instance == null) {
            synchronized (GenerationTimestampCache.class) {
                if (instance == null) {
                    instance = new GenerationTimestampCache(cacheDir);
                }
            }
        }
        return instance;
    }

    /**
     * 从文件加载时间戳缓存
     */
    private void load() {
        if (!Files.exists(cacheFile)) {
            LOGGER.info("Generation timestamp cache file not found, starting fresh");
            loaded = true;
            return;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                try {
                    long ts = Long.parseLong(props.getProperty(key));
                    timestamps.put(key, ts);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid timestamp for {}: {}", key, props.getProperty(key));
                }
            }

            LOGGER.info("Loaded {} generation timestamps from cache", timestamps.size());
            loaded = true;
        } catch (IOException e) {
            LOGGER.error("Failed to load generation timestamp cache", e);
        }
    }

    /**
     * 保存时间戳缓存到文件
     */
    public void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, Long> entry : timestamps.entrySet()) {
                props.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }

            try (OutputStream os = Files.newOutputStream(cacheFile)) {
                props.store(os, "Generation timestamps for map regions");
            }

            LOGGER.info("Saved {} generation timestamps to cache", timestamps.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save generation timestamp cache", e);
        }
    }

    /**
     * 更新region的生成时间戳
     * @param relativePath 相对路径（如 overworld/-1_-1）
     * @param timestamp 生成时间戳（毫秒）
     */
    public void updateTimestamp(String relativePath, long timestamp) {
        timestamps.put(relativePath, timestamp);
    }

    /**
     * 获取region的生成时间戳
     * @param relativePath 相对路径
     * @return 时间戳，不存在返回0
     */
    public long getTimestamp(String relativePath) {
        return timestamps.getOrDefault(relativePath, 0L);
    }

    /**
     * 获取所有时间戳
     */
    public Map<String, Long> getAllTimestamps() {
        return new HashMap<>(timestamps);
    }

    /**
     * 检查region是否需要同步
     * @param relativePath 相对路径
     * @param clientTimestamp 客户端时间戳
     * @return true表示需要同步（客户端不存在或客户端时间早于服务端）
     */
    public boolean needsSync(String relativePath, long clientTimestamp) {
        long serverTimestamp = getTimestamp(relativePath);

        // 服务端没有该region的记录，不需要同步
        if (serverTimestamp == 0) {
            return false;
        }

        // 客户端不存在该region（时间戳为0），需要同步
        if (clientTimestamp == 0) {
            return true;
        }

        // 客户端时间早于服务端，需要同步
        // 客户端时间晚于或等于服务端，不需要同步（保留客户端数据）
        return clientTimestamp < serverTimestamp;
    }

    /**
     * 清除缓存
     */
    public void clear() {
        timestamps.clear();
        save();
    }

    /**
     * Reset singleton instance to release memory.
     * Called when server stops to prevent memory leaks on dedicated servers.
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.timestamps.clear();
            instance = null;
            LOGGER.info("GenerationTimestampCache instance reset");
        }
    }
}