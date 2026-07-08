package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
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
 * Fabric 1.21.11 Payload 适配器
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
    public static final CustomPacketPayload.Type<ContributionRequestWrapper> CONTRIBUTION_REQUEST_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_REQUEST_ID));
    public static final CustomPacketPayload.Type<ContributionDataWrapper> CONTRIBUTION_DATA_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_DATA_ID));
    public static final CustomPacketPayload.Type<ContributionCompleteWrapper> CONTRIBUTION_COMPLETE_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_COMPLETE_ID));
    public static final CustomPacketPayload.Type<ContributionResultWrapper> CONTRIBUTION_RESULT_TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_RESULT_ID));

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

    public static final StreamCodec<RegistryFriendlyByteBuf, ContributionRequestWrapper> CONTRIBUTION_REQUEST_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeContributionRequest(buf, wrapper.payload()),
                    buf -> new ContributionRequestWrapper(readContributionRequest(buf))
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, ContributionDataWrapper> CONTRIBUTION_DATA_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeContributionData(buf, wrapper.payload()),
                    buf -> new ContributionDataWrapper(readContributionData(buf))
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, ContributionCompleteWrapper> CONTRIBUTION_COMPLETE_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeContributionComplete(buf, wrapper.payload()),
                    buf -> new ContributionCompleteWrapper(readContributionComplete(buf))
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, ContributionResultWrapper> CONTRIBUTION_RESULT_CODEC =
            StreamCodec.of(
                    (buf, wrapper) -> writeContributionResult(buf, wrapper.payload()),
                    buf -> new ContributionResultWrapper(readContributionResult(buf))
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

    public record ContributionRequestWrapper(ContributionRequestPayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return CONTRIBUTION_REQUEST_TYPE;
        }
    }

    public record ContributionDataWrapper(ContributionDataPayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return CONTRIBUTION_DATA_TYPE;
        }
    }

    public record ContributionCompleteWrapper(ContributionCompletePayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return CONTRIBUTION_COMPLETE_TYPE;
        }
    }

    public record ContributionResultWrapper(ContributionResultPayload payload) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return CONTRIBUTION_RESULT_TYPE;
        }
    }

    // ===== 同步请求序列化 =====

    private static void writeSyncRequest(RegistryFriendlyByteBuf buf, SyncRequestPayload payload) {
        buf.writeInt(payload.clientMeta().size());
        for (var entry : payload.clientMeta().entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeLong(entry.getValue().timestampSeconds());
            buf.writeUtf(entry.getValue().hash());
        }
        buf.writeBoolean(payload.totalParts() > 1);
        if (payload.totalParts() > 1) {
            buf.writeInt(payload.partIndex());
            buf.writeInt(payload.totalParts());
        }
    }

    private static SyncRequestPayload readSyncRequest(RegistryFriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<String, ClientMeta> metaMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String path = buf.readUtf();
            long timestampSeconds = buf.readLong();
            String hash = buf.readUtf();
            metaMap.put(path, new ClientMeta(timestampSeconds, hash));
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

        return new SyncRequestPayload(metaMap, partIndex, totalParts);
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
        buf.writeUtf(payload.version());
        buf.writeLong(payload.lastGenerationTimestamp());
        buf.writeInt(payload.autoSyncIntervalMinutes());
    }

    private static ServerInstalledPayload readServerInstalled(RegistryFriendlyByteBuf buf) {
        return new ServerInstalledPayload(buf.readUtf(), buf.readLong(), buf.readInt());
    }

    // ===== 贡献请求序列化 =====

    private static void writeContributionRequest(RegistryFriendlyByteBuf buf, ContributionRequestPayload payload) {
        buf.writeInt(payload.requestId());
        buf.writeInt(payload.regions().size());
        for (ContributionRegionMeta region : payload.regions()) {
            buf.writeUtf(region.relativePath());
            buf.writeInt(region.regionX());
            buf.writeInt(region.regionZ());
            buf.writeUtf(region.dimension());
            buf.writeInt(region.caveLayer());
            buf.writeLong(region.serverTimestampSeconds());
            buf.writeUtf(region.serverHash());
        }
        buf.writeUtf(payload.status());
    }

    private static ContributionRequestPayload readContributionRequest(RegistryFriendlyByteBuf buf) {
        int requestId = buf.readInt();
        int size = buf.readInt();
        List<ContributionRegionMeta> regions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String relativePath = buf.readUtf();
            int regionX = buf.readInt();
            int regionZ = buf.readInt();
            String dimension = buf.readUtf();
            int caveLayer = buf.readInt();
            long serverTimestampSeconds = buf.readLong();
            String serverHash = buf.readUtf();
            regions.add(new ContributionRegionMeta(
                    relativePath,
                    regionX,
                    regionZ,
                    dimension,
                    caveLayer,
                    serverTimestampSeconds,
                    serverHash
            ));
        }
        return new ContributionRequestPayload(requestId, regions, buf.readUtf());
    }

    // ===== 贡献数据序列化 =====

    private static void writeContributionData(RegistryFriendlyByteBuf buf, ContributionDataPayload payload) {
        buf.writeInt(payload.requestId());
        writeChunkMapData(buf, payload.chunk());
        buf.writeUtf(payload.relativePath());
        buf.writeLong(payload.observedServerTimestampSeconds());
        buf.writeUtf(payload.observedServerHash());
    }

    private static ContributionDataPayload readContributionData(RegistryFriendlyByteBuf buf) {
        int requestId = buf.readInt();
        ChunkMapData chunk = readChunkMapData(buf);
        String relativePath = buf.readUtf();
        long observedServerTimestampSeconds = buf.readLong();
        String observedServerHash = buf.readUtf();
        return new ContributionDataPayload(
                requestId,
                chunk,
                relativePath,
                observedServerTimestampSeconds,
                observedServerHash
        );
    }

    // ===== 贡献完成序列化 =====

    private static void writeContributionComplete(RegistryFriendlyByteBuf buf, ContributionCompletePayload payload) {
        buf.writeInt(payload.requestId());
        buf.writeInt(payload.sentRegions());
        buf.writeUtf(payload.status());
    }

    private static ContributionCompletePayload readContributionComplete(RegistryFriendlyByteBuf buf) {
        return new ContributionCompletePayload(buf.readInt(), buf.readInt(), buf.readUtf());
    }

    // ===== 贡献结果序列化 =====

    private static void writeContributionResult(RegistryFriendlyByteBuf buf, ContributionResultPayload payload) {
        buf.writeInt(payload.requestId());
        buf.writeInt(payload.accepted());
        buf.writeInt(payload.rejected());
        buf.writeUtf(payload.status());
    }

    private static ContributionResultPayload readContributionResult(RegistryFriendlyByteBuf buf) {
        return new ContributionResultPayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readUtf());
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
