package com.mapsyncer.network.payload;

/**
 * 地图区域数据传输类 - 平台无关版本
 *
 * 包含单个region的地图数据和元信息，用于服务端到客户端同步。
 * 支持地表层和洞穴层的地图数据传输，支持大数据分片传输。
 *
 * caveLayer字段说明：
 * - Integer.MAX_VALUE：地表层（默认值）
 * - 其他值：洞穴层号，对应文件夹 caves/<caveLayer>/...
 *
 * 分片字段 (partIndex, totalParts)：
 * - totalParts <= 1：未分片（默认值，向后兼容）
 * - totalParts >= 2：分片传输，接收端按 partIndex 组装
 */
public class ChunkMapData {

    /** 每个分包的最大字节数（MC协议硬上限 ~32767，留余量） */
    public static final int MAX_PAYLOAD_BYTES = 28_000;

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
    /** 分片序号（0-based），totalParts<=1时为0 */
    public final int partIndex;
    /** 总分片数，<=1表示未分片 */
    public final int totalParts;

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data) {
        this(regionX, regionZ, dimension, data, 0, Integer.MAX_VALUE);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data, long timestampSeconds) {
        this(regionX, regionZ, dimension, data, timestampSeconds, Integer.MAX_VALUE);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data,
                         long timestampSeconds, int caveLayer) {
        this(regionX, regionZ, dimension, data, timestampSeconds, caveLayer, 0, 0);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data,
                         long timestampSeconds, int caveLayer, int partIndex, int totalParts) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimension = dimension;
        this.data = data;
        this.timestampSeconds = timestampSeconds;
        this.caveLayer = caveLayer;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
    }

    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }

    /**
     * 将超大的 ChunkMapData 拆分为多个小包。
     * 如果数据未超过 MAX_PAYLOAD_BYTES，返回只包含自身的数组。
     */
    public static ChunkMapData[] split(ChunkMapData original) {
        int totalParts = (original.data.length + MAX_PAYLOAD_BYTES - 1) / MAX_PAYLOAD_BYTES;
        if (totalParts <= 1) {
            return new ChunkMapData[] { original };
        }
        ChunkMapData[] parts = new ChunkMapData[totalParts];
        for (int i = 0; i < totalParts; i++) {
            int offset = i * MAX_PAYLOAD_BYTES;
            int len = Math.min(MAX_PAYLOAD_BYTES, original.data.length - offset);
            byte[] partData = new byte[len];
            System.arraycopy(original.data, offset, partData, 0, len);
            parts[i] = new ChunkMapData(original.regionX, original.regionZ, original.dimension,
                    partData, original.timestampSeconds, original.caveLayer, i, totalParts);
        }
        return parts;
    }
}
