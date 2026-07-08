package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ClientContributionCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientContributionCollector.class);

    private ClientContributionCollector() {
    }

    public static List<ContributionDataPayload> collect(int requestId, ContributionRegionMeta meta, Path serverDir) {
        if (meta == null || serverDir == null) {
            return List.of();
        }
        Path file = resolveClientRegion(serverDir, meta);
        if (!Files.exists(file)) {
            return List.of();
        }

        try {
            byte[] data = Files.readAllBytes(file);
            String hash = HashUtils.computeHash(data);
            if (!HashUtils.isValidHash(hash) || hash.equals(meta.serverHash())) {
                return List.of();
            }

            long timestampSeconds = resolveLogicalTimestamp(serverDir, meta.relativePath(), hash, file);
            if (timestampSeconds <= meta.serverTimestampSeconds()) {
                return List.of();
            }

            ChunkMapData chunk = new ChunkMapData(
                    meta.regionX(),
                    meta.regionZ(),
                    meta.dimension(),
                    data,
                    timestampSeconds,
                    meta.caveLayer()
            );
            List<ContributionDataPayload> payloads = new ArrayList<>();
            for (ChunkMapData part : ChunkMapData.split(chunk)) {
                payloads.add(new ContributionDataPayload(
                        requestId,
                        part,
                        meta.relativePath(),
                        meta.serverTimestampSeconds(),
                        meta.serverHash()
                ));
            }
            return payloads;
        } catch (Exception e) {
            LOGGER.warn("Failed to collect contribution for region {} at {}", meta.relativePath(), file, e);
            return List.of();
        }
    }

    private static Path resolveClientRegion(Path serverDir, ContributionRegionMeta meta) {
        String[] parts = meta.relativePath().split("/");
        Path dimDir = serverDir.resolve(parts[0]);
        Path mwDir = findMwDir(dimDir);
        if (mwDir == null) {
            mwDir = dimDir.resolve("mw$0");
        }
        String fileName = meta.regionX() + "_" + meta.regionZ() + ".zip";
        if (meta.caveLayer() == Integer.MAX_VALUE) {
            return mwDir.resolve(fileName);
        }
        return mwDir.resolve("caves")
                .resolve(String.valueOf(meta.caveLayer()))
                .resolve(fileName);
    }

    private static Path findMwDir(Path dimDir) {
        if (dimDir == null || !Files.exists(dimDir)) {
            return null;
        }
        try (var dirs = Files.list(dimDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("mw$"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            LOGGER.warn("Failed to scan Xaero mw directory {}", dimDir, e);
            return null;
        }
    }

    private static long resolveLogicalTimestamp(
            Path serverDir,
            String relativePath,
            String currentHash,
            Path file
    ) throws Exception {
        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        TimestampHashEntry cached = cache.get(relativePath);
        if (cached != null && cached.hash().equals(currentHash)) {
            return cached.timestampSeconds();
        }
        return Files.getLastModifiedTime(file).toMillis() / 1000;
    }
}
