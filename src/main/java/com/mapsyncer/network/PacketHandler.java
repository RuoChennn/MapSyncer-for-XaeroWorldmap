package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.client.ClientHashManager.ClientMeta;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络包处理器
 *
 * <p>负责定义和处理MapSyncer模组的所有网络通信包。
 * 包含同步请求、同步响应和同步进度三种类型的网络包。</p>
 *
 * <p>网络包类型：</p>
 * <ul>
 *   <li>{@link SyncRequestPayload} - 客户端发送的同步请求，包含本地region元数据</li>
 *   <li>{@link SyncResponsePayload} - 服务端发送的同步响应，包含需要更新的地图数据</li>
 *   <li>{@link SyncProgressPayload} - 服务端发送的同步进度通知</li>
 * </ul>
 */
public class PacketHandler {

    /** 同步请求包的资源定位符 */
    public static final ResourceLocation SYNC_REQUEST_ID = ResourceLocation.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_request");
    /** 同步响应包的资源定位符 */
    public static final ResourceLocation SYNC_RESPONSE_ID = ResourceLocation.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_response");
    /** 同步进度包的资源定位符 */
    public static final ResourceLocation SYNC_PROGRESS_ID = ResourceLocation.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_progress");

    /**
     * 初始化网络包处理器
     *
     * <p>预留的初始化方法，用于注册网络包处理器。</p>
     */
    public static void init() {
    }

    /**
     * 同步请求包 - 客户端发送各region的元数据（时间戳+哈希）
     *
     * <p>客户端通过此包向服务端报告本地已有的地图数据状态，
     * 服务端据此判断哪些数据需要同步。</p>
     *
     * @param clientMeta 客户端元数据映射，键为region路径，值为时间戳和哈希值
     */
    public record SyncRequestPayload(Map<String, ClientMeta> clientMeta) implements CustomPacketPayload {
        /** 包类型标识 */
        public static final Type<SyncRequestPayload> TYPE = new Type<>(SYNC_REQUEST_ID);
        /** 流编解码器，用于网络传输的序列化和反序列化 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequestPayload> STREAM_CODEC = StreamCodec.of(
                SyncRequestPayload::encode, SyncRequestPayload::decode
        );

        /**
         * 将同步请求包编码到网络缓冲区
         *
         * @param buf     网络缓冲区
         * @param payload 要编码的请求包
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncRequestPayload payload) {
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
        }

        /**
         * 从网络缓冲区解码同步请求包
         *
         * @param buf 网络缓冲区
         * @return 解码后的同步请求包
         */
        public static SyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readInt();
            Map<String, ClientMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String path = buf.readUtf();
                long timestampSeconds = buf.readLong();
                String hash = buf.readUtf();
                metaMap.put(path, new ClientMeta(timestampSeconds, hash));
            }
            return new SyncRequestPayload(metaMap);
        }

        /**
         * 获取此负载的包类型
         *
         * @return 包类型标识
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 同步响应包 - 服务端发送需要更新的地图数据
     *
     * <p>服务端通过此包将需要同步的地图数据发送给客户端。
     * 可能包含多个region的数据，并标识是否为最后一包。</p>
     *
     * @param chunks     地图数据列表，包含需要更新的region数据
     * @param isComplete 是否为最后一包（true表示同步完成）
     * @param worldId    世界ID，用于客户端识别当前同步的世界
     */
    public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId) implements CustomPacketPayload {
        /** 包类型标识 */
        public static final Type<SyncResponsePayload> TYPE = new Type<>(SYNC_RESPONSE_ID);
        /** 流编解码器，用于网络传输的序列化和反序列化 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncResponsePayload> STREAM_CODEC = StreamCodec.of(
                SyncResponsePayload::encode, SyncResponsePayload::decode
        );

        /**
         * 将同步响应包编码到网络缓冲区
         *
         * @param buf     网络缓冲区
         * @param payload 要编码的响应包
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncResponsePayload payload) {
            buf.writeInt(payload.worldId);
            buf.writeInt(payload.chunks.size());
            for (ChunkMapData data : payload.chunks) {
                ChunkMapData.encode(buf, data);
            }
            buf.writeBoolean(payload.isComplete);
        }

        /**
         * 从网络缓冲区解码同步响应包
         *
         * @param buf 网络缓冲区
         * @return 解码后的同步响应包
         */
        public static SyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(ChunkMapData.decode(buf));
            }
            return new SyncResponsePayload(chunks, buf.readBoolean(), worldId);
        }

        /**
         * 获取此负载的包类型
         *
         * @return 包类型标识
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 同步进度包 - 服务端发送同步进度通知
     *
     * <p>用于向客户端报告当前同步进度，让客户端能够显示进度条或状态信息。</p>
     *
     * @param processed 已处理的region数量
     * @param total     总region数量
     * @param status    当前状态描述文本
     */
    public record SyncProgressPayload(int processed, int total, String status) implements CustomPacketPayload {
        /** 包类型标识 */
        public static final Type<SyncProgressPayload> TYPE = new Type<>(SYNC_PROGRESS_ID);
        /** 流编解码器，用于网络传输的序列化和反序列化 */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncProgressPayload> STREAM_CODEC = StreamCodec.of(
                SyncProgressPayload::encode, SyncProgressPayload::decode
        );

        /**
         * 将同步进度包编码到网络缓冲区
         *
         * @param buf     网络缓冲区
         * @param payload 要编码的进度包
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncProgressPayload payload) {
            buf.writeInt(payload.processed);
            buf.writeInt(payload.total);
            buf.writeUtf(payload.status);
        }

        /**
         * 从网络缓冲区解码同步进度包
         *
         * @param buf 网络缓冲区
         * @return 解码后的同步进度包
         */
        public static SyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
        }

        /**
         * 获取此负载的包类型
         *
         * @return 包类型标识
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}