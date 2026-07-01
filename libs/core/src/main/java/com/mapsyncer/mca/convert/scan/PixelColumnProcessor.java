package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser;
import com.mapsyncer.mca.ChunkSectionParser.BlockState;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.convert.io.XaeroBlockStateNbtWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.model.OverlayEntry;
import com.mapsyncer.mca.convert.overlay.OverlayAccumulator;

import java.util.ArrayList;
import java.util.List;

import static com.mapsyncer.mca.convert.model.ConvertConstants.REGION_SIZE_BLOCKS;

/**
 * 统一的列扫描逻辑，合并原 single/multi palette 路径。
 */
public final class PixelColumnProcessor {

    private PixelColumnProcessor() {}

    /**
     * @return true 表示该像素已找到表面
     */
    public static boolean processColumn(
            ChunkDataParser.ChunkInfo chunk,
            ChunkSectionParser.SectionData section,
            int sectionBaseY,
            int lx, int lz,
            int relX, int relZ,
            int effectiveStartY, int scanBottomY,
            int chunkBottomY,
            int heightMapValue,
            boolean isCaveMode,
            boolean worldHasSkylight,
            LightMode lightMode,
            boolean singlePalette,
            ChunkSectionParser.BlockState singleState,
            ColumnScanContext ctx,
            MapRegionData data,
            BlockPropertyLookup blockLookup) {

        int pos = ColumnScanContext.pos(lx, lz);
        if (ctx.blockFound[pos]) {
            return false;
        }

        if (singlePalette) {
            if (singleState.isAir()) {
                if (isCaveMode) {
                    ctx.onAir(pos);
                }
                return false;
            }
            if (isCaveMode && ColumnScanContext.hasFluid(singleState, blockLookup)) {
                ctx.onFluid(pos, true);
            }
            if (!ctx.canProcessCaveBlock(pos, isCaveMode)) {
                return false;
            }
        }

        int localStartY = 15;
        if (effectiveStartY >= sectionBaseY && effectiveStartY <= sectionBaseY + 15) {
            localStartY = effectiveStartY - sectionBaseY;
        } else if (singlePalette) {
            localStartY = Math.min(effectiveStartY - sectionBaseY, 15);
            if (localStartY < 0) {
                localStartY = 15;
            }
        }
        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

        for (int ly = localStartY; ly >= localScanBottomY; ly--) {
            int worldY = sectionBaseY + ly;
            if (worldY < scanBottomY) {
                break;
            }
            if (worldY < chunkBottomY) {
                break;
            }

            ChunkSectionParser.BlockState state = singlePalette
                ? singleState
                : ChunkSectionParser.getBlockStateAt(section, lx, ly, lz);

            if (state.isAir()) {
                if (isCaveMode) {
                    ctx.onAir(pos);
                }
                continue;
            }

            if (isCaveMode && ColumnScanContext.hasFluid(state, blockLookup)) {
                ctx.onFluid(pos, true);
            }

            if (!ctx.canProcessCaveBlock(pos, isCaveMode)) {
                continue;
            }

            String blockName = state.name();
            int flags = blockLookup.getFlags(blockName);
            ArrayList<OverlayEntry> overlays = ctx.overlayLists[pos];

            if ((flags & BlockPropertyLookup.FLAG_WATER_INHERITING) != 0) {
                return finishSurface(chunk, section, lx, ly, lz, relX, relZ, worldY,
                    state, heightMapValue, overlays, ctx, data, blockLookup,
                    lightMode, worldHasSkylight, true);
            }

            if (blockLookup.isWaterloggedSurface(blockName, state.properties())
                && (flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) == 0) {
                return finishSurface(chunk, section, lx, ly, lz, relX, relZ, worldY,
                    state, heightMapValue, overlays, ctx, data, blockLookup,
                    lightMode, worldHasSkylight, false);
            }

            if ((flags & BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID) != 0) {
                addFluidOverlay(chunk, section, lx, ly, lz, worldY, state,
                    overlays, ctx, pos, blockLookup);
                continue;
            }

            if (state.isWaterlogged() && (flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) != 0) {
                int aboveWorldY = worldY + 1;
                int waterOpacity = blockLookup.getLightBlock("minecraft:water");
                byte waterLight = SectionLightAccess.getBlockLightCrossSection(
                    chunk, section, lx, ly, lz, aboveWorldY);
                overlays = ensureOverlayList(ctx, pos, overlays);
                OverlayAccumulator.add(overlays, overlays, XaeroBlockStateNbtWriter.WATER, worldY,
                    waterOpacity, waterLight, blockLookup);
                int opacity = blockLookup.getLightBlock(blockName);
                byte light = SectionLightAccess.getBlockLightCrossSection(
                    chunk, section, lx, ly, lz, aboveWorldY);
                OverlayAccumulator.add(overlays, overlays, state, worldY, opacity, light, blockLookup);
                if (ctx.topPixelH[pos] < 0) {
                    ctx.topPixelH[pos] = worldY;
                }
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) != 0) {
                int opacity = blockLookup.getLightBlock(blockName);
                int aboveWorldY = worldY + 1;
                byte light = SectionLightAccess.getBlockLightCrossSection(
                    chunk, section, lx, ly, lz, aboveWorldY);
                overlays = ensureOverlayList(ctx, pos, overlays);
                OverlayAccumulator.add(overlays, overlays, state, worldY, opacity, light, blockLookup);
                if (ctx.topPixelH[pos] < 0) {
                    ctx.topPixelH[pos] = worldY;
                }
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_INVISIBLE) != 0) {
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_TRANSPARENT) != 0) {
                int opacity = blockLookup.getLightBlock(blockName);
                int aboveWorldY = worldY + 1;
                byte light = SectionLightAccess.getBlockLightCrossSection(
                    chunk, section, lx, ly, lz, aboveWorldY);
                overlays = ensureOverlayList(ctx, pos, overlays);
                OverlayAccumulator.add(overlays, overlays, state, worldY, opacity, light, blockLookup);
                if (ctx.topPixelH[pos] < 0) {
                    ctx.topPixelH[pos] = worldY;
                }
                continue;
            }

            int aboveWorldY = worldY + 1;
            byte light = SectionLightAccess.calculateSurfaceLight(chunk, section, lx, ly, lz, aboveWorldY,
                heightMapValue, overlays, lightMode, worldHasSkylight, blockLookup);
            int topBlockY = ctx.topPixelH[pos] < 0 ? worldY : ctx.topPixelH[pos];
            recordPixelScan(data, state, worldY, topBlockY, light, ctx.overlayLists[pos], relX, relZ);
            ctx.blockFound[pos] = true;
            return true;
        }

        return false;
    }

    private static boolean finishSurface(
            ChunkDataParser.ChunkInfo chunk,
            ChunkSectionParser.SectionData section,
            int lx, int ly, int lz,
            int relX, int relZ,
            int worldY,
            ChunkSectionParser.BlockState state,
            int heightMapValue,
            ArrayList<OverlayEntry> overlays,
            ColumnScanContext ctx,
            MapRegionData data,
            BlockPropertyLookup blockLookup,
            LightMode lightMode,
            boolean worldHasSkylight,
            boolean useCalculateLight) {

        int pos = ColumnScanContext.pos(lx, lz);
        int opacity = blockLookup.getLightBlock("minecraft:water");
        int aboveWorldY = worldY + 1;
        byte light = useCalculateLight
            ? SectionLightAccess.calculateSurfaceLight(chunk, section, lx, ly, lz, aboveWorldY,
                heightMapValue, overlays, lightMode, worldHasSkylight, blockLookup)
            : SectionLightAccess.getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
        overlays = ensureOverlayList(ctx, pos, overlays);
        OverlayAccumulator.add(overlays, overlays, XaeroBlockStateNbtWriter.WATER, worldY, opacity, light, blockLookup);
        int topBlockY = ctx.topPixelH[pos] < 0 ? worldY : ctx.topPixelH[pos];
        recordPixelScan(data, state, worldY, topBlockY, light, ctx.overlayLists[pos], relX, relZ);
        ctx.blockFound[pos] = true;
        return true;
    }

    private static void addFluidOverlay(
            ChunkDataParser.ChunkInfo chunk,
            ChunkSectionParser.SectionData section,
            int lx, int ly, int lz,
            int worldY,
            ChunkSectionParser.BlockState state,
            ArrayList<OverlayEntry> overlays,
            ColumnScanContext ctx,
            int pos,
            BlockPropertyLookup blockLookup) {

        int opacity = blockLookup.getLightBlock(state.name());
        int aboveWorldY = worldY + 1;
        byte light = SectionLightAccess.getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
        overlays = ensureOverlayList(ctx, pos, overlays);
        OverlayAccumulator.add(overlays, overlays, state, worldY, opacity, light, blockLookup);
        if (ctx.topPixelH[pos] < 0) {
            ctx.topPixelH[pos] = worldY;
        }
    }

    private static ArrayList<OverlayEntry> ensureOverlayList(
            ColumnScanContext ctx, int pos, ArrayList<OverlayEntry> overlays) {
        if (overlays == null) {
            overlays = new ArrayList<>();
            ctx.overlayLists[pos] = overlays;
        }
        return overlays;
    }

    static void recordPixelScan(MapRegionData data, ChunkSectionParser.BlockState surfaceState,
                                int topY, int highestBlockY, byte surfaceLight,
                                List<OverlayEntry> overlayList, int relX, int relZ) {
        if (relX >= REGION_SIZE_BLOCKS || relZ >= REGION_SIZE_BLOCKS) {
            return;
        }
        data.hasData[relX][relZ] = true;
        BlockState stored = surfaceState != null ? surfaceState : XaeroBlockStateNbtWriter.AIR;
        data.blockStates[relX][relZ] = stored;
        data.topBlockY[relX][relZ] = highestBlockY;
        data.heightMap[relX][relZ] = topY;
        data.lightMap[relX][relZ] = surfaceLight;
        if (overlayList != null && !overlayList.isEmpty()) {
            data.overlays.put(relX * REGION_SIZE_BLOCKS + relZ, overlayList);
        }
    }
}
