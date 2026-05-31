package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Properties 缓存基类
 *
 * <p>提供统一的缓存管理模板，子类只需实现：</p>
 * <ul>
 *   <li>{@link #getCacheFileName()} - 缓存文件名</li>
 *   <li>{@link #parseEntry(String)} - 解析缓存条目</li>
 *   <li>{@link #formatEntry(Object)} - 格式化缓存条目</li>
 *   <li>{@link #getFileHeader()} - 文件头注释</li>
 * </ul>
 *
 * <p>模板方法模式：基类管理加载、保存、清理的通用逻辑，子类定制具体解析规则。</p>
 *
 * @param <T> 缓存条目类型
 */
public abstract class PropertiesCacheBase<T> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /** 缓存文件路径 */
    protected final Path cacheFile;

    /** 缓存数据 */
    protected final Map<String, T> cache = new HashMap<>();

    /** 单例实例 */
    protected static volatile Object instance;

    /** 上次使用的目录（用于检测目录变化） */
    protected static volatile Path lastBaseDir = null;

    /**
     * 构造方法 - 初始化并加载缓存
     *
     * @param baseDir 缓存文件存放的基础目录
     */
    protected PropertiesCacheBase(Path baseDir) {
        this.cacheFile = baseDir.resolve(getCacheFileName());
        load();
    }

    /**
     * 获取缓存文件名
     *
     * @return 缓存文件名（如 "generation_cache.properties"）
     */
    protected abstract String getCacheFileName();

    /**
     * 解析缓存条目
     *
     * @param value 缓存值字符串
     * @return 解析后的缓存条目，解析失败返回 null
     */
    protected abstract T parseEntry(String value);

    /**
     * 格式化缓存条目为字符串
     *
     * @param entry 缓存条目
     * @return 格式化后的字符串
     */
    protected abstract String formatEntry(T entry);

    /**
     * 获取文件头注释
     *
     * @return Properties 文件头注释
     */
    protected abstract String getFileHeader();

    /**
     * 从文件加载缓存
     */
    protected void load() {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            logger.info("Cache file not found: {}", cacheFile);
            return;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                T entry = parseEntry(props.getProperty(key));
                if (entry != null) {
                    cache.put(key, entry);
                } else {
                    logger.warn("Invalid cache entry for {}: {}", key, props.getProperty(key));
                }
            }

            logger.info("Loaded {} entries from cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            logger.error("Failed to load cache file: {}", cacheFile, e);
        }
    }

    /**
     * 保存缓存到文件
     */
    public void save() {
        if (cacheFile == null) {
            logger.warn("Cache file path is null, skip saving");
            return;
        }

        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, T> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), formatEntry(entry.getValue()));
            }

            try (OutputStream os = Files.newOutputStream(cacheFile)) {
                props.store(os, getFileHeader());
            }

            logger.info("Saved {} entries to cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            logger.error("Failed to save cache file: {}", cacheFile, e);
        }
    }

    /**
     * 更新缓存条目
     *
     * @param key 缓存键
     * @param entry 缓存条目
     */
    public void update(String key, T entry) {
        cache.put(key, entry);
    }

    /**
     * 获取缓存条目
     *
     * @param key 缓存键
     * @return 缓存条目，不存在返回 null
     */
    public T get(String key) {
        return cache.get(key);
    }

    /**
     * 获取所有缓存数据（不可修改视图）
     *
     * @return 缓存数据的不可修改视图
     */
    public Map<String, T> getAll() {
        return Collections.unmodifiableMap(cache);
    }

    /**
     * 清除缓存
     */
    public void clear() {
        cache.clear();
        try {
            Files.deleteIfExists(cacheFile);
            logger.info("Cleared cache");
        } catch (IOException e) {
            logger.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    /**
     * 获取缓存大小
     *
     * @return 缓存条目数量
     */
    public int size() {
        return cache.size();
    }

    /**
     * 检查缓存是否为空
     *
     * @return true 表示缓存为空
     */
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    /**
     * 检查缓存文件是否存在
     *
     * @return true 表示缓存文件存在
     */
    public boolean cacheFileExists() {
        return Files.exists(cacheFile);
    }

    /**
     * 获取缓存文件路径
     *
     * @return 缓存文件路径
     */
    public Path getCacheFile() {
        return cacheFile;
    }

    /**
     * 重置单例实例
     *
     * <p>子类需要实现具体的重置逻辑</p>
     */
    public abstract void reset();
}