package com.mapsyncer.mca.convert.scan;

/**
 * 列扫描的垂直范围限制（地表模式用于逻辑顶以上区域等场景）。
 */
public record ScanVerticalBounds(int floorY, int ceilingY) {

    public static ScanVerticalBounds unbounded() {
        return new ScanVerticalBounds(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static ScanVerticalBounds fullColumn(int minBuildHeight, int worldTopY) {
        return new ScanVerticalBounds(minBuildHeight, worldTopY - 1);
    }

    /** 仅扫描 {@code floorY}（含）以上到世界顶 */
    public static ScanVerticalBounds aboveY(int floorY, int worldTopY) {
        return new ScanVerticalBounds(floorY, worldTopY - 1);
    }

    public int clampStartY(int startY) {
        return Math.min(startY, ceilingY);
    }

    public int clampBottomY(int minBuildHeight, int scanBottomY) {
        return Math.max(scanBottomY, Math.max(minBuildHeight, floorY));
    }

    /**
     * 地表模式起点：有 {@code floorY} 限制时忽略高度图，从 {@code ceilingY} 向下扫
     * （地狱逻辑顶以上地表；高度图指向下层可玩区，不能用于上层扫描起点）。
     */
    public int resolveSurfaceStartY(int heightmapStartY) {
        if (floorY > Integer.MIN_VALUE) {
            return ceilingY;
        }
        return clampStartY(heightmapStartY);
    }

    public boolean ignoresHeightmap() {
        return floorY > Integer.MIN_VALUE;
    }
}
