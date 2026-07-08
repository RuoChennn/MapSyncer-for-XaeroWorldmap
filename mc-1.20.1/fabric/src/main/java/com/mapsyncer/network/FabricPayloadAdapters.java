package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
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
 * Fabric 1.20.1 Payload 适配器
 *
 * 提供 Fabric Networking API (Identifier-based) 需要的 ResourceLocation 通道常量
 * 和 FriendlyByteBuf 序列化方法。
 *
 * <p>MC 1.20.1 不支持 PayloadTypeRegistry/CustomPacketPayload/StreamCodec，
 * 使用旧版 Identifier + FriendlyByteBuf 通道模式。</p>
 */
public class FabricPayloadAdapters {

    // ===== 通道 ID 常量 =====

    public static final ResourceLocation SYNC_REQUEST_ID = new ResourceLocation(MapSyncer.MOD_ID, "sync_request");
    public static final ResourceLocation SYNC_RESPONSE_ID = new ResourceLocation(MapSyncer.MOD_ID, "sync_response");
    public static final ResourceLocation SYNC_PROGRESS_ID = new ResourceLocation(MapSyncer.MOD_ID, "sync_progress");
    public static final ResourceLocation SERVER_INSTALLED_ID = new ResourceLocation(MapSyncer.MOD_ID, "server_installed");
    public static final ResourceLocation CONTRIBUTION_REQUEST_ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_REQUEST_ID);
    public static final ResourceLocation CONTRIBUTION_DATA_ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_DATA_ID);
    public static final ResourceLocation CONTRIBUTION_COMPLETE_ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_COMPLETE_ID);
    public static final ResourceLocation CONTRIBUTION_ONLY_REQUEST_ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID);
    public static final ResourceLocation CONTRIBUTION_RESULT_ID =
            new ResourceLocation(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_RESULT_ID);

    // ===== 同步请求 =====

    public static void writeSyncRequest(FriendlyByteBuf buf, SyncRequestPayload payload) {
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

    public static SyncRequestPayload readSyncRequest(FriendlyByteBuf buf) {
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

    // ===== 同步响应 =====

    public static void writeSyncResponse(FriendlyByteBuf buf, SyncResponsePayload payload) {
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

    // ===== 同步进度 =====

    public static void writeSyncProgress(FriendlyByteBuf buf, SyncProgressPayload payload) {
        buf.writeInt(payload.processed());
        buf.writeInt(payload.total());
        buf.writeUtf(payload.status());
    }

    public static SyncProgressPayload readSyncProgress(FriendlyByteBuf buf) {
        return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
    }

    // ===== 服务端已安装 =====

    public static void writeServerInstalled(FriendlyByteBuf buf, ServerInstalledPayload payload) {
        buf.writeUtf(payload.version());
        buf.writeLong(payload.lastGenerationTimestamp());
        buf.writeInt(payload.autoSyncIntervalMinutes());
    }

    public static ServerInstalledPayload readServerInstalled(FriendlyByteBuf buf) {
        return new ServerInstalledPayload(buf.readUtf(), buf.readLong(), buf.readInt());
    }

    // ===== 贡献请求 =====

    public static void writeContributionRequest(FriendlyByteBuf buf, ContributionRequestPayload payload) {
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

    public static ContributionRequestPayload readContributionRequest(FriendlyByteBuf buf) {
        int requestId = buf.readInt();
        int size = buf.readInt();
        List<ContributionRegionMeta> regions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            regions.add(new ContributionRegionMeta(
                    buf.readUtf(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readUtf()
            ));
        }
        String status = buf.readUtf();
        return new ContributionRequestPayload(requestId, regions, status);
    }

    // ===== 贡献数据 =====

    public static void writeContributionData(FriendlyByteBuf buf, ContributionDataPayload payload) {
        buf.writeInt(payload.requestId());
        writeChunkMapData(buf, payload.chunk());
        buf.writeUtf(payload.relativePath());
        buf.writeLong(payload.observedServerTimestampSeconds());
        buf.writeUtf(payload.observedServerHash());
    }

    public static ContributionDataPayload readContributionData(FriendlyByteBuf buf) {
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

    // ===== 贡献完成 =====

    public static void writeContributionComplete(FriendlyByteBuf buf, ContributionCompletePayload payload) {
        buf.writeInt(payload.requestId());
        buf.writeInt(payload.sentRegions());
        buf.writeUtf(payload.status());
    }

    public static ContributionCompletePayload readContributionComplete(FriendlyByteBuf buf) {
        return new ContributionCompletePayload(buf.readInt(), buf.readInt(), buf.readUtf());
    }

    // ===== 仅贡献请求 =====

    public static void writeContributionOnlyRequest(FriendlyByteBuf buf, ContributionOnlyRequestPayload payload) {
        buf.writeInt(payload.requestId());
        buf.writeInt(payload.partIndex());
        buf.writeInt(payload.totalParts());
        buf.writeInt(payload.clientMeta().size());
        for (var entry : payload.clientMeta().entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeLong(entry.getValue().timestampSeconds());
            buf.writeUtf(entry.getValue().hash());
        }
        buf.writeUtf(payload.reason());
    }

    public static ContributionOnlyRequestPayload readContributionOnlyRequest(FriendlyByteBuf buf) {
        int requestId = buf.readInt();
        int partIndex = buf.readInt();
        int totalParts = buf.readInt();
        int size = buf.readInt();
        Map<String, ClientMeta> metaMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String path = buf.readUtf();
            long timestampSeconds = buf.readLong();
            String hash = buf.readUtf();
            metaMap.put(path, new ClientMeta(timestampSeconds, hash));
        }
        return new ContributionOnlyRequestPayload(requestId, partIndex, totalParts, metaMap, buf.readUtf());
    }

    // ===== 贡献结果 =====

    public static void writeContributionResult(FriendlyByteBuf buf, ContributionResultPayload payload) {
        buf.writeInt(payload.requestId());
        buf.writeInt(payload.accepted());
        buf.writeInt(payload.rejected());
        buf.writeUtf(payload.status());
        buf.writeBoolean(payload.terminal());
    }

    public static ContributionResultPayload readContributionResult(FriendlyByteBuf buf) {
        return new ContributionResultPayload(buf.readInt(), buf.readInt(), buf.readInt(), buf.readUtf(), buf.readBoolean());
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
        buf.writeBoolean(data.totalParts > 1);
        if (data.totalParts > 1) {
            buf.writeInt(data.partIndex);
            buf.writeInt(data.totalParts);
        }
    }

    private static ChunkMapData readChunkMapData(FriendlyByteBuf buf) {
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
