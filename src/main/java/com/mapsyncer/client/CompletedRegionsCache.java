package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 用于跟踪本地已完全生成的区域的缓存。
 * 一旦区域被标记为完成，在未来的同步中将不会从服务器请求该区域。
 *
 * <p>缓存文件格式：使用二进制格式存储，每个条目包含维度名称和区域坐标。</p>
 *
 * <p>缓存文件位置：位于地图目录下的 completed_regions.cache 文件中。</p>
 */
public class CompletedRegionsCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompletedRegionsCache.class);

    /** 缓存文件名称 */
    private static final String CACHE_FILE_NAME = "completed_regions.cache";

    /** 缓存文件路径 */
    private final Path cacheFile;

    /** 已完成的区域集合，键格式为 "dimension:regionX_regionZ" */
    private final Set<String> completedRegions;

    /** 是否已从磁盘加载缓存 */
    private boolean loaded = false;

    /**
     * 构造一个新的已完成区域缓存实例。
     *
     * @param mapDirectory 地图目录路径，缓存文件将存储在此目录下
     */
    public CompletedRegionsCache(Path mapDirectory) {
        this.cacheFile = mapDirectory.resolve(CACHE_FILE_NAME);
        this.completedRegions = new HashSet<>();
    }

    /**
     * 从磁盘加载缓存数据。
     * 如果缓存文件存在，读取其中的已完成区域列表。
     * 加载是惰性的，只会在第一次需要时执行。
     */
    public void load() {
        if (loaded) return;

        if (Files.exists(cacheFile)) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(cacheFile))) {
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    String regionKey = in.readUTF();
                    completedRegions.add(regionKey);
                }
                LOGGER.debug("Loaded {} completed regions from cache", count);
            } catch (IOException e) {
                LOGGER.warn("Failed to load completed regions cache, starting fresh", e);
                completedRegions.clear();
            }
        }

        loaded = true;
    }

    /**
     * 将缓存数据保存到磁盘。
     * 使用二进制格式存储所有已完成的区域键。
     */
    public void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(cacheFile))) {
                out.writeInt(completedRegions.size());
                for (String regionKey : completedRegions) {
                    out.writeUTF(regionKey);
                }
            }
            LOGGER.debug("Saved {} completed regions to cache", completedRegions.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save completed regions cache", e);
        }
    }

    /**
     * 检查指定区域是否被标记为已完成。
     *
     * @param dimension 维度名称（例如 "null"、"DIM-1"、"DIM1"）
     * @param regionX   区域X坐标
     * @param regionZ   区域Z坐标
     * @return 如果区域已完成且不应同步，返回 true；否则返回 false
     */
    public boolean isComplete(String dimension, int regionX, int regionZ) {
        load();
        String key = formatKey(dimension, regionX, regionZ);
        return completedRegions.contains(key);
    }

    /**
     * 将区域标记为已完成。
     * 标记后的区域在未来的同步中将被跳过。
     *
     * @param dimension 维度名称
     * @param regionX   区域X坐标
     * @param regionZ   区域Z坐标
     */
    public void markComplete(String dimension, int regionX, int regionZ) {
        load();
        String key = formatKey(dimension, regionX, regionZ);
        if (!completedRegions.contains(key)) {
            completedRegions.add(key);
            LOGGER.debug("Marked region as complete: {}", key);
            save();
        }
    }

    /**
     * 从已完成集合中移除区域标记（如果需要重新同步）。
     *
     * @param dimension 维度名称
     * @param regionX   区域X坐标
     * @param regionZ   区域Z坐标
     */
    public void unmarkComplete(String dimension, int regionX, int regionZ) {
        load();
        String key = formatKey(dimension, regionX, regionZ);
        if (completedRegions.remove(key)) {
            LOGGER.debug("Unmarked region: {}", key);
            save();
        }
    }

    /**
     * 清空所有缓存的已完成区域标记。
     * 同时删除磁盘上的缓存文件。
     */
    public void clear() {
        completedRegions.clear();
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete cache file", e);
        }
        LOGGER.info("Cleared completed regions cache");
    }

    /**
     * 获取指定维度的所有已完成区域键。
     *
     * @param dimension 维度名称
     * @return 区域键集合（格式为 "regionX_regionZ"）
     */
    public Set<String> getCompletedRegions(String dimension) {
        load();
        Set<String> result = new HashSet<>();
        for (String key : completedRegions) {
            if (key.startsWith(dimension + ":")) {
                result.add(key.substring(dimension.length() + 1));
            }
        }
        return result;
    }

    /**
     * 格式化区域的唯一键。
     * 格式为 "dimension:regionX_regionZ"。
     *
     * @param dimension 维度名称
     * @param regionX   区域X坐标
     * @param regionZ   区域Z坐标
     * @return 格式化的区域键字符串
     */
    private static String formatKey(String dimension, int regionX, int regionZ) {
        return dimension + ":" + regionX + "_" + regionZ;
    }

    /**
     * 获取缓存的已完成区域总数。
     *
     * @return 已完成区域的数量
     */
    public int size() {
        load();
        return completedRegions.size();
    }
}