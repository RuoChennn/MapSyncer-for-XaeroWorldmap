package com.mapsyncer.network;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * 地图区域数据传输类
 *
 * 包含单个 region 的地图数据和元信息，用于服务端到客户端同步。
 *
 * caveLayer 字段说明：
 * - Integer.MAX_VALUE：地表层（默认）
 * - 其他值：洞穴层号，对应文件夹 caves/<caveLayer>/...
 * - 层号计算：caveLayer = caveStart >> 4（除以16）
 */
public class ChunkMapData {
    public final int regionX;
    public final int regionZ;
    public final String dimension;
    public final byte[] data;
    public final long timestampSeconds;  // Server generation timestamp
    public final int caveLayer;           // Cave layer number (Integer.MAX_VALUE = surface)

    // 兼容旧代码的构造器（默认地表层）
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data) {
        this(regionX, regionZ, dimension, data, 0, Integer.MAX_VALUE);
    }

    // 兼容旧代码的构造器（默认地表层）
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data, long timestampSeconds) {
        this(regionX, regionZ, dimension, data, timestampSeconds, Integer.MAX_VALUE);
    }

    // 完整构造器
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data,
                         long timestampSeconds, int caveLayer) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimension = dimension;
        this.data = data;
        this.timestampSeconds = timestampSeconds;
        this.caveLayer = caveLayer;
    }

    /**
     * 判断是否为地表层
     */
    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }

    /**
     * 序列化到网络缓冲区
     *
     * 使用标记位实现向后兼容：
     * - 先写入基本字段（regionX, regionZ, dimension, data, timestampSeconds）
     * - 写入标记位表示是否有 caveLayer
     * - 只有非地表层时才写入 caveLayer 值
     */
    public static void encode(RegistryFriendlyByteBuf buf, ChunkMapData data) {
        buf.writeInt(data.regionX);
        buf.writeInt(data.regionZ);
        buf.writeUtf(data.dimension);
        buf.writeByteArray(data.data);
        buf.writeLong(data.timestampSeconds);

        // 使用标记位实现向后兼容
        boolean hasCaveLayer = data.caveLayer != Integer.MAX_VALUE;
        buf.writeBoolean(hasCaveLayer);
        if (hasCaveLayer) {
            buf.writeInt(data.caveLayer);
        }
    }

    /**
     * 从网络缓冲区反序列化
     *
     * 向后兼容处理：
     * - 读取标记位判断是否有 caveLayer
     * - 如果没有标记位或标记为 false，使用 Integer.MAX_VALUE（地表）
     */
    public static ChunkMapData decode(RegistryFriendlyByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readUtf();
        byte[] data = buf.readByteArray();
        long timestampSeconds = buf.readLong();

        // 尝试读取 caveLayer（向后兼容）
        int caveLayer = Integer.MAX_VALUE;
        if (buf.isReadable()) {
            boolean hasCaveLayer = buf.readBoolean();
            if (hasCaveLayer) {
                caveLayer = buf.readInt();
            }
        }

        return new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds, caveLayer);
    }
}