package com.mapsyncer.mca.convert.biome;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser;

import static com.mapsyncer.mca.convert.model.ConvertConstants.DEFAULT_BIOME;

/**
 * 按 quart（4×4×4）精度解析 chunk 内 biome，对齐 Xaero fillBiomes 的采样方式。
 * 不包含邻域 region chunk 加载。
 */
public final class BiomeQuartResolver {

    private BiomeQuartResolver() {}

    public static String resolve(ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz) {
        return resolve(chunk, lx, absoluteY, lz, false);
    }

    /**
     * 仅在指定 Y 及其 section 内解析 biome，不回退到高度图地表 Y。
     * 洞穴层采样时使用，避免 Y=63 的像素被替换成 Y=127 的地表群系。
     */
    public static String resolveAtY(ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz) {
        return resolveAtY(chunk, lx, absoluteY, lz, false);
    }

    public static String resolveAtY(ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz,
                                    boolean smoothBoundary) {
        String biome = resolveBiomeAtAbsoluteY(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * 16;
            if (fallbackLy < 0 || fallbackLy > 15) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            for (int tryLy = 0; tryLy <= 15; tryLy++) {
                String candidate = ChunkSectionParser.getBiomeAt(s, lx, tryLy, lz, smoothBoundary);
                if (isValidBiome(candidate)) {
                    return candidate;
                }
            }
        }

        return DEFAULT_BIOME;
    }

    public static String resolve(ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz,
                                   boolean smoothBoundary) {
        String biome = resolveBiomeAtAbsoluteY(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        int[][] heightmap = chunk.heightmap();
        if (heightmap != null) {
            int surfaceY = heightmap[lx][lz];
            biome = resolveBiomeAtAbsoluteY(chunk, lx, surfaceY, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * 16;
            if (fallbackLy < 0 || fallbackLy > 15) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            for (int tryLy = 0; tryLy <= 15; tryLy++) {
                String candidate = ChunkSectionParser.getBiomeAt(s, lx, tryLy, lz, smoothBoundary);
                if (isValidBiome(candidate)) {
                    return candidate;
                }
            }
        }

        return DEFAULT_BIOME;
    }

    private static String resolveBiomeAtAbsoluteY(ChunkDataParser.ChunkInfo chunk,
                                                   int lx, int absoluteY, int lz,
                                                   boolean smoothBoundary) {
        if (!smoothBoundary && chunk.biomeGrid() != null) {
            String gridBiome = chunk.biomeGrid().lookup(lx, absoluteY, lz);
            if (isValidBiome(gridBiome)) {
                return gridBiome;
            }
        }

        String biome = ChunkDataParser.getBiomeAt(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        int targetSectionY = absoluteY >> 4;
        int localY = absoluteY & 0xF;

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.sectionY() != targetSectionY || s.biomePalette().isEmpty()) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, localY, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * 16;
            if (fallbackLy < 0 || fallbackLy > 15) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        return null;
    }

    static boolean isValidBiome(String biome) {
        return biome != null && !biome.equals(DEFAULT_BIOME);
    }
}
