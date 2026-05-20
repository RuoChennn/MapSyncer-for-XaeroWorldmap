package com.mapsyncer.server;

import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
    // 存储：relativePath -> RegionMeta
    private final Map<String, RegionMeta> cache = new HashMap<>();

    /**
     * Region元数据：时间戳(秒) + CRC32哈希
     * 与 TimestampHashEntry 功能相同，保留此类型用于兼容
     */
    public record RegionMeta(long timestampSeconds, String hash) {
        public static RegionMeta parse(String value) {
            TimestampHashEntry entry = PropertiesCacheIO.parseEntry(value);
            return entry != null ? new RegionMeta(entry.timestampSeconds(), entry.hash()) : null;
        }

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
     * 从文件加载缓存（使用 PropertiesCacheIO）
     */
    private void load() {
        Map<String, TimestampHashEntry> loaded = PropertiesCacheIO.load(cacheFile, PropertiesCacheIO::parseEntry);
        for (Map.Entry<String, TimestampHashEntry> entry : loaded.entrySet()) {
            cache.put(entry.getKey(), new RegionMeta(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
    }

    /**
     * 保存缓存到文件（使用 PropertiesCacheIO）
     */
    public void save() {
        Map<String, TimestampHashEntry> toSave = new HashMap<>();
        for (Map.Entry<String, RegionMeta> entry : cache.entrySet()) {
            toSave.put(entry.getKey(), new TimestampHashEntry(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
        PropertiesCacheIO.save(cacheFile, toSave, TimestampHashEntry::format,
            "Generation cache for map regions\nFormat: dimension/region_x_z = timestamp_seconds:hash\nHash is CRC32 of file content");
    }

    /**
     * 更新region的缓存信息
     */
    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
    }

    /**
     * 更新region的缓存信息（自动计算哈希，使用 HashUtils）
     */
    public void updateWithHash(String relativePath, Path filePath, long timestampSeconds) {
        String hash = HashUtils.computeFileHash(filePath);
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
        LOGGER.debug("Updated cache for {}: ts={}, hash={}", relativePath, timestampSeconds, hash);
    }

    /**
     * 获取region的元数据
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
     */
    public boolean needsSync(String relativePath, RegionMeta clientMeta) {
        RegionMeta serverMeta = cache.get(relativePath);

        if (serverMeta == null) {
            return false;
        }

        if (clientMeta == null) {
            return true;
        }

        if (serverMeta.hash().equals(clientMeta.hash())) {
            LOGGER.debug("Skip sync {}: hash match", relativePath);
            return false;
        }

        if (clientMeta.timestampSeconds() < serverMeta.timestampSeconds()) {
            LOGGER.debug("Need sync {}: client ts={} < server ts={}",
                relativePath, clientMeta.timestampSeconds(), serverMeta.timestampSeconds());
            return true;
        }

        LOGGER.debug("Skip sync {}: client has newer data", relativePath);
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