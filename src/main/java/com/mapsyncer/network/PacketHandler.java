package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.client.ClientHashManager.ClientMeta;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络包处理器 - Fabric 版本
 *
 * 定义MapSyncer模组的所有网络通信包。
 */
public class PacketHandler {

    public static final Identifier SYNC_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_request");
    public static final Identifier SYNC_RESPONSE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_response");
    public static final Identifier SYNC_PROGRESS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_progress");
    public static final Identifier SERVER_INSTALLED_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "server_installed");

    /**
     * 同步请求包 - 客户端发送各region的元数据（时间戳+哈希）
     */
    public record SyncRequestPayload(Map<String, ClientMeta> clientMeta) implements CustomPacketPayload {
        public static final Type<SyncRequestPayload> ID = new Type<>(SYNC_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequestPayload> CODEC = StreamCodec.of(
                SyncRequestPayload::encode, SyncRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncRequestPayload payload) {
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
        }

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

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /**
     * 同步响应包 - 服务端发送需要更新的地图数据
     */
    public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId, String status) implements CustomPacketPayload {
        public static final Type<SyncResponsePayload> ID = new Type<>(SYNC_RESPONSE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncResponsePayload> CODEC = StreamCodec.of(
                SyncResponsePayload::encode, SyncResponsePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncResponsePayload payload) {
            buf.writeInt(payload.worldId);
            buf.writeInt(payload.chunks.size());
            for (ChunkMapData data : payload.chunks) {
                ChunkMapData.encode(buf, data);
            }
            buf.writeBoolean(payload.isComplete);
            buf.writeUtf(payload.status);
        }

        public static SyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(ChunkMapData.decode(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new SyncResponsePayload(chunks, isComplete, worldId, status);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /**
     * 同步进度包 - 服务端发送同步进度通知
     */
    public record SyncProgressPayload(int processed, int total, String status) implements CustomPacketPayload {
        public static final Type<SyncProgressPayload> ID = new Type<>(SYNC_PROGRESS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncProgressPayload> CODEC = StreamCodec.of(
                SyncProgressPayload::encode, SyncProgressPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncProgressPayload payload) {
            buf.writeInt(payload.processed);
            buf.writeInt(payload.total);
            buf.writeUtf(payload.status);
        }

        public static SyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }

    /**
     * 服务端已安装通知包
     */
    public record ServerInstalledPayload(String version) implements CustomPacketPayload {
        public static final Type<ServerInstalledPayload> ID = new Type<>(SERVER_INSTALLED_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerInstalledPayload> CODEC = StreamCodec.of(
                ServerInstalledPayload::encode, ServerInstalledPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, ServerInstalledPayload payload) {
            buf.writeUtf(payload.version());
        }

        public static ServerInstalledPayload decode(RegistryFriendlyByteBuf buf) {
            return new ServerInstalledPayload(buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return ID;
        }
    }
}
