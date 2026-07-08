package com.mapsyncer.network;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge Payload 适配器（传统消息方式）
 *
 * Forge 1.20.1 使用 SimpleNetworkWrapper，消息类不需要实现 CustomPacketPayload 接口。
 * 只需要提供 encode/decode 方法供 SimpleChannel 使用。
 */
public class ForgePayloadAdapters {

    // ===== 同步请求消息 =====

    public static class ForgeSyncRequestMessage {
        private final SyncRequestPayload data;

        public ForgeSyncRequestMessage(SyncRequestPayload data) {
            this.data = data;
        }

        public SyncRequestPayload getData() {
            return data;
        }

        public static void encode(ForgeSyncRequestMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.clientMeta().size());
            for (var entry : msg.data.clientMeta().entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
            buf.writeBoolean(msg.data.totalParts() > 1);
            if (msg.data.totalParts() > 1) {
                buf.writeInt(msg.data.partIndex());
                buf.writeInt(msg.data.totalParts());
            }
        }

        public static ForgeSyncRequestMessage decode(FriendlyByteBuf buf) {
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
            if (buf.isReadable()) {
                boolean isSplit = buf.readBoolean();
                if (isSplit) {
                    partIndex = buf.readInt();
                    totalParts = buf.readInt();
                }
            }

            return new ForgeSyncRequestMessage(new SyncRequestPayload(metaMap, partIndex, totalParts));
        }
    }

    // ===== 同步响应消息 =====

    public static class ForgeSyncResponseMessage {
        private final SyncResponsePayload data;

        public ForgeSyncResponseMessage(SyncResponsePayload data) {
            this.data = data;
        }

        public SyncResponsePayload getData() {
            return data;
        }

        public static void encode(ForgeSyncResponseMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.worldId());
            buf.writeInt(msg.data.chunks().size());
            for (ChunkMapData chunk : msg.data.chunks()) {
                encodeChunkMapData(buf, chunk);
            }
            buf.writeBoolean(msg.data.isComplete());
            buf.writeUtf(msg.data.status());
        }

        public static ForgeSyncResponseMessage decode(FriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(decodeChunkMapData(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new ForgeSyncResponseMessage(new SyncResponsePayload(chunks, isComplete, worldId, status));
        }
    }

    // ===== 同步进度消息 =====

    public static class ForgeSyncProgressMessage {
        private final SyncProgressPayload data;

        public ForgeSyncProgressMessage(SyncProgressPayload data) {
            this.data = data;
        }

        public SyncProgressPayload getData() {
            return data;
        }

        public static void encode(ForgeSyncProgressMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.processed());
            buf.writeInt(msg.data.total());
            buf.writeUtf(msg.data.status());
        }

        public static ForgeSyncProgressMessage decode(FriendlyByteBuf buf) {
            return new ForgeSyncProgressMessage(new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf()));
        }
    }

    // ===== 服务端已安装消息 =====

    public static class ForgeServerInstalledMessage {
        private final ServerInstalledPayload data;

        public ForgeServerInstalledMessage(ServerInstalledPayload data) {
            this.data = data;
        }

        public ServerInstalledPayload getData() {
            return data;
        }

        public static void encode(ForgeServerInstalledMessage msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.data.version());
            buf.writeLong(msg.data.lastGenerationTimestamp());
            buf.writeInt(msg.data.autoSyncIntervalMinutes());
        }

        public static ForgeServerInstalledMessage decode(FriendlyByteBuf buf) {
            return new ForgeServerInstalledMessage(new ServerInstalledPayload(buf.readUtf(), buf.readLong(), buf.readInt()));
        }
    }

    // ===== 贡献请求消息 =====

    public static class ForgeContributionRequestMessage {
        private final ContributionRequestPayload data;

        public ForgeContributionRequestMessage(ContributionRequestPayload data) {
            this.data = data;
        }

        public ContributionRequestPayload getData() {
            return data;
        }

        public static void encode(ForgeContributionRequestMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.requestId());
            buf.writeInt(msg.data.regions().size());
            for (ContributionRegionMeta region : msg.data.regions()) {
                buf.writeUtf(region.relativePath());
                buf.writeInt(region.regionX());
                buf.writeInt(region.regionZ());
                buf.writeUtf(region.dimension());
                buf.writeInt(region.caveLayer());
                buf.writeLong(region.serverTimestampSeconds());
                buf.writeUtf(region.serverHash());
            }
            buf.writeUtf(msg.data.status());
        }

        public static ForgeContributionRequestMessage decode(FriendlyByteBuf buf) {
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
            return new ForgeContributionRequestMessage(new ContributionRequestPayload(requestId, regions, status));
        }
    }

    // ===== 贡献数据消息 =====

    public static class ForgeContributionDataMessage {
        private final ContributionDataPayload data;

        public ForgeContributionDataMessage(ContributionDataPayload data) {
            this.data = data;
        }

        public ContributionDataPayload getData() {
            return data;
        }

        public static void encode(ForgeContributionDataMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.requestId());
            encodeChunkMapData(buf, msg.data.chunk());
            buf.writeUtf(msg.data.relativePath());
            buf.writeLong(msg.data.observedServerTimestampSeconds());
            buf.writeUtf(msg.data.observedServerHash());
        }

        public static ForgeContributionDataMessage decode(FriendlyByteBuf buf) {
            int requestId = buf.readInt();
            ChunkMapData chunk = decodeChunkMapData(buf);
            String relativePath = buf.readUtf();
            long observedServerTimestampSeconds = buf.readLong();
            String observedServerHash = buf.readUtf();
            return new ForgeContributionDataMessage(new ContributionDataPayload(
                    requestId,
                    chunk,
                    relativePath,
                    observedServerTimestampSeconds,
                    observedServerHash
            ));
        }
    }

    // ===== 贡献完成消息 =====

    public static class ForgeContributionCompleteMessage {
        private final ContributionCompletePayload data;

        public ForgeContributionCompleteMessage(ContributionCompletePayload data) {
            this.data = data;
        }

        public ContributionCompletePayload getData() {
            return data;
        }

        public static void encode(ForgeContributionCompleteMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.requestId());
            buf.writeInt(msg.data.sentRegions());
            buf.writeUtf(msg.data.status());
        }

        public static ForgeContributionCompleteMessage decode(FriendlyByteBuf buf) {
            return new ForgeContributionCompleteMessage(new ContributionCompletePayload(
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf()
            ));
        }
    }

    // ===== 仅贡献请求消息 =====

    public static class ForgeContributionOnlyRequestMessage {
        private final ContributionOnlyRequestPayload data;

        public ForgeContributionOnlyRequestMessage(ContributionOnlyRequestPayload data) {
            this.data = data;
        }

        public ContributionOnlyRequestPayload getData() {
            return data;
        }

        public static void encode(ForgeContributionOnlyRequestMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.requestId());
            buf.writeInt(msg.data.partIndex());
            buf.writeInt(msg.data.totalParts());
            buf.writeInt(msg.data.clientMeta().size());
            for (var entry : msg.data.clientMeta().entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
            buf.writeUtf(msg.data.reason());
        }

        public static ForgeContributionOnlyRequestMessage decode(FriendlyByteBuf buf) {
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
            return new ForgeContributionOnlyRequestMessage(
                    new ContributionOnlyRequestPayload(requestId, partIndex, totalParts, metaMap, buf.readUtf())
            );
        }
    }

    // ===== 贡献结果消息 =====

    public static class ForgeContributionResultMessage {
        private final ContributionResultPayload data;

        public ForgeContributionResultMessage(ContributionResultPayload data) {
            this.data = data;
        }

        public ContributionResultPayload getData() {
            return data;
        }

        public static void encode(ForgeContributionResultMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.requestId());
            buf.writeInt(msg.data.accepted());
            buf.writeInt(msg.data.rejected());
            buf.writeUtf(msg.data.status());
        }

        public static ForgeContributionResultMessage decode(FriendlyByteBuf buf) {
            return new ForgeContributionResultMessage(new ContributionResultPayload(
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf()
            ));
        }
    }

    // ===== ChunkMapData 序列化 =====

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
        buf.writeBoolean(data.totalParts > 1);
        if (data.totalParts > 1) {
            buf.writeInt(data.partIndex);
            buf.writeInt(data.totalParts);
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

        int partIndex = 0;
        int totalParts = 0;
        if (buf.isReadable()) {
            boolean isSplit = buf.readBoolean();
            if (isSplit) {
                partIndex = buf.readInt();
                totalParts = buf.readInt();
            }
        }

        return new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds, caveLayer, partIndex, totalParts);
    }
}
