package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.ServerInstalledWireCodec;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncRequestWireCodec;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric 26.x Payload 适配器
 *
 * 将 platform-api 中的平台无关 Payload 包装为 Fabric CustomPacketPayload，
 * 并提供 StreamCodec 用于序列化/反序列化。
 */
public class FabricPayloadAdapters {

    // ===== CustomPacketPayload.Type 常量 =====

    public static final CustomPacketPayload.Type<SyncRequestWrapper> SYNC_REQUEST_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, "sync_request"));
    public static final CustomPacketPayload.Type<SyncResponseWrapper> SYNC_RESPONSE_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, "sync_response"));
    public static final CustomPacketPayload.Type<SyncProgressWrapper> SYNC_PROGRESS_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, "sync_progress"));
    public static final CustomPacketPayload.Type<ServerInstalledWrapper> SERVER_INSTALLED_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, "server_installed"));

    // ===== StreamCodec 定义 =====

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequestWrapper> SYNC_REQUEST_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeSyncRequest(buf, wrapper.payload()),
                    buf -> new SyncRequestWrapper(readSyncRequest(buf))
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncResponseWrapper> SYNC_RESPONSE_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeSyncResponse(buf, wrapper.payload()),
                    buf -> new SyncResponseWrapper(readSyncResponse(buf))
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncProgressWrapper> SYNC_PROGRESS_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeSyncProgress(buf, wrapper.payload()),
                    buf -> new SyncProgressWrapper(readSyncProgress(buf))
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerInstalledWrapper> SERVER_INSTALLED_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeServerInstalled(buf, wrapper.payload()),
                    buf -> new ServerInstalledWrapper(readServerInstalled(buf))
            );

    // ===== CustomPacketPayload Wrapper Records =====

    public record SyncRequestWrapper(SyncRequestPayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return SYNC_REQUEST_TYPE;
        }
    }

    public record SyncResponseWrapper(SyncResponsePayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return SYNC_RESPONSE_TYPE;
        }
    }

    public record SyncProgressWrapper(SyncProgressPayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return SYNC_PROGRESS_TYPE;
        }
    }

    public record ServerInstalledWrapper(ServerInstalledPayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return SERVER_INSTALLED_TYPE;
        }
    }

    // ===== 同步请求序列化 =====

    private static void writeSyncRequest(RegistryFriendlyByteBuf buf, SyncRequestPayload payload) {
        SyncRequestWireCodec.write(buf, payload);
    }

    private static SyncRequestPayload readSyncRequest(RegistryFriendlyByteBuf buf) {
        return SyncRequestWireCodec.read(buf);
    }

    // ===== 同步响应序列化 =====

    private static void writeSyncResponse(RegistryFriendlyByteBuf buf, SyncResponsePayload payload) {
        buf.writeInt(payload.worldId());
        buf.writeInt(payload.chunks().size());
        for (ChunkMapData chunk : payload.chunks()) {
            writeChunkMapData(buf, chunk);
        }
        buf.writeBoolean(payload.isComplete());
        buf.writeUtf(payload.status());
    }

    private static SyncResponsePayload readSyncResponse(RegistryFriendlyByteBuf buf) {
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

    // ===== 同步进度序列化 =====

    private static void writeSyncProgress(RegistryFriendlyByteBuf buf, SyncProgressPayload payload) {
        buf.writeInt(payload.processed());
        buf.writeInt(payload.total());
        buf.writeUtf(payload.status());
    }

    private static SyncProgressPayload readSyncProgress(RegistryFriendlyByteBuf buf) {
        return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
    }

    // ===== 服务端已安装序列化 =====

    private static void writeServerInstalled(RegistryFriendlyByteBuf buf, ServerInstalledPayload payload) {
        ServerInstalledWireCodec.write(buf, payload);
    }

    private static ServerInstalledPayload readServerInstalled(RegistryFriendlyByteBuf buf) {
        return ServerInstalledWireCodec.read(buf);
    }

    // ===== ChunkMapData 序列化 =====

    private static void writeChunkMapData(RegistryFriendlyByteBuf buf, ChunkMapData data) {
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
        buf.writeBoolean(data.totalParts > 1);
        if (data.totalParts > 1) {
            buf.writeInt(data.partIndex);
            buf.writeInt(data.totalParts);
        }
    }

    private static ChunkMapData readChunkMapData(RegistryFriendlyByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readUtf();
        byte[] data = buf.readByteArray();
        long timestampSeconds = buf.readLong();

        int caveLayer = Integer.MAX_VALUE;
        if (buf.readableBytes() > 0) {
            boolean hasCaveLayer = buf.readBoolean();
            if (hasCaveLayer) {
                caveLayer = buf.readInt();
            }
        }

        int partIndex = 0;
        int totalParts = 0;
        if (buf.readableBytes() > 0) {
            boolean isSplit = buf.readBoolean();
            if (isSplit) {
                partIndex = buf.readInt();
                totalParts = buf.readInt();
            }
        }

        return new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds, caveLayer, partIndex, totalParts);
    }
}
