package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Properties 格式缓存文件读写工具类
 *
 * 统一的缓存文件IO操作，合并 ClientTimestampCache 和 GenerationCache 中的重复实现
 * 支持泛型值类型，通过解析器/格式化器进行转换
 */
public final class PropertiesCacheIO {

    private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesCacheIO.class);

    /**
     * 私有构造方法，防止实例化
     */
    private PropertiesCacheIO() {
        // 工具类不允许实例化
    }

    /**
     * 从 Properties 文件加载缓存
     *
     * @param cacheFile 缓存文件路径
     * @param parser 值解析器（字符串 → T）
     * @return 加载的缓存数据 Map
     */
    public static <T> Map<String, T> load(Path cacheFile, Function<String, T> parser) {
        Map<String, T> cache = new HashMap<>();

        if (cacheFile == null || !Files.exists(cacheFile)) {
            LOGGER.info("Cache file not found: {}", cacheFile);
            return cache;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                T value = parser.apply(props.getProperty(key));
                if (value != null) {
                    cache.put(key, value);
                } else {
                    LOGGER.warn("Invalid cache entry for {}: {}", key, props.getProperty(key));
                }
            }

            LOGGER.info("Loaded {} entries from cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to load cache file: {}", cacheFile, e);
        }

        return cache;
    }

    /**
     * 保存缓存到 Properties 文件
     *
     * @param cacheFile 缓存文件路径
     * @param cache 缓存数据 Map
     * @param formatter 值格式化器（T → 字符串）
     * @param header 文件头注释
     */
    public static <T> void save(Path cacheFile, Map<String, T> cache, Function<T, String> formatter, String header) {
        if (cacheFile == null) {
            LOGGER.warn("Cache file path is null, skip saving");
            return;
        }

        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, T> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), formatter.apply(entry.getValue()));
            }

            try (OutputStream os = Files.newOutputStream(cacheFile)) {
                props.store(os, header != null ? header : "Cache file");
            }

            LOGGER.info("Saved {} entries to cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to save cache file: {}", cacheFile, e);
        }
    }

    /**
     * 解析 "timestamp_seconds:hash" 格式的缓存值
     *
     * @param value 缓存值字符串（如 "1234567890:abc12345"）
     * @return TimestampHashEntry 对象，解析失败返回 null
     */
    public static TimestampHashEntry parseTimestampHash(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        String[] parts = value.split(":");
        if (parts.length == 2) {
            try {
                long ts = Long.parseLong(parts[0]);
                return new TimestampHashEntry(ts, parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 时间戳+哈希缓存条目
     */
    public record TimestampHashEntry(long timestampSeconds, String hash) {
        /**
         * 格式化为缓存字符串
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }
}