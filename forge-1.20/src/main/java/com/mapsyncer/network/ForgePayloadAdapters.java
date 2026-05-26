package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge Payload 适配器
 *
 * 将平台无关的 Payload DTO 适配到 Forge 的 CustomPacketPayload 接口。
 * 与 NeoForge 版本几乎相同，唯一的差异是包名前缀（net.minecraftforge vs net.neoforged）。
 */
public class ForgePayloadAdapters {

    // ===== 同步请求适配器 =====

    public record ForgeSyncRequestPayload(SyncRequestPayload data) implements CustomPacketPayload {
        public static final Type<ForgeSyncRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_REQUEST_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, ForgeSyncRequestPayload> STREAM_CODEC =
            StreamCodec.of(ForgeSyncRequestPayload::encode, ForgeSyncRequestPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, ForgeSyncRequestPayload payload) {
            buf.writeInt(payload.data.clientMeta().size());
            for (var entry : payload.data.clientMeta().entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
        }

        public static ForgeSyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readInt();
            Map<String, ClientMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String path = buf.readUtf();
                long timestampSeconds = buf.readLong();
                String hash = buf.readUtf();
                metaMap.put(path, new ClientMeta(timestampSeconds, hash));
            }
            return new ForgeSyncRequestPayload(new SyncRequestPayload(metaMap));
        }
    }

    // ===== 同步响应适配器 =====

    public record ForgeSyncResponsePayload(SyncResponsePayload data) implements CustomPacketPayload {
        public static final Type<ForgeSyncResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_RESPONSE_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, ForgeSyncResponsePayload> STREAM_CODEC =
            StreamCodec.of(ForgeSyncResponsePayload::encode, ForgeSyncResponsePayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, ForgeSyncResponsePayload payload) {
            buf.writeInt(payload.data.worldId());
            buf.writeInt(payload.data.chunks().size());
            for (ChunkMapData chunk : payload.data.chunks()) {
                encodeChunkMapData(buf, chunk);
            }
            buf.writeBoolean(payload.data.isComplete());
            buf.writeUtf(payload.data.status());
        }

        public static ForgeSyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(decodeChunkMapData(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new ForgeSyncResponsePayload(new SyncResponsePayload(chunks, isComplete, worldId, status));
        }
    }

    // ===== 同步进度适配器 =====

    public record ForgeSyncProgressPayload(SyncProgressPayload data) implements CustomPacketPayload {
        public static final Type<ForgeSyncProgressPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_PROGRESS_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, ForgeSyncProgressPayload> STREAM_CODEC =
            StreamCodec.of(ForgeSyncProgressPayload::encode, ForgeSyncProgressPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, ForgeSyncProgressPayload payload) {
            buf.writeInt(payload.data.processed());
            buf.writeInt(payload.data.total());
            buf.writeUtf(payload.data.status());
        }

        public static ForgeSyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new ForgeSyncProgressPayload(new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf()));
        }
    }

    // ===== 服务端已安装适配器 =====

    public record ForgeServerInstalledPayload(ServerInstalledPayload data) implements CustomPacketPayload {
        public static final Type<ForgeServerInstalledPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SERVER_INSTALLED_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, ForgeServerInstalledPayload> STREAM_CODEC =
            StreamCodec.of(ForgeServerInstalledPayload::encode, ForgeServerInstalledPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, ForgeServerInstalledPayload payload) {
            buf.writeUtf(payload.data.version());
        }

        public static ForgeServerInstalledPayload decode(RegistryFriendlyByteBuf buf) {
            return new ForgeServerInstalledPayload(new ServerInstalledPayload(buf.readUtf()));
        }
    }

    // ===== ChunkMapData 序列化（共享逻辑）=====

    private static void encodeChunkMapData(RegistryFriendlyByteBuf buf, ChunkMapData data) {
        buf.writeInt(data.regionX);
        buf.writeInt(data.regionZ);
        buf.writeUtf(data.dimension);
        buf.writeByteArray(data.data);
        buf.writeLong(data.timestampSeconds);

        boolean hasCaveLayer = data.caveLayer != Integer.MAX_VALUE;
        buf.writeBoolean(hasCaveLayer);
        if (hasCaveLayer) {
            buf.writeInt(data.caveLayer);
        }
    }

    private static ChunkMapData decodeChunkMapData(RegistryFriendlyByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readUtf();
        byte[] data = buf.readByteArray();
        long timestampSeconds = buf.readLong();

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