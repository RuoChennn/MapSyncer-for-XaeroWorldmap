package com.mapsyncer.network.payload;

/**
 * 地图区域数据传输类 - 平台无关版本
 *
 * 包含单个region的地图数据和元信息，用于服务端到客户端同步。
 * 支持地表层和洞穴层的地图数据传输。
 *
 * caveLayer字段说明：
 * - Integer.MAX_VALUE：地表层（默认值）
 * - 其他值：洞穴层号，对应文件夹 caves/<caveLayer>/...
 */
public class ChunkMapData {

    /** Region的X坐标（单位：region） */
    public final int regionX;
    /** Region的Z坐标（单位：region） */
    public final int regionZ;
    /** 维度标识符，如 "minecraft:overworld" */
    public final String dimension;
    /** 地图数据字节数组（压缩后的region文件内容） */
    public final byte[] data;
    /** 服务端生成时间戳（秒级） */
    public final long timestampSeconds;
    /** 洞穴层号，Integer.MAX_VALUE表示地表层 */
    public final int caveLayer;

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data) {
        this(regionX, regionZ, dimension, data, 0, Integer.MAX_VALUE);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data, long timestampSeconds) {
        this(regionX, regionZ, dimension, data, timestampSeconds, Integer.MAX_VALUE);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data,
                         long timestampSeconds, int caveLayer) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimension = dimension;
        this.data = data;
        this.timestampSeconds = timestampSeconds;
        this.caveLayer = caveLayer;
    }

    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }
}