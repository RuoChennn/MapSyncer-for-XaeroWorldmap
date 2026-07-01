package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

/**
 * 维度扫描配置记录
 *
 * <p>存储单个维度的扫描参数</p>
 *
 * @param dimension 维度 ID（如 "minecraft:the_nether"）
 * @param scanMode 扫描模式（SURFACE 或 CAVE）
 * @param caveStart 洞穴起始高度（SURFACE 模式忽略此参数）
 * @param dimTypeInfo 维度类型信息（可选，用于离线解析时确定高度范围和光照属性）
 */
public record DimensionScanConfig(
    String dimension,
    ScanMode scanMode,
    int caveStart,
    DimensionTypeInfo dimTypeInfo
) {
    /**
     * 简化构造函数（不包含维度类型信息）
     *
     * <p>维度类型信息将根据维度 ID 自动推断</p>
     *
     * @param dimension 维度 ID
     * @param scanMode 扫描模式
     * @param caveStart 洞穴起始高度
     */
    public DimensionScanConfig(String dimension, ScanMode scanMode, int caveStart) {
        this(dimension, scanMode, caveStart, null);
    }

    /**
     * 计算洞穴层号
     *
     * <p>参考 Xaero MapProcessor.getCaveLayer():</p>
     * <ul>
     *   <li>SURFACE 模式返回 Integer.MAX_VALUE（地表）</li>
     *   <li>CAVE 模式返回 caveStart >> 4（除以16）</li>
     *   <li>支持负高度：-64 → layer -4</li>
     * </ul>
     *
     * @return 洞穴层号
     */
    public int getCaveLayer() {
        if (scanMode == ScanMode.SURFACE) {
            return Integer.MAX_VALUE;
        }
        if (caveStart == Integer.MAX_VALUE || caveStart == Integer.MIN_VALUE) {
            return caveStart;
        }
        return caveStart >> 4;
    }

    /** Xaero 分层洞穴单层扫描深度（tile footer 中的 caveDepth，对应 16 格厚 section） */
    private static final int XAERO_CAVE_LAYER_DEPTH = 15;

    /**
     * 获取洞穴扫描深度
     *
     * <p>分层洞穴与 Xaero 一致：从 {@code caveStart} 向下扫描 {@link #XAERO_CAVE_LAYER_DEPTH} 格。
     * 全洞穴模式（{@code caveStart == Integer.MIN_VALUE}）仍扫至世界底部。</p>
     *
     * @param minBuildHeight 世界最低建筑高度（仅全洞穴模式使用）
     * @return 洞穴深度值
     */
    public int getCaveDepth(int minBuildHeight) {
        if (scanMode == ScanMode.SURFACE) {
            return 0;
        }
        if (caveStart == Integer.MIN_VALUE) {
            return Math.max(30, caveStart - minBuildHeight);
        }
        return XAERO_CAVE_LAYER_DEPTH;
    }

    /**
     * 获取维度类型信息
     *
     * <p>如果有配置则返回配置值，否则根据维度 ID 推断</p>
     *
     * @return DimensionTypeInfo 对象
     */
    public DimensionTypeInfo getDimensionTypeInfo() {
        if (dimTypeInfo != null) {
            return dimTypeInfo;
        }
        return DimensionTypeInfo.fromDimensionId(dimension);
    }
}
