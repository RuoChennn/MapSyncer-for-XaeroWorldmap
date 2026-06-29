package com.mapsyncer.mca.convert.biome;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.convert.model.MapRegionData;

import static com.mapsyncer.mca.convert.model.ConvertConstants.CHUNKS_PER_REGION;
import static com.mapsyncer.mca.convert.model.ConvertConstants.DEFAULT_BIOME;
import static com.mapsyncer.mca.convert.model.ConvertConstants.REGION_SIZE_BLOCKS;

/**
 * 扫描完成后按表面 height 填充 biome。
 *
 * <p>与重构前 scan 阶段一致：biome 在发现表面方块时的 worldY 采样，而非 topBlockY
 * （overlay 上方最高方块，仅用于 Xaero 序列化 bit 24）。</p>
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
                int surfaceY = data.heightMap[rx][rz];

                data.biomeNames[rx][rz] = BiomeQuartResolver.resolve(chunk, lx, surfaceY, lz);
            }
        }
    }
}
