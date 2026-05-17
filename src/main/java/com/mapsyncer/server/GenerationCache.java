package com.mapsyncer.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.CRC32;

/**
 * 缓存每个region的生成时间戳和CRC32哈希值
 * 用于同步时比对：
 * - 哈希值一致 → 不同步（文件内容相同）
 * - 哈希值不一致 → 检查时间戳，客户端旧于服务端则同步
 */
public class GenerationCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationCache.class);

    private static volatile GenerationCache instance;

    private final Path cacheFile;
    // 存储：relativePath -> "timestamp_seconds:hash"
    private final Map<String, RegionMeta> cache = new HashMap<>();

    /**
     * Region元数据：时间戳(秒) + CRC32哈希
     */
    public record RegionMeta(long timestampSeconds, String hash) {
        /**
         * 解析缓存字符串
         * 格式：timestamp:hash
         */
        public static RegionMeta parse(String value) {
            String[] parts = value.split(":");
            if (parts.length == 2) {
                try {
                    long ts = Long.parseLong(parts[0]);
                    return new RegionMeta(ts, parts[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        /**
         * 格式化为缓存字符串
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }

    private GenerationCache(Path cacheDir) {
        this.cacheFile = cacheDir.resolve("generation_cache.properties");
        load();
    }

    public static GenerationCache getInstance(Path cacheDir) {
        if (instance == null) {
            synchronized (GenerationCache.class) {
                if (instance == null) {
                    instance = new GenerationCache(cacheDir);
                }
            }
        }
        return instance;
    }

    /**
     * 从文件加载缓存
     */
    private void load() {
        if (!Files.exists(cacheFile)) {
            LOGGER.info("Generation cache file not found, starting fresh");
            return;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                RegionMeta meta = RegionMeta.parse(props.getProperty(key));
                if (meta != null) {
                    cache.put(key, meta);
                } else {
                    LOGGER.warn("Invalid cache entry for {}: {}", key, props.getProperty(key));
                }
            }

            LOGGER.info("Loaded {} region entries from generation cache", cache.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load generation cache", e);
        }
    }

    /**
     * 保存缓存到文件
     */
    public void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, RegionMeta> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue().format());
            }

            try (OutputStream os = Files.newOutputStream(cacheFile)) {
                props.store(os, "Generation cache for map regions\nFormat: dimension/region_x_z = timestamp_seconds:hash\nHash is CRC32 of file content");
            }

            LOGGER.info("Saved {} region entries to generation cache", cache.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save generation cache", e);
        }
    }

    /**
     * 计算文件的CRC32哈希值
     * @param filePath 文件路径
     * @return CRC32哈希值（8位十六进制字符串）
     */
    public static String computeFileHash(Path filePath) {
        if (!Files.exists(filePath)) {
            return "00000000";
        }

        try {
            CRC32 crc32 = new CRC32();
            byte[] data = Files.readAllBytes(filePath);
            crc32.update(data);
            return String.format("%08x", crc32.getValue());
        } catch (IOException e) {
            LOGGER.warn("Failed to compute hash for {}", filePath, e);
            return "00000000";
        }
    }

    /**
     * 更新region的缓存信息
     * @param relativePath 相对路径（如 overworld/-1_-1）
     * @param timestampSeconds 生成时间戳（秒）
     * @param hash CRC32哈希值
     */
    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
    }

    /**
     * 更新region的缓存信息（自动计算哈希）
     * @param relativePath 相对路径
     * @param filePath 实际文件路径，用于计算哈希
     * @param timestampSeconds 生成时间戳（秒）
     */
    public void updateWithHash(String relativePath, Path filePath, long timestampSeconds) {
        String hash = computeFileHash(filePath);
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
        LOGGER.debug("Updated cache for {}: ts={}, hash={}", relativePath, timestampSeconds, hash);
    }

    /**
     * 获取region的元数据
     * @param relativePath 相对路径
     * @return 元数据，不存在返回null
     */
    public RegionMeta getMeta(String relativePath) {
        return cache.get(relativePath);
    }

    /**
     * 获取所有缓存数据
     */
    public Map<String, RegionMeta> getAll() {
        return new HashMap<>(cache);
    }

    /**
     * 检查是否需要同步
     * @param relativePath 相对路径
     * @param clientMeta 客户端元数据（时间戳秒+哈希）
     * @return true表示需要同步
     */
    public boolean needsSync(String relativePath, RegionMeta clientMeta) {
        RegionMeta serverMeta = cache.get(relativePath);

        // 服务端没有该region的记录
        if (serverMeta == null) {
            return false;
        }

        // 客户端没有元数据（新region），需要同步
        if (clientMeta == null) {
            return true;
        }

        // 哈希值一致 → 文件内容相同，不同步
        if (serverMeta.hash().equals(clientMeta.hash())) {
            LOGGER.debug("Skip sync {}: hash match (server={}, client={})",
                relativePath, serverMeta.hash(), clientMeta.hash());
            return false;
        }

        // 哈希值不一致 → 检查时间戳
        // 客户端时间戳比服务端旧，需要同步
        if (clientMeta.timestampSeconds() < serverMeta.timestampSeconds()) {
            LOGGER.debug("Need sync {}: client ts={} < server ts={}",
                relativePath, clientMeta.timestampSeconds(), serverMeta.timestampSeconds());
            return true;
        }

        // 客户端时间戳不比服务端旧，保留客户端数据（客户端可能探索了新内容）
        LOGGER.debug("Skip sync {}: client ts={} >= server ts={} (client has newer data)",
            relativePath, clientMeta.timestampSeconds(), serverMeta.timestampSeconds());
        return false;
    }

    /**
     * 清除缓存
     */
    public void clear() {
        cache.clear();
        save();
    }

    /**
     * 重置单例实例
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.cache.clear();
            instance = null;
            LOGGER.info("GenerationCache instance reset");
        }
    }
}