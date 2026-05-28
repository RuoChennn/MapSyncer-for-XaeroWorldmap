package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric Payload 适配器
 *
 * 提供 Fabric Networking API v1 需要的 Identifier 常量和 PacketByteBuf 序列化方法。
 * Payload DTOs 在 platform-api 中定义为平台无关的纯 record。
 */
public class FabricPayloadAdapters {

    // ===== Identifier 常量（Fabric 需要 Identifier 类型） =====

    public static final Identifier SYNC_REQUEST_ID = Identifier.of(MapSyncer.MOD_ID, "sync_request");
    public static final Identifier SYNC_RESPONSE_ID = Identifier.of(MapSyncer.MOD_ID, "sync_response");
    public static final Identifier SYNC_PROGRESS_ID = Identifier.of(MapSyncer.MOD_ID, "sync_progress");
    public static final Identifier SERVER_INSTALLED_ID = Identifier.of(MapSyncer.MOD_ID, "server_installed");

    // ===== 同步请求 Payload =====

    public static void writeSyncRequest(PacketByteBuf buf, SyncRequestPayload payload) {
        buf.writeInt(payload.clientMeta().size());
        for (var entry : payload.clientMeta().entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeLong(entry.getValue().timestampSeconds());
            buf.writeString(entry.getValue().hash());
        }
    }

    public static SyncRequestPayload readSyncRequest(PacketByteBuf buf) {
        int size = buf.readInt();
        Map<String, ClientMeta> metaMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String path = buf.readString();
            long timestampSeconds = buf.readLong();
            String hash = buf.readString();
            metaMap.put(path, new ClientMeta(timestampSeconds, hash));
        }
        return new SyncRequestPayload(metaMap);
    }

    // ===== 同步响应 Payload =====

    public static void writeSyncResponse(PacketByteBuf buf, SyncResponsePayload payload) {
        buf.writeInt(payload.worldId());
        buf.writeInt(payload.chunks().size());
        for (ChunkMapData chunk : payload.chunks()) {
            writeChunkMapData(buf, chunk);
        }
        buf.writeBoolean(payload.isComplete());
        buf.writeString(payload.status());
    }

    public static SyncResponsePayload readSyncResponse(PacketByteBuf buf) {
        int worldId = buf.readInt();
        int size = buf.readInt();
        List<ChunkMapData> chunks = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            chunks.add(readChunkMapData(buf));
        }
        boolean isComplete = buf.readBoolean();
        String status = buf.readString();
        return new SyncResponsePayload(chunks, isComplete, worldId, status);
    }

    // ===== 同步进度 Payload =====

    public static void writeSyncProgress(PacketByteBuf buf, SyncProgressPayload payload) {
        buf.writeInt(payload.processed());
        buf.writeInt(payload.total());
        buf.writeString(payload.status());
    }

    public static SyncProgressPayload readSyncProgress(PacketByteBuf buf) {
        return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readString());
    }

    // ===== 服务端已安装 Payload =====

    public static void writeServerInstalled(PacketByteBuf buf, ServerInstalledPayload payload) {
        buf.writeString(payload.version());
    }

    public static ServerInstalledPayload readServerInstalled(PacketByteBuf buf) {
        return new ServerInstalledPayload(buf.readString());
    }

    // ===== ChunkMapData 序列化 =====

    private static void writeChunkMapData(PacketByteBuf buf, ChunkMapData data) {
        buf.writeInt(data.regionX);
        buf.writeInt(data.regionZ);
        buf.writeString(data.dimension);
        buf.writeByteArray(data.data);
        buf.writeLong(data.timestampSeconds);

        boolean hasCaveLayer = data.caveLayer != Integer.MAX_VALUE;
        buf.writeBoolean(hasCaveLayer);
        if (hasCaveLayer) {
            buf.writeInt(data.caveLayer);
        }
    }

    private static ChunkMapData readChunkMapData(PacketByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readString();
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