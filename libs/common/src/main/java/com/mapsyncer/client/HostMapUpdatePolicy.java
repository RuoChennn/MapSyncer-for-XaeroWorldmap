package com.mapsyncer.client;

/** 使用区块坐标和 Xaero 实际绘制距离；与近处写入范围相交的整个 region 都不重载。 */
public final class HostMapUpdatePolicy {
    private HostMapUpdatePolicy() {}
    public static boolean outsideWritingRange(int regionX, int regionZ, int playerChunkX, int playerChunkZ, int distance) {
        long minX = (long) regionX * 32, minZ = (long) regionZ * 32;
        return minX > (long) playerChunkX + distance || minX + 31 < (long) playerChunkX - distance
                || minZ > (long) playerChunkZ + distance || minZ + 31 < (long) playerChunkZ - distance;
    }
}
