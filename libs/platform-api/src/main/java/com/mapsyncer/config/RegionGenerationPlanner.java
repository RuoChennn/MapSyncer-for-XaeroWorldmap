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
 * 根据维度扫描配置与运行时维度类型，生成 region 的多 pass 扫描计划。
 */
public final class RegionGenerationPlanner {

    private static final int CAVE_LAYER_DEPTH = 15;

    private RegionGenerationPlanner() {}

    public static List<RegionScanPass> plan(DimensionScanConfig config, DimensionTypeInfo info) {
        List<RegionScanPass> passes = new ArrayList<>();
        Set<Integer> seenLayers = new LinkedHashSet<>();

        ScanMode mode = config.scanMode();
        CaveSpec spec = config.caveSpec();

        if (mode == ScanMode.SURFACE && !spec.splitByLogical() && spec.explicitStarts().isEmpty()) {
            passes.add(surfacePass(info));
            return List.copyOf(passes);
        }

        if (spec.splitByLogical() && info.hasUpperZone()) {
            int logicalTopY = info.logicalTopY();
            int minLayer = floorDiv(info.minY(), 16);
            int maxLayer = logicalTopY >> 4;
            for (int layer = minLayer; layer <= maxLayer; layer++) {
                addCaveLayerPass(passes, seenLayers, layer, info);
            }
            passes.add(new RegionScanPass(
                Integer.MAX_VALUE,
                LightMode.SURFACE,
                CaveModeParams.NONE,
                ScanVerticalBounds.aboveY(logicalTopY + 1, info.maxY())
            ));
        }

        for (int caveStart : spec.explicitStarts()) {
            addCaveStartPass(passes, seenLayers, caveStart, info);
        }

        if (passes.isEmpty() && mode == ScanMode.CAVE) {
            addCaveStartPass(passes, seenLayers, config.caveStart(), info);
        }

        if (passes.isEmpty()) {
            passes.add(surfacePass(info));
        }

        return List.copyOf(passes);
    }

    public static int countPasses(DimensionScanConfig config, DimensionTypeInfo info) {
        return plan(config, info).size();
    }

    private static RegionScanPass surfacePass(DimensionTypeInfo info) {
        return new RegionScanPass(
            Integer.MAX_VALUE,
            LightMode.SURFACE,
            CaveModeParams.NONE,
            ScanVerticalBounds.fullColumn(info.minY(), info.maxY())
        );
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
