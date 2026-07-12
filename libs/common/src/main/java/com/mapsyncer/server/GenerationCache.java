package com.mapsyncer.server;

import com.mapsyncer.config.CacheConfig;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成缓存 - 缓存每个region的生成时间戳和CRC32哈希值
 *
 * 用于同步时比对：
 * - 哈希值一致 → 不同步（文件内容相同）
 * - 哈希值不一致 → 检查时间戳，客户端旧于服务端则同步
 *
 * 缓存格式：
 * - 存储：relativePath -> TimestampHashEntry
 * - 文件：generation_cache.properties
 * - 格式：dimension/region_x_z = timestamp_seconds:hash
 *
 * 内存管理：
 * - 使用 {@link CacheConfig#MAX_REGION_META_CACHE} 作为上限
 * - 超过限制时自动清理最旧的条目
 */
public class GenerationCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationCache.class);

    /** 最大缓存条目数（使用集中配置，便于管理） */
    private static final int MAX_CACHE_REGIONS = CacheConfig.MAX_REGION_META_CACHE;

    /** 单例实例 */
    private static volatile GenerationCache instance;

    /** 缓存文件路径 */
    private final Path cacheFile;

    /** 缓存数据：relativePath -> TimestampHashEntry（线程安全） */
    private final Map<String, TimestampHashEntry> cache = new ConcurrentHashMap<>();

    private GenerationCache(Path cacheDir) {
        this.cacheFile = cacheDir.resolve("generation_cache.properties");
        // load 抛异常时 instance 保持 null（volatile 保证构造完成后才赋值），
        // 下次 getInstance 会重试。不得在此构造器中添加可能抛异常的逻辑而不处理。
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
        cache.putAll(loaded);
    }

    /**
     * 保存缓存到文件
     *
     * 使用PropertiesCacheIO保存缓存数据。
     */
    public void save() {
        PropertiesCacheIO.save(cacheFile, new HashMap<>(cache), TimestampHashEntry::format,
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
        cache.put(relativePath, new TimestampHashEntry(timestampSeconds, hash));
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
        cache.put(relativePath, new TimestampHashEntry(timestampSeconds, hash));
        LOGGER.debug("Updated cache for {}: ts={}, hash={}", relativePath, timestampSeconds, hash);
    }

    /**
     * 获取region的元数据
     *
     * @param relativePath 相对路径
     * @return Region元数据，不存在返回null
     */
    public TimestampHashEntry getMeta(String relativePath) {
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
    public Map<String, TimestampHashEntry> getAll() {
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
    public boolean needsSync(String relativePath, TimestampHashEntry clientMeta) {
        TimestampHashEntry serverMeta = cache.get(relativePath);

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

        if (clientMeta.timestampSeconds() >= serverMeta.timestampSeconds()) {
            LOGGER.debug("Skip sync {}: client ts {} >= server ts {}",
                    relativePath, clientMeta.timestampSeconds(), serverMeta.timestampSeconds());
            return false;
        }

        LOGGER.debug("Need sync {}: hash mismatch (client={}, server={})",
                relativePath, clientMeta.hash(), serverMeta.hash());
        return true;
    }

    /**
     * 移除单条缓存记录（不写盘；调用方在批次结束时 {@link #save()}）。
     */
    public void remove(String relativePath) {
        if (relativePath != null) {
            cache.remove(relativePath);
        }
    }

    /**
     * 删除 cache 中有记录但磁盘 zip 无效或缺失的条目。
     *
     * @param cacheRoot server_map_cache 根目录
     * @return 移除条目数
     */
    public int pruneInvalidEntries(Path cacheRoot) {
        if (cacheRoot == null || !Files.exists(cacheRoot)) {
            return 0;
        }
        int removed = 0;
        for (String key : List.copyOf(cache.keySet())) {
            Path zipPath = resolveZipPath(cacheRoot, key);
            if (zipPath == null || !HashUtils.isValidRegionZip(zipPath)) {
                cache.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            save();
            LOGGER.info("Pruned {} invalid generation_cache entries under {}", removed, cacheRoot);
        }
        return removed;
    }

    private static Path resolveZipPath(Path cacheRoot, String relativePath) {
        String normalized = relativePath.replace("\\", "/");
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return null;
        }
        Path dimDir = cacheRoot.resolve(parts[0]);
        if (!Files.isDirectory(dimDir)) {
            return null;
        }
        String fileName = parts[parts.length - 1] + ".zip";

        // 服务端缓存目录是扁平的：zip 直接位于维度目录下，无 mw$ 中间层。
        //   地表：{dimDir}/{region}.zip
        //   洞穴：{dimDir}/caves/{layer}/{region}.zip
        Path flatPath;
        if (parts.length == 2) {
            flatPath = dimDir.resolve(fileName);
        } else if (parts.length == 4 && "caves".equals(parts[1])) {
            flatPath = dimDir.resolve("caves").resolve(parts[2]).resolve(fileName);
        } else {
            flatPath = null;
        }
        if (flatPath != null && Files.isRegularFile(flatPath)) {
            return flatPath;
        }

        // 回退：mw$ 是客户端 Xaero Worldmap 的目录约定（{dimDir}/mw$id/{region}.zip）。
        // 用于兼容含 mw$ 子目录的布局。
        Path mwDir;
        try (var stream = Files.list(dimDir)) {
            mwDir = stream.filter(p -> p.getFileName().toString().startsWith("mw$"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return flatPath;
        }
        if (mwDir == null) {
            return flatPath;
        }
        if (parts.length == 2) {
            return mwDir.resolve(fileName);
        }
        if (parts.length == 4 && "caves".equals(parts[1])) {
            return mwDir.resolve("caves").resolve(parts[2]).resolve(fileName);
        }
        return flatPath;
    }

    /**
     * 获取最后一次地图生成的时间戳。
     * 遍历所有缓存的 region 取最大 timestamp。
     *
     * @return 最后生成时间戳（秒），无缓存时返回 0
     */
    public long getLastGenerationTime() {
        return cache.values().stream()
            .mapToLong(TimestampHashEntry::timestampSeconds)
            .max().orElse(0);
    }

    /**
     * 清除缓存
     */
    public void clear() {
        cache.clear();
        save();
    }

    /**
     * 移除以指定前缀开头的所有缓存记录。
     *
     * @param prefix 前缀（如 null/、DIM-1/）
     * @return 移除的记录数
     */
    public int removeByPrefix(String prefix) {
        int removed = 0;
        for (String key : List.copyOf(cache.keySet())) {
            if (key.startsWith(prefix)) {
                cache.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            save();
        }
        return removed;
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