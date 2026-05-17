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

public class PacketHandler {

    public static final ResourceLocation SYNC_REQUEST_ID = ResourceLocation.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_request");
    public static final ResourceLocation SYNC_RESPONSE_ID = ResourceLocation.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_response");
    public static final ResourceLocation SYNC_PROGRESS_ID = ResourceLocation.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_progress");

    public static void init() {
    }

    /**
     * 同步请求：客户端发送各region的元数据（时间戳+哈希）
     */
    public record SyncRequestPayload(Map<String, ClientMeta> clientMeta) implements CustomPacketPayload {
        public static final Type<SyncRequestPayload> TYPE = new Type<>(SYNC_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequestPayload> STREAM_CODEC = StreamCodec.of(
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
            return TYPE;
        }
    }

    public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId) implements CustomPacketPayload {
        public static final Type<SyncResponsePayload> TYPE = new Type<>(SYNC_RESPONSE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncResponsePayload> STREAM_CODEC = StreamCodec.of(
                SyncResponsePayload::encode, SyncResponsePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncResponsePayload payload) {
            buf.writeInt(payload.worldId);
            buf.writeInt(payload.chunks.size());
            for (ChunkMapData data : payload.chunks) {
                ChunkMapData.encode(buf, data);
            }
            buf.writeBoolean(payload.isComplete);
        }

        public static SyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(ChunkMapData.decode(buf));
            }
            return new SyncResponsePayload(chunks, buf.readBoolean(), worldId);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncProgressPayload(int processed, int total, String status) implements CustomPacketPayload {
        public static final Type<SyncProgressPayload> TYPE = new Type<>(SYNC_PROGRESS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncProgressPayload> STREAM_CODEC = StreamCodec.of(
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
            return TYPE;
        }
    }
}