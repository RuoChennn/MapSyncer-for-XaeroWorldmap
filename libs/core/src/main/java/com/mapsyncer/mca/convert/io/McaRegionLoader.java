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
import com.mapsyncer.nbt.Tag;

import java.io.IOException;
import java.nio.file.Path;

public final class McaRegionLoader {

    private McaRegionLoader() {}

    public static MapRegionData load(Path mcaPath, int minBuildHeight, int worldTopY,
                                      LightMode lightMode,
                                      RegionConverterStandalone.CaveModeParams caveParams,
                                      boolean worldHasSkylight,
                                      BlockPropertyLookup blockLookup) throws IOException {
        MapRegionData data = new MapRegionData(minBuildHeight, lightMode);

        try (McaReader reader = McaReader.open(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            for (int localX = 0; localX < ConvertConstants.CHUNKS_PER_REGION; localX++) {
                for (int localZ = 0; localZ < ConvertConstants.CHUNKS_PER_REGION; localZ++) {
                    Tag.Compound nbt;
                    try {
                        nbt = reader.readChunkNbt(localX, localZ);
                    } catch (IOException e) {
                        continue;
                    }
                    if (nbt == null) {
                        continue;
                    }

                    ChunkDataParser.ChunkInfo chunkInfo = ChunkDataParser.parseChunk(
                        localX, localZ, nbt, worldHeightRange
                    );

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
}
