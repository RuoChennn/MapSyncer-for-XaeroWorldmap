package com.mapsyncer.server;

import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 生成缓存 - 缓存每个region的生成时间戳和CRC32哈希值
 *
 * 用于同步时比对：
 * - 哈希值一致 → 不同步（文件内容相同）
 * - 哈希值不一致 → 检查时间戳，客户端旧于服务端则同步
 *
 * 缓存格式：
 * - 存储：relativePath -> RegionMeta
 * - 文件：generation_cache.properties
 * - 格式：dimension/region_x_z = timestamp_seconds:hash
 *
 * 内存管理：
 * - 最大缓存条目数限制（MAX_CACHE_REGIONS）
 * - 超过限制时自动清理最旧的条目
 */
public class GenerationCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationCache.class);

    /** 最大缓存条目数（防止大型服务器内存占用过高） */
    private static final int MAX_CACHE_REGIONS = 50000;

    /** 单例实例 */
    private static volatile GenerationCache instance;

    /** 缓存文件路径 */
    private final Path cacheFile;

    /** 缓存数据：relativePath -> RegionMeta */
    private final Map<String, RegionMeta> cache = new HashMap<>();

    /**
     * Region元数据：时间戳(秒) + CRC32哈希
     *
     * 与TimestampHashEntry功能相同，保留此类型用于兼容。
     */
    public record RegionMeta(long timestampSeconds, String hash) {
        /**
         * 从字符串解析Region元数据
         *
         * @param value 字符串值（格式：timestamp:hash）
         * @return 解析后的RegionMeta，解析失败返回null
         */
        public static RegionMeta parse(String value) {
            TimestampHashEntry entry = PropertiesCacheIO.parseTimestampHash(value);
            return entry != null ? new RegionMeta(entry.timestampSeconds(), entry.hash()) : null;
        }

        /**
         * 格式化Region元数据为字符串
         *
         * @return 格式化字符串（timestamp:hash）
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }

    /**
     * 私有构造方法
     *
     * @param cacheDir 缓存目录路径
     */
    private GenerationCache(Path cacheDir) {
        this.cacheFile = cacheDir.resolve("generation_cache.properties");
        load();
    }

    /**
     * 获取单例实例
     *
     * @param cacheDir 缓存目录路径
     * @return 生成缓存实例
     */
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
     *
     * 使用PropertiesCacheIO加载缓存数据。
     */
    private void load() {
        Map<String, TimestampHashEntry> loaded = PropertiesCacheIO.load(cacheFile, PropertiesCacheIO::parseTimestampHash);
        for (Map.Entry<String, TimestampHashEntry> entry : loaded.entrySet()) {
            cache.put(entry.getKey(), new RegionMeta(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
    }

    /**
     * 保存缓存到文件
     *
     * 使用PropertiesCacheIO保存缓存数据。
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
     *
     * @param relativePath 相对路径（如：dimension/regionX_regionZ）
     * @param timestampSeconds 时间戳（秒）
     * @param hash CRC32哈希值
     */
    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
        trimIfOverLimit();
    }

    /**
     * 如果缓存超过限制，清理最旧的条目
     *
     * 保留最新的条目，因为它们更可能被请求同步。
     */
    private void trimIfOverLimit() {
        if (cache.size() <= MAX_CACHE_REGIONS) {
            return;
        }

        int toRemove = cache.size() - MAX_CACHE_REGIONS;
        LOGGER.info("Cache size {} exceeds limit {}, trimming {} oldest entries",
            cache.size(), MAX_CACHE_REGIONS, toRemove);

        // 按时间戳排序，删除最旧的条目
        cache.entrySet().stream()
            .sorted((a, b) -> Long.compare(a.getValue().timestampSeconds(), b.getValue().timestampSeconds()))
            .limit(toRemove)
            .map(Map.Entry::getKey)
            .forEach(cache::remove);

        LOGGER.info("Cache trimmed to {} entries", cache.size());
    }

    /**
     * 更新region的缓存信息（自动计算哈希）
     *
     * 使用HashUtils计算文件CRC32哈希。
     *
     * @param relativePath 相对路径
     * @param filePath 文件路径
     * @param timestampSeconds 时间戳（秒）
     */
    public void updateWithHash(String relativePath, Path filePath, long timestampSeconds) {
        String hash = HashUtils.computeFileHash(filePath);
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
        LOGGER.debug("Updated cache for {}: ts={}, hash={}", relativePath, timestampSeconds, hash);
    }

    /**
     * 获取region的元数据
     *
     * @param relativePath 相对路径
     * @return Region元数据，不存在返回null
     */
    public RegionMeta getMeta(String relativePath) {
        return cache.get(relativePath);
    }

    /**
     * 获取所有缓存数据
     *
     * <p>返回不可修改视图，避免创建完整副本浪费内存。</p>
     * <p>如果需要修改数据，请使用 update() 方法。</p>
     *
     * @return 缓存数据的不可修改视图
     */
    public Map<String, RegionMeta> getAll() {
        return Collections.unmodifiableMap(cache);
    }

    /**
     * 检查是否需要同步
     *
     * 同步逻辑：
     * - 服务端无缓存 → 不同步（服务端无数据）
     * - 客户端无元数据 → 同步（新区域）
     * - 哈希值一致 → 不同步（内容相同）
     * - 客户端时间戳旧于服务端 → 同步
     * - 客户端时间戳新于服务端 → 不同步
     *
     * @param relativePath 相对路径
     * @param clientMeta 客户端元数据
     * @return true表示需要同步
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
     *
     * 清除缓存数据并释放单例引用，用于服务器停止时的清理。
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.cache.clear();
            instance = null;
            LOGGER.info("GenerationCache instance reset");
        }
    }
}