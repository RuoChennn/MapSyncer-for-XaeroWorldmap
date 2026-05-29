package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge Payload 适配器
 *
 * 将平台无关的 Payload DTO 适配到 NeoForge 的 CustomPacketPayload 接口。
 * NeoForge 20.4.x 使用 write()/id() 模式（无 StreamCodec）。
 */
public class NeoForgePayloadAdapters {

    // ===== 同步请求适配器 =====

    public record NeoForgeSyncRequestPayload(SyncRequestPayload data) implements CustomPacketPayload {
        public static final ResourceLocation ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.SYNC_REQUEST_ID);

        public static final FriendlyByteBuf.Reader<NeoForgeSyncRequestPayload> READER =
            NeoForgeSyncRequestPayload::decode;

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeInt(data.clientMeta().size());
            for (var entry : data.clientMeta().entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
        }

        public static NeoForgeSyncRequestPayload decode(FriendlyByteBuf buf) {
            int size = buf.readInt();
            Map<String, ClientMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String path = buf.readUtf();
                long timestampSeconds = buf.readLong();
                String hash = buf.readUtf();
                metaMap.put(path, new ClientMeta(timestampSeconds, hash));
            }
            return new NeoForgeSyncRequestPayload(new SyncRequestPayload(metaMap));
        }
    }

    // ===== 同步响应适配器 =====

    public record NeoForgeSyncResponsePayload(SyncResponsePayload data) implements CustomPacketPayload {
        public static final ResourceLocation ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.SYNC_RESPONSE_ID);

        public static final FriendlyByteBuf.Reader<NeoForgeSyncResponsePayload> READER =
            NeoForgeSyncResponsePayload::decode;

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeInt(data.worldId());
            buf.writeInt(data.chunks().size());
            for (ChunkMapData chunk : data.chunks()) {
                encodeChunkMapData(buf, chunk);
            }
            buf.writeBoolean(data.isComplete());
            buf.writeUtf(data.status());
        }

        public static NeoForgeSyncResponsePayload decode(FriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(decodeChunkMapData(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new NeoForgeSyncResponsePayload(new SyncResponsePayload(chunks, isComplete, worldId, status));
        }
    }

    // ===== 同步进度适配器 =====

    public record NeoForgeSyncProgressPayload(SyncProgressPayload data) implements CustomPacketPayload {
        public static final ResourceLocation ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.SYNC_PROGRESS_ID);

        public static final FriendlyByteBuf.Reader<NeoForgeSyncProgressPayload> READER =
            NeoForgeSyncProgressPayload::decode;

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeInt(data.processed());
            buf.writeInt(data.total());
            buf.writeUtf(data.status());
        }

        public static NeoForgeSyncProgressPayload decode(FriendlyByteBuf buf) {
            return new NeoForgeSyncProgressPayload(new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf()));
        }
    }

    // ===== 服务端已安装适配器 =====

    public record NeoForgeServerInstalledPayload(ServerInstalledPayload data) implements CustomPacketPayload {
        public static final ResourceLocation ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.SERVER_INSTALLED_ID);

        public static final FriendlyByteBuf.Reader<NeoForgeServerInstalledPayload> READER =
            NeoForgeServerInstalledPayload::decode;

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(data.version());
        }

        public static NeoForgeServerInstalledPayload decode(FriendlyByteBuf buf) {
            return new NeoForgeServerInstalledPayload(new ServerInstalledPayload(buf.readUtf()));
        }
    }

    // ===== ChunkMapData 序列化（共享逻辑）=====

    private static void encodeChunkMapData(FriendlyByteBuf buf, ChunkMapData data) {
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

    private static ChunkMapData decodeChunkMapData(FriendlyByteBuf buf) {
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
