package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

/**
 * 维度扫描配置记录
 *
 * <p>存储单个维度的扫描参数。cave 字段通过 {@link CaveSpec} 支持多层与逻辑顶拆分。</p>
 */
public record DimensionScanConfig(
    String dimension,
    ScanMode scanMode,
    CaveSpec caveSpec,
    DimensionTypeInfo dimTypeInfo
) {
    public DimensionScanConfig(String dimension, ScanMode scanMode, int caveStart) {
        this(dimension, scanMode, CaveSpec.single(caveStart), null);
    }

    public DimensionScanConfig(String dimension, ScanMode scanMode, int caveStart, DimensionTypeInfo dimTypeInfo) {
        this(dimension, scanMode, CaveSpec.single(caveStart), dimTypeInfo);
    }

    /** 兼容旧 API：首个显式 caveStart 或默认值 */
    public int caveStart() {
        return caveSpec.primaryStart();
    }

    /**
     * 单 pass 场景下的洞穴层号；多 pass 时请使用 {@link RegionGenerationPlanner}。
     */
    public int getCaveLayer() {
        if (scanMode == ScanMode.SURFACE && !caveSpec.splitByLogical()) {
            return Integer.MAX_VALUE;
        }
        int start = caveStart();
        if (start == Integer.MAX_VALUE || start == Integer.MIN_VALUE) {
            return start;
        }
        return start >> 4;
    }

    public int getCaveDepth(int minBuildHeight) {
        if (scanMode == ScanMode.SURFACE) {
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
