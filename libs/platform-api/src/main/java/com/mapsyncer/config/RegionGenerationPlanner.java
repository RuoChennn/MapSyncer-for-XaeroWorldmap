package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone.CaveModeParams;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import com.mapsyncer.mca.convert.scan.ScanVerticalBounds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 根据层计划与运行时维度类型，生成 region 的多 pass 扫描计划。
 *
 * <p>{@code SURFACE} 仅生成地表（有顶盖维度为逻辑顶以上，否则为全列地表）。
 * {@code ALL} 生成维度高度范围内的全部洞穴层。显式 Y 坐标与 {@code ALL} 按层号自动去重。</p>
 */
public final class RegionGenerationPlanner {

    private static final int CAVE_LAYER_DEPTH = 15;

    private RegionGenerationPlanner() {}

    public static List<RegionScanPass> plan(DimensionScanConfig config, DimensionTypeInfo info) {
        return plan(config.layerPlan(), info);
    }

    public static List<RegionScanPass> plan(LayerPlan plan, DimensionTypeInfo info) {
        List<RegionScanPass> passes = new ArrayList<>();
        Set<Integer> seenLayers = new LinkedHashSet<>();

        if (plan.includeSurface()) {
            addSurfacePass(passes, seenLayers, info);
        }

        if (plan.includeAllCaves()) {
            addAllCaveLayers(passes, seenLayers, info);
        }

        for (int caveStart : plan.caveStarts()) {
            addCaveStartPass(passes, seenLayers, caveStart, info);
        }

        if (passes.isEmpty()) {
            addSurfacePass(passes, seenLayers, info);
        }

        return List.copyOf(passes);
    }

    public static int countPasses(DimensionScanConfig config, DimensionTypeInfo info) {
        return plan(config, info).size();
    }

    private static void addSurfacePass(List<RegionScanPass> passes, Set<Integer> seenLayers,
                                       DimensionTypeInfo info) {
        if (!seenLayers.add(Integer.MAX_VALUE)) {
            return;
        }
        ScanVerticalBounds bounds = info.hasUpperZone()
            ? ScanVerticalBounds.aboveY(info.logicalTopY(), info.maxY())
            : ScanVerticalBounds.fullColumn(info.minY(), info.maxY());
        passes.add(new RegionScanPass(
            Integer.MAX_VALUE,
            LightMode.SURFACE,
            CaveModeParams.NONE,
            bounds
        ));
    }

    private static void addAllCaveLayers(List<RegionScanPass> passes, Set<Integer> seenLayers,
                                         DimensionTypeInfo info) {
        int minLayer = floorDiv(info.minY(), 16);
        int maxLayer = floorDiv(info.maxY() - 1, 16);
        for (int layer = minLayer; layer <= maxLayer; layer++) {
            addCaveLayerPass(passes, seenLayers, layer, info);
        }
    }

    private static void addCaveLayerPass(List<RegionScanPass> passes, Set<Integer> seenLayers,
                                       int layer, DimensionTypeInfo info) {
        int caveStart = (layer << 4) + 15;
        addCaveStartPass(passes, seenLayers, caveStart, info);
    }

    private static void addCaveStartPass(List<RegionScanPass> passes, Set<Integer> seenLayers,
                                           int caveStart, DimensionTypeInfo info) {
        int layer = caveLayerFromStart(caveStart);
        if (!seenLayers.add(layer)) {
            return;
        }
        int depth = caveStart == Integer.MIN_VALUE
            ? Math.max(30, caveStart - info.minY())
            : CAVE_LAYER_DEPTH;
        passes.add(new RegionScanPass(
            layer,
            LightMode.CAVE,
            new CaveModeParams(caveStart, depth),
            ScanVerticalBounds.unbounded()
        ));
    }

    private static int caveLayerFromStart(int caveStart) {
        if (caveStart == Integer.MAX_VALUE || caveStart == Integer.MIN_VALUE) {
            return caveStart;
        }
        return caveStart >> 4;
    }

    private static int floorDiv(int y, int divisor) {
        int r = y / divisor;
        if ((y ^ divisor) < 0 && r * divisor != y) {
            r--;
        }
        return r;
    }
}
