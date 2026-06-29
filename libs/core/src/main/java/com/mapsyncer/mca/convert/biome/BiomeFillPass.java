package com.mapsyncer.mca.convert.biome;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.convert.model.MapRegionData;

import static com.mapsyncer.mca.convert.model.ConvertConstants.CHUNKS_PER_REGION;
import static com.mapsyncer.mca.convert.model.ConvertConstants.DEFAULT_BIOME;
import static com.mapsyncer.mca.convert.model.ConvertConstants.REGION_SIZE_BLOCKS;

/**
 * 扫描完成后按 topHeight 填充 biome，对齐 Xaero fillBiomes 两阶段流程。
 */
public final class BiomeFillPass {

    private BiomeFillPass() {}

    public static void fill(MapRegionData data) {
        for (int rx = 0; rx < REGION_SIZE_BLOCKS; rx++) {
            for (int rz = 0; rz < REGION_SIZE_BLOCKS; rz++) {
                if (!data.hasData[rx][rz]) {
                    continue;
                }

                int chunkX = rx >> 4;
                int chunkZ = rz >> 4;
                if (chunkX >= CHUNKS_PER_REGION || chunkZ >= CHUNKS_PER_REGION) {
                    data.biomeNames[rx][rz] = DEFAULT_BIOME;
                    continue;
                }

                ChunkDataParser.ChunkInfo chunk = data.chunkGrid[chunkX][chunkZ];
                if (chunk == null) {
                    data.biomeNames[rx][rz] = DEFAULT_BIOME;
                    continue;
                }

                int lx = rx & 0xF;
                int lz = rz & 0xF;
                int height = data.heightMap[rx][rz];
                int topY = data.topBlockY[rx][rz];
                int sampleY = topY >= 0 ? topY : height;

                data.biomeNames[rx][rz] = BiomeQuartResolver.resolve(chunk, lx, sampleY, lz);
            }
        }
    }
}
