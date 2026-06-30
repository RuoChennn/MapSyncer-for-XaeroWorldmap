package com.mapsyncer.mca.convert.biome;

import com.mapsyncer.mca.ChunkSectionParser;

import java.util.List;

/**
 * 预计算的 chunk 内 quart（4×4×4）biome 体素表，将 fill 阶段查表降为 O(1)。
 */
public final class BiomeQuartGrid {

    private static final int VOXELS_PER_SECTION = 64;

    private final int minSectionY;
    private final String[][] sectionVoxels;

    private BiomeQuartGrid(int minSectionY, String[][] sectionVoxels) {
        this.minSectionY = minSectionY;
        this.sectionVoxels = sectionVoxels;
    }

    public static BiomeQuartGrid build(List<ChunkSectionParser.SectionData> sections,
                                       int minSectionY,
                                       ChunkSectionParser.SectionData[] sectionLookup) {
        if (sectionLookup == null || sectionLookup.length == 0) {
            return new BiomeQuartGrid(minSectionY, new String[0][]);
        }

        String[][] grids = new String[sectionLookup.length][];
        for (ChunkSectionParser.SectionData section : sections) {
            if (section == null || section.biomePalette().isEmpty()) {
                continue;
            }
            int idx = section.sectionY() - minSectionY;
            if (idx < 0 || idx >= grids.length) {
                continue;
            }
            String[] voxels = new String[VOXELS_PER_SECTION];
            if (section.biomePalette().size() == 1) {
                String only = section.biomePalette().get(0);
                java.util.Arrays.fill(voxels, only);
            } else {
                for (int voxelY = 0; voxelY < 4; voxelY++) {
                    for (int voxelZ = 0; voxelZ < 4; voxelZ++) {
                        for (int voxelX = 0; voxelX < 4; voxelX++) {
                            int blockX = voxelX << 2;
                            int blockY = voxelY << 2;
                            int blockZ = voxelZ << 2;
                            int voxelIndex = (voxelY << 4) | (voxelZ << 2) | voxelX;
                            voxels[voxelIndex] = ChunkSectionParser.getBiomeAt(
                                    section, blockX, blockY, blockZ, false);
                        }
                    }
                }
            }
            grids[idx] = voxels;
        }
        return new BiomeQuartGrid(minSectionY, grids);
    }

    /**
     * O(1) quart 查表；无数据时返回 null（由 {@link BiomeQuartResolver} 走原有回退链）。
     */
    public String lookup(int lx, int absoluteY, int lz) {
        int sectionIdx = (absoluteY >> 4) - minSectionY;
        if (sectionIdx < 0 || sectionIdx >= sectionVoxels.length) {
            return null;
        }
        String[] voxels = sectionVoxels[sectionIdx];
        if (voxels == null) {
            return null;
        }
        int localY = absoluteY & 0xF;
        int voxelIndex = ((localY >> 2) << 4) | ((lz >> 2) << 2) | (lx >> 2);
        return voxels[voxelIndex];
    }
}
