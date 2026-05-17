package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Cache for tracking regions that are completely generated locally.
 * Once a region is marked as complete, it won't be requested from server in future syncs.
 */
public class CompletedRegionsCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompletedRegionsCache.class);

    private static final String CACHE_FILE_NAME = "completed_regions.cache";

    private final Path cacheFile;
    private final Set<String> completedRegions;
    private boolean loaded = false;

    public CompletedRegionsCache(Path mapDirectory) {
        this.cacheFile = mapDirectory.resolve(CACHE_FILE_NAME);
        this.completedRegions = new HashSet<>();
    }

    /**
     * Load the cache from disk.
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
     * Save the cache to disk.
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
     * Check if a region is marked as complete.
     *
     * @param dimension dimension name (e.g., "null", "DIM-1")
     * @param regionX   region X coordinate
     * @param regionZ   region Z coordinate
     * @return true if region is complete and should not be synced
     */
    public boolean isComplete(String dimension, int regionX, int regionZ) {
        load();
        String key = formatKey(dimension, regionX, regionZ);
        return completedRegions.contains(key);
    }

    /**
     * Mark a region as complete.
     *
     * @param dimension dimension name
     * @param regionX   region X coordinate
     * @param regionZ   region Z coordinate
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
     * Remove a region from the complete set (if it needs re-sync).
     *
     * @param dimension dimension name
     * @param regionX   region X coordinate
     * @param regionZ   region Z coordinate
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
     * Clear all cached regions.
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
     * Get all completed region keys for a dimension.
     *
     * @param dimension dimension name
     * @return set of region keys (format: "regionX_regionZ")
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
     * Format a unique key for a region.
     */
    private static String formatKey(String dimension, int regionX, int regionZ) {
        return dimension + ":" + regionX + "_" + regionZ;
    }

    /**
     * Get the total number of cached complete regions.
     */
    public int size() {
        load();
        return completedRegions.size();
    }
}