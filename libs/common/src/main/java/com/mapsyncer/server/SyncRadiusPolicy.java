package com.mapsyncer.server;

/** 服务端按请求时的玩家位置裁剪地图；region 为 512 × 512 方块。 */
public record SyncRadiusPolicy(int radius, int centerX, int centerZ, String dimension) {
    public static SyncRadiusPolicy atPlayer(int radius, int blockX, int blockZ, String dimension) {
        return new SyncRadiusPolicy(Math.max(0, radius), Math.floorDiv(blockX, 512),
                Math.floorDiv(blockZ, 512), dimension);
    }

    public boolean allows(String regionDimension, int regionX, int regionZ) {
        if (radius == 0) return true;
        return dimension.equals(regionDimension)
                && Math.abs((long) regionX - centerX) <= radius
                && Math.abs((long) regionZ - centerZ) <= radius;
    }
}
