package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScanner.class);
    private static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$");

    public record RegionCoords(int x, int z) {
    }

    public record DimensionRegions(net.minecraft.resources.ResourceKey<Level> dimension, List<RegionCoords> regions) {
    }

    public static List<DimensionRegions> scanAllDimensions(MinecraftServer server) {
        List<DimensionNames> dimNames = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimId = level.dimension().location().getPath();
            if (!dimNames.stream().anyMatch(d -> d.name().equals(dimId))) {
                dimNames.add(new DimensionNames(dimId, level.dimension()));
            }
        }

        List<DimensionRegions> result = new ArrayList<>();
        for (DimensionNames dn : dimNames) {
            List<RegionCoords> regions = scanRegionDir(server.getWorldPath(LevelResource.ROOT), dn.name());
            result.add(new DimensionRegions(dn.key(), regions));
        }
        return result;
    }

    public static List<RegionCoords> scanDimension(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        String dimId = level.dimension().location().getPath();
        return scanRegionDir(worldRoot, dimId);
    }

    /**
     * 获取指定维度的region目录路径
     */
    public static Path getRegionDir(ServerLevel level) {
        try {
            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
            if (!Files.exists(worldRoot)) return null;
            worldRoot = worldRoot.toRealPath();
            String dimId = level.dimension().location().getPath();
            Path regionDir = ".".equals(dimId) ? worldRoot.resolve("region") : worldRoot.resolve(dimId).resolve("region");
            if (!Files.exists(regionDir)) regionDir = worldRoot.resolve("region");
            return Files.exists(regionDir) ? regionDir.toRealPath() : null;
        } catch (IOException e) {
            LOGGER.error("Failed to get region directory", e);
            return null;
        }
    }

    private static List<RegionCoords> scanRegionDir(Path worldRoot, String dimId) {
        Path regionDir = worldRoot.resolve(dimId).resolve("region");
        if (!Files.exists(regionDir)) {
            regionDir = worldRoot.resolve("region");
        }
        if (!Files.exists(regionDir)) {
            LOGGER.warn("Region directory not found for dimension: {}", dimId);
            return List.of();
        }
        return scanRegionDirectory(regionDir);
    }

    public static List<RegionCoords> scanRegionDirectory(Path regionDir) {
        List<RegionCoords> regions = new ArrayList<>();
        if (!Files.exists(regionDir)) {
            return regions;
        }

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                Matcher matcher = REGION_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    regions.add(new RegionCoords(regionX, regionZ));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }

        return regions;
    }

    private record DimensionNames(String name, net.minecraft.resources.ResourceKey<Level> key) {}
}
