package com.mapsyncer.mca.convert.io;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.McaReader;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.convert.biome.BiomeFillPass;
import com.mapsyncer.mca.convert.model.ConvertConstants;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.scan.ChunkColumnScanner;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import com.mapsyncer.nbt.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class McaRegionLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaRegionLoader.class);

    private McaRegionLoader() {}

    public record PassMapData(RegionScanPass pass, MapRegionData data) {}

    public static MapRegionData load(Path mcaPath, int minBuildHeight, int worldTopY,
                                      LightMode lightMode,
                                      RegionConverterStandalone.CaveModeParams caveParams,
                                      boolean worldHasSkylight,
                                      BlockPropertyLookup blockLookup) throws IOException {
        MapRegionData data = new MapRegionData(minBuildHeight, lightMode, caveParams);

        try (McaReader reader = McaReader.open(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            ChunkDataParser.ChunkInfo[][] chunks = readAllChunks(reader, worldHeightRange);
            for (int localX = 0; localX < ConvertConstants.CHUNKS_PER_REGION; localX++) {
                for (int localZ = 0; localZ < ConvertConstants.CHUNKS_PER_REGION; localZ++) {
                    ChunkDataParser.ChunkInfo chunkInfo = chunks[localX][localZ];
                    if (chunkInfo == null) {
                        continue;
                    }
                    ChunkColumnScanner.scan(data, chunkInfo, minBuildHeight, worldTopY,
                        lightMode, caveParams, worldHasSkylight, blockLookup);
                }
            }
        }

        BiomeFillPass.fill(data);
        return data;
    }

    /**
     * 单次 MCA 解析，按多个扫描 pass 输出多份 MapRegionData。
     */
    public static List<PassMapData> loadMulti(Path mcaPath, int minBuildHeight, int worldTopY,
                                               boolean worldHasSkylight,
                                               BlockPropertyLookup blockLookup,
                                               List<RegionScanPass> passes) throws IOException {
        if (passes.isEmpty()) {
            return List.of();
        }

        List<PassMapData> results = new ArrayList<>(passes.size());
        for (RegionScanPass pass : passes) {
            results.add(new PassMapData(pass, new MapRegionData(minBuildHeight, pass.lightMode(), pass.caveParams())));
        }

        try (McaReader reader = McaReader.open(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            ChunkDataParser.ChunkInfo[][] chunks = readAllChunks(reader, worldHeightRange);

            for (int localX = 0; localX < ConvertConstants.CHUNKS_PER_REGION; localX++) {
                for (int localZ = 0; localZ < ConvertConstants.CHUNKS_PER_REGION; localZ++) {
                    ChunkDataParser.ChunkInfo chunkInfo = chunks[localX][localZ];
                    if (chunkInfo == null) {
                        continue;
                    }
                    for (PassMapData passData : results) {
                        RegionScanPass pass = passData.pass();
                        ChunkColumnScanner.scan(
                            passData.data(), chunkInfo, minBuildHeight, worldTopY,
                            pass.lightMode(), pass.caveParams(), worldHasSkylight, blockLookup,
                            pass.verticalBounds());
                    }
                }
            }
        }

        for (PassMapData passData : results) {
            BiomeFillPass.fill(passData.data());
        }
        return results;
    }

    private static ChunkDataParser.ChunkInfo[][] readAllChunks(McaReader reader, int worldHeightRange)
            throws IOException {
        ChunkDataParser.ChunkInfo[][] grid =
            new ChunkDataParser.ChunkInfo[ConvertConstants.CHUNKS_PER_REGION][ConvertConstants.CHUNKS_PER_REGION];

        for (int localX = 0; localX < ConvertConstants.CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < ConvertConstants.CHUNKS_PER_REGION; localZ++) {
                Tag.Compound nbt;
                try {
                    nbt = reader.readChunkNbt(localX, localZ);
                } catch (IOException e) {
                    LOGGER.warn("Failed to read chunk ({}, {}) from region file, skipping: {}",
                            localX, localZ, e.getMessage());
                    continue;
                }
                if (nbt == null) {
                    continue;
                }
                grid[localX][localZ] = ChunkDataParser.parseChunk(localX, localZ, nbt, worldHeightRange);
            }
        }
        return grid;
    }
}
