package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

/**
 * 维度扫描配置记录。
 *
 * <p>层生成由 {@link LayerPlan} 与运行时 {@link DimensionTypeInfo} 共同决定，
 * 不再区分独立的 SURFACE/CAVE 扫描模式字段。</p>
 */
public record DimensionScanConfig(
    String dimension,
    LayerPlan layerPlan,
    DimensionTypeInfo dimTypeInfo
) {
    public DimensionScanConfig(String dimension, LayerPlan layerPlan) {
        this(dimension, layerPlan, null);
    }

    /** 保留供日志等兼容；新配置请使用 {@link #layerPlan()} */
    @Deprecated
    public ScanMode scanMode() {
        if (layerPlan.includeSurface() && !layerPlan.includeAllCaves() && layerPlan.caveStarts().isEmpty()) {
            return ScanMode.SURFACE;
        }
        if (!layerPlan.includeSurface() && (layerPlan.includeAllCaves() || !layerPlan.caveStarts().isEmpty())) {
            return ScanMode.CAVE;
        }
        return layerPlan.includeSurface() ? ScanMode.SURFACE : ScanMode.CAVE;
    }

    public int caveStart() {
        return layerPlan.primaryCaveStart();
    }

    /**
     * 单 pass 场景下的洞穴层号；多 pass 时请使用 {@link RegionGenerationPlanner}。
     */
    public int getCaveLayer() {
        if (layerPlan.includeSurface() && !layerPlan.includeAllCaves() && layerPlan.caveStarts().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int start = caveStart();
        if (start == Integer.MAX_VALUE || start == Integer.MIN_VALUE) {
            return start;
        }
        return start >> 4;
    }

    public int getCaveDepth(int minBuildHeight) {
        if (layerPlan.includeSurface() && !layerPlan.includeAllCaves() && layerPlan.caveStarts().isEmpty()) {
            return 0;
        }
        int start = caveStart();
        if (start == Integer.MIN_VALUE) {
            return Math.max(30, start - minBuildHeight);
        }
        return 15;
    }

    public DimensionTypeInfo getDimensionTypeInfo() {
        if (dimTypeInfo != null) {
            return dimTypeInfo;
        }
        return DimensionTypeInfo.fromDimensionId(dimension);
    }
}
