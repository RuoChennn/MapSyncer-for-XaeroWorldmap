package com.mapsyncer.network;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * 地图区域数据传输类
 *
 * <p>包含单个region的地图数据和元信息，用于服务端到客户端同步。
 * 支持地表层和洞穴层的地图数据传输。</p>
 *
 * <p>caveLayer字段说明：</p>
 * <ul>
 *   <li>{@code Integer.MAX_VALUE}：地表层（默认值）</li>
 *   <li>其他值：洞穴层号，对应文件夹 caves/<caveLayer>/...</li>
 *   <li>层号计算：caveLayer = caveStart >> 4（除以16）</li>
 * </ul>
 *
 * @see PacketHandler.SyncResponsePayload
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
    /** 服务端生成时间戳（秒级），用于客户端判断数据是否过期 */
    public final long timestampSeconds;
    /** 洞洞层号，Integer.MAX_VALUE表示地表层 */
    public final int caveLayer;

    /**
     * 兼容旧代码的构造器（默认地表层，时间戳为0）
     *
     * @param regionX   Region的X坐标
     * @param regionZ   Region的Z坐标
     * @param dimension 维度标识符
     * @param data      地图数据字节数组
     */
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data) {
        this(regionX, regionZ, dimension, data, 0, Integer.MAX_VALUE);
    }

    /**
     * 兼容旧代码的构造器（默认地表层）
     *
     * @param regionX           Region的X坐标
     * @param regionZ           Region的Z坐标
     * @param dimension         维度标识符
     * @param data              地图数据字节数组
     * @param timestampSeconds  服务端生成时间戳（秒）
     */
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data, long timestampSeconds) {
        this(regionX, regionZ, dimension, data, timestampSeconds, Integer.MAX_VALUE);
    }

    /**
     * 完整构造器
     *
     * @param regionX           Region的X坐标
     * @param regionZ           Region的Z坐标
     * @param dimension         维度标识符
     * @param data              地图数据字节数组
     * @param timestampSeconds  服务端生成时间戳（秒）
     * @param caveLayer         洞洞层号，Integer.MAX_VALUE表示地表层
     */
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
     *
     * @return 如果caveLayer为Integer.MAX_VALUE则返回true，否则返回false
     */
    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }

    /**
     * 序列化到网络缓冲区
     *
     * <p>使用标记位实现向后兼容：</p>
     * <ul>
     *   <li>先写入基本字段（regionX, regionZ, dimension, data, timestampSeconds）</li>
     *   <li>写入标记位表示是否有caveLayer</li>
     *   <li>只有非地表层时才写入caveLayer值</li>
     * </ul>
     *
     * @param buf  网络缓冲区
     * @param data 要序列化的地图数据
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
     * <p>向后兼容处理：</p>
     * <ul>
     *   <li>读取标记位判断是否有caveLayer</li>
     *   <li>如果没有标记位或标记为false，使用Integer.MAX_VALUE（地表）</li>
     * </ul>
     *
     * @param buf 网络缓冲区
     * @return 反序列化后的地图数据对象
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