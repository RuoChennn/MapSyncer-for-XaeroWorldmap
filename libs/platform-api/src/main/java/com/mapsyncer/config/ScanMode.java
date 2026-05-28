package com.mapsyncer.config;

/**
 * 扫描模式枚举
 *
 * <p>定义维度地图的扫描方式</p>
 */
public enum ScanMode {
    /**
     * 地表模式：从高度图向下扫描
     *
     * <p>适用于普通地表地图，使用高度图确定扫描起始位置</p>
     */
    SURFACE,

    /**
     * 洞穴模式：从固定高度向下扫描
     *
     * <p>适用于洞穴地图（如地狱），使用固定的起始高度向下扫描</p>
     */
    CAVE
}
