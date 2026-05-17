package com.mapsyncer.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存 MCA 文件的最后修改时间
 * 用于检测文件是否更新，触发增量重新生成
 */
public class McaTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaTimestampCache.class);
    private static final String CACHE_FILE_NAME = "mca_timestamps.cache";

    // 维度 -> 区域坐标 -> 最后修改时间 (毫秒)
    private final Map<String, Map<String, Long>> timestampCache = new ConcurrentHashMap<>();

    // 缓存文件路径
    private final Path cacheFilePath;

    // 单例实例
    private static volatile McaTimestampCache instance;

    public static McaTimestampCache getInstance(Path baseDir) {
        if (instance == null) {
            synchronized (McaTimestampCache.class) {
                if (instance == null) {
                    instance = new McaTimestampCache(baseDir);
                }
            }
        }
        return instance;
    }

    private McaTimestampCache(Path baseDir) {
        this.cacheFilePath = baseDir.resolve(CACHE_FILE_NAME);
        loadCache();
    }

    /**
     * 从文件加载缓存（使用 Properties 格式，人类可读）
     */
    private void loadCache() {
        if (!Files.exists(cacheFilePath)) {
            LOGGER.info("No existing timestamp cache found, will create new one");
            return;
        }

        try (InputStream is = Files.newInputStream(cacheFilePath)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                try {
                    // 时间戳以秒为单位存储，读取后转换为毫秒
                    long timestampSeconds = Long.parseLong(props.getProperty(key));
                    long timestampMillis = timestampSeconds * 1000;
                    // 解析键：格式为 "dimension/region_x_z"
                    String[] parts = key.split("/");
                    if (parts.length == 2) {
                        String dimension = parts[0];
                        String regionKey = parts[1];
                        timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                                     .put(regionKey, timestampMillis);
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid timestamp for {}: {}", key, props.getProperty(key));
                }
            }

            int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
            LOGGER.info("Loaded timestamp cache: {} dimensions, {} regions",
                timestampCache.size(), totalRegions);
        } catch (IOException e) {
            LOGGER.warn("Failed to load timestamp cache, will rebuild: {}", e.getMessage());
            timestampCache.clear();
        }
    }

    /**
     * 保存缓存到文件（使用 Properties 格式，人类可读）
     */
    public void saveCache() {
        try {
            Files.createDirectories(cacheFilePath.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, Map<String, Long>> dimEntry : timestampCache.entrySet()) {
                String dimension = dimEntry.getKey();
                for (Map.Entry<String, Long> regionEntry : dimEntry.getValue().entrySet()) {
                    // 格式：dimension/region_x_z = timestamp (秒)
                    String key = dimension + "/" + regionEntry.getKey();
                    // 存储时转换为秒，更易读
                    long timestampSeconds = regionEntry.getValue() / 1000;
                    props.setProperty(key, String.valueOf(timestampSeconds));
                }
            }

            // 先写入临时文件，再原子替换
            Path tempFile = cacheFilePath.resolveSibling(CACHE_FILE_NAME + ".temp");
            try (OutputStream os = Files.newOutputStream(tempFile)) {
                props.store(os, "MCA file modification timestamps (seconds since epoch)\nFormat: dimension/region_x_z = timestamp");
            }
            Files.move(tempFile, cacheFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
            LOGGER.info("Saved timestamp cache: {} dimensions, {} regions to {}",
                timestampCache.size(), totalRegions, cacheFilePath);
        } catch (IOException e) {
            LOGGER.error("Failed to save timestamp cache: {}", e.getMessage());
        }
    }

    /**
     * 获取 MCA 文件的最后修改时间
     * @param mcaPath MCA 文件路径
     * @return 最后修改时间 (毫秒)，如果文件不存在返回 -1
     */
    public long getFileTimestamp(Path mcaPath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(mcaPath, BasicFileAttributes.class);
            FileTime lastModified = attrs.lastModifiedTime();
            return lastModified.toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 检查区域是否需要重新生成
     * @param dimension 维度名称
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @param mcaPath MCA 文件路径
     * @return true 如果需要重新生成
     */
    public boolean needsRegeneration(String dimension, int regionX, int regionZ, Path mcaPath) {
        if (!Files.exists(mcaPath)) {
            return false;  // 文件不存在，不需要生成
        }

        String regionKey = regionX + "_" + regionZ;
        long currentTimestamp = getFileTimestamp(mcaPath);

        if (currentTimestamp < 0) {
            return true;  // 无法读取时间，保守地重新生成
        }

        Map<String, Long> dimCache = timestampCache.get(dimension);
        if (dimCache == null) {
            LOGGER.debug("No cached timestamp for dimension {}, will regenerate", dimension);
            return true;  // 维度缓存不存在，需要生成
        }

        Long cachedTimestamp = dimCache.get(regionKey);
        if (cachedTimestamp == null) {
            LOGGER.debug("No cached timestamp for region {} in {}, will generate", regionKey, dimension);
            return true;  // 区域缓存不存在，需要生成
        }

        if (currentTimestamp > cachedTimestamp) {
            LOGGER.info("Region {} in {} has been updated (cached={}, current={}), will regenerate",
                regionKey, dimension, cachedTimestamp, currentTimestamp);
            return true;  // 文件已更新
        }

        return false;  // 文件未更新
    }

    /**
     * 更新区域的缓存时间戳
     * @param dimension 维度名称
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @param mcaPath MCA 文件路径
     */
    public void updateTimestamp(String dimension, int regionX, int regionZ, Path mcaPath) {
        long timestamp = getFileTimestamp(mcaPath);
        if (timestamp < 0) {
            LOGGER.warn("Could not get timestamp for {}", mcaPath);
            return;
        }

        String regionKey = regionX + "_" + regionZ;
        timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                      .put(regionKey, timestamp);

        LOGGER.debug("Updated timestamp cache for {} / {}: {}", dimension, regionKey, timestamp);
    }

    /**
     * 批量扫描并更新所有区域的时间戳
     * @param dimension 维度名称
     * @param regionDir 区域目录
     * @return 需要重新生成的区域列表
     */
    public java.util.List<RegionScanner.RegionCoords> scanAndUpdate(String dimension, Path regionDir) {
        java.util.List<RegionScanner.RegionCoords> needsRegeneration = new java.util.ArrayList<>();
        Map<String, Long> dimCache = timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());

        if (!Files.exists(regionDir)) {
            LOGGER.warn("Region directory not found: {}", regionDir);
            return needsRegeneration;
        }

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path mcaFile : stream) {
                String fileName = mcaFile.getFileName().toString();
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$").matcher(fileName);

                if (matcher.matches()) {
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    String regionKey = regionX + "_" + regionZ;

                    long currentTimestamp = getFileTimestamp(mcaFile);
                    Long cachedTimestamp = dimCache.get(regionKey);

                    if (cachedTimestamp == null || currentTimestamp > cachedTimestamp) {
                        needsRegeneration.add(new RegionScanner.RegionCoords(regionX, regionZ));
                        dimCache.put(regionKey, currentTimestamp);

                        if (cachedTimestamp != null) {
                            LOGGER.info("Detected update in {} / {}: cached={}, current={}",
                                dimension, regionKey, cachedTimestamp, currentTimestamp);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }

        return needsRegeneration;
    }

    /**
     * 清空缓存
     */
    public void clearCache() {
        timestampCache.clear();
        try {
            Files.deleteIfExists(cacheFilePath);
            LOGGER.info("Cleared timestamp cache");
        } catch (IOException e) {
            LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        int totalDimensions = timestampCache.size();
        int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
        return String.format("Timestamp cache: %d dimensions, %d regions cached", totalDimensions, totalRegions);
    }

    /**
     * Reset singleton instance to release memory.
     * Called when server stops to prevent memory leaks on dedicated servers.
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.timestampCache.clear();
            instance = null;
            LOGGER.info("McaTimestampCache instance reset");
        }
    }
}