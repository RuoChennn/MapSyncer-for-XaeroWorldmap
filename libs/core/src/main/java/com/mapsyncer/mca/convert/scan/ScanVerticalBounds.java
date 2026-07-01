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
}
