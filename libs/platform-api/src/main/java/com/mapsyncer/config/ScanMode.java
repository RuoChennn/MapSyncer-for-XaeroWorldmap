package com.mapsyncer.config;

/**
 * 未在 {@code dimension_configs} 中列出的维度之默认层计划回退值。
 *
 * <p>Per-dimension 配置请使用 {@link LayerPlan}（{@code dimension|layerPlan|dim_type_info}）。</p>
 */
public enum ScanMode {
    /** 回退为 {@link LayerPlan#surfaceOnly()} */
    SURFACE,

    /** 回退为 {@link LayerPlan#caves(int)}，Y 由 {@code default_cave_start} 指定 */
    CAVE
}
