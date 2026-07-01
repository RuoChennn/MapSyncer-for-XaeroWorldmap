package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.convert.model.MapRegionData;

import static com.mapsyncer.mca.convert.model.ConvertConstants.REGION_SIZE_BLOCKS;

public final class ChunkColumnScanner {

    private ChunkColumnScanner() {}

    public static void scan(MapRegionData data,
                            ChunkDataParser.ChunkInfo chunk,
                            int minBuildHeight,
                            int worldTopY,
                            LightMode lightMode,
                            RegionConverterStandalone.CaveModeParams caveParams,
                            boolean worldHasSkylight,
                            BlockPropertyLookup blockLookup) {
        scan(data, chunk, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight,
            blockLookup, ScanVerticalBounds.unbounded());
    }

    public static void scan(MapRegionData data,
                            ChunkDataParser.ChunkInfo chunk,
                            int minBuildHeight,
                            int worldTopY,
                            LightMode lightMode,
                            RegionConverterStandalone.CaveModeParams caveParams,
                            boolean worldHasSkylight,
                            BlockPropertyLookup blockLookup,
                            ScanVerticalBounds bounds) {
        int chunkX = chunk.chunkX();
        int chunkZ = chunk.chunkZ();

        data.chunkExists[chunkX][chunkZ] = true;
        data.chunkGrid[chunkX][chunkZ] = chunk;

        int caveStart = caveParams.caveStart();
        int caveDepth = caveParams.caveDepth();
        boolean isCaveMode = caveStart != Integer.MAX_VALUE;
        boolean fullCave = caveStart == Integer.MIN_VALUE;
        int[][] heightMap = chunk.heightmap();
        int chunkBottomY = chunk.chunkBottomY();

        ColumnScanContext ctx = new ColumnScanContext(fullCave);

        int sectionIndex = 0;
        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.blockPalette().isEmpty()) {
                continue;
            }

            int sectionY = section.sectionY();
            int sectionBaseY = sectionY * 16;
            int sectionTopY = sectionBaseY + 15;
            int sectionBottomY = sectionBaseY;

            if (sectionTopY < chunkBottomY) {
                continue;
            }

            boolean singlePalette = section.blockPalette().size() == 1 && section.blockData() == null;
            ChunkSectionParser.BlockState singleState = singlePalette
                ? section.blockPalette().get(0) : null;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int relX = chunkX * 16 + lx;
                    int relZ = chunkZ * 16 + lz;
                    if (relX >= REGION_SIZE_BLOCKS || relZ >= REGION_SIZE_BLOCKS) {
                        continue;
                    }

                    int pos = ColumnScanContext.pos(lx, lz);
                    if (ctx.blockFound[pos]) {
                        continue;
                    }

                    int heightMapValue = heightMap[lx][lz];

                    int scanBottomY;
                    int startY;
                    if (isCaveMode) {
                        startY = bounds.clampStartY(caveStart);
                        scanBottomY = bounds.clampBottomY(minBuildHeight,
                            Math.max(caveStart - caveDepth, minBuildHeight));
                    } else {
                        startY = bounds.resolveSurfaceStartY(
                            ChunkDataParser.getHeightmapStartY(chunk, lx, lz, worldTopY));
                        scanBottomY = bounds.clampBottomY(minBuildHeight, minBuildHeight);
                    }

                    if (startY < scanBottomY) {
                        continue;
                    }

                    if (isCaveMode && sectionTopY > startY) {
                        continue;
                    }
                    // 整段在扫描底以下才跳过（用 sectionTopY，不能用 sectionBottomY）
                    if (sectionTopY < scanBottomY) {
                        continue;
                    }

                    int effectiveStartY = computeEffectiveStartY(sectionIndex, startY, worldTopY,
                        isCaveMode, heightMapValue, chunkBottomY, sectionTopY, bounds);

                    if (!isCaveMode && effectiveStartY < sectionBottomY) {
                        continue;
                    }

                    PixelColumnProcessor.processColumn(chunk, section, sectionBaseY,
                        lx, lz, relX, relZ, effectiveStartY, scanBottomY, chunkBottomY,
                        heightMapValue, isCaveMode, worldHasSkylight, lightMode,
                        singlePalette, singleState, ctx, data, blockLookup);
                }
            }

            sectionIndex++;
        }
    }

    private static int computeEffectiveStartY(int sectionIndex, int startY, int worldTopY,
                                               boolean isCaveMode, int heightMapValue, int chunkBottomY,
                                               int sectionTopY, ScanVerticalBounds bounds) {
        int effectiveStartY = startY;
        if (sectionIndex > 0) {
            effectiveStartY = Math.min(startY + 1, worldTopY - 1);
        }
        if (!isCaveMode && !bounds.ignoresHeightmap() && heightMapValue < chunkBottomY) {
            effectiveStartY = sectionTopY;
        }
        if (isCaveMode) {
            effectiveStartY = Math.min(effectiveStartY, sectionTopY);
        }
        return effectiveStartY;
    }
}
