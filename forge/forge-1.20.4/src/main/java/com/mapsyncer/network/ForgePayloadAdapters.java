package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge Payload 适配器 - 1.20.4 版本
 *
 * Forge 49.x 使用 FriendlyByteBuf 进行序列化。
 */
public class ForgePayloadAdapters {

    // ===== 同步请求 Payload =====

    public static void writeSyncRequest(SyncRequestPayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.clientMeta().size());
        for (var entry : payload.clientMeta().entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeLong(entry.getValue().timestampSeconds());
            buf.writeUtf(entry.getValue().hash());
        }
    }

    public static SyncRequestPayload readSyncRequest(FriendlyByteBuf buf) {
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

    // ===== 同步响应 Payload =====

    public static void writeSyncResponse(SyncResponsePayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.worldId());
        buf.writeInt(payload.chunks().size());
        for (ChunkMapData chunk : payload.chunks()) {
            writeChunkMapData(buf, chunk);
        }
        buf.writeBoolean(payload.isComplete());
        buf.writeUtf(payload.status());
    }

    public static SyncResponsePayload readSyncResponse(FriendlyByteBuf buf) {
        int worldId = buf.readInt();
        int size = buf.readInt();
        List<ChunkMapData> chunks = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            chunks.add(readChunkMapData(buf));
        }
        boolean isComplete = buf.readBoolean();
        String status = buf.readUtf();
        return new SyncResponsePayload(chunks, isComplete, worldId, status);
    }

    // ===== 同步进度 Payload =====

    public static void writeSyncProgress(SyncProgressPayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.processed());
        buf.writeInt(payload.total());
        buf.writeUtf(payload.status());
    }

    public static SyncProgressPayload readSyncProgress(FriendlyByteBuf buf) {
        return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
    }

    // ===== 服务端已安装 Payload =====

    public static void writeServerInstalled(ServerInstalledPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.version());
    }

    public static ServerInstalledPayload readServerInstalled(FriendlyByteBuf buf) {
        return new ServerInstalledPayload(buf.readUtf());
    }

    // ===== ChunkMapData 序列化 =====

    private static void writeChunkMapData(FriendlyByteBuf buf, ChunkMapData data) {
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

    private static ChunkMapData readChunkMapData(FriendlyByteBuf buf) {
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