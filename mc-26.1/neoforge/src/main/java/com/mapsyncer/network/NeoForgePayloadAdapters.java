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
 * NeoForge Payload 适配器
 *
 * 将平台无关的 Payload DTO 适配到 NeoForge 的 CustomPacketPayload 接口。
 * 每个适配器包含 StreamCodec 用于网络序列化。
 */
public class NeoForgePayloadAdapters {

    // ===== 同步请求适配器 =====

    public record NeoForgeSyncRequestPayload(SyncRequestPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeSyncRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_REQUEST_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeSyncRequestPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeSyncRequestPayload::encode, NeoForgeSyncRequestPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeSyncRequestPayload payload) {
            buf.writeInt(payload.data.clientMeta().size());
            for (var entry : payload.data.clientMeta().entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
            buf.writeBoolean(payload.data.totalParts() > 1);
            if (payload.data.totalParts() > 1) {
                buf.writeInt(payload.data.partIndex());
                buf.writeInt(payload.data.totalParts());
            }
        }

        public static NeoForgeSyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
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

            return new NeoForgeSyncRequestPayload(new SyncRequestPayload(metaMap, partIndex, totalParts));
        }
    }

    // ===== 同步响应适配器 =====

    public record NeoForgeSyncResponsePayload(SyncResponsePayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeSyncResponsePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_RESPONSE_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeSyncResponsePayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeSyncResponsePayload::encode, NeoForgeSyncResponsePayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeSyncResponsePayload payload) {
            buf.writeInt(payload.data.worldId());
            buf.writeInt(payload.data.chunks().size());
            for (ChunkMapData chunk : payload.data.chunks()) {
                encodeChunkMapData(buf, chunk);
            }
            buf.writeBoolean(payload.data.isComplete());
            buf.writeUtf(payload.data.status());
        }

        public static NeoForgeSyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
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
        public static final Type<NeoForgeSyncProgressPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_PROGRESS_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeSyncProgressPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeSyncProgressPayload::encode, NeoForgeSyncProgressPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeSyncProgressPayload payload) {
            buf.writeInt(payload.data.processed());
            buf.writeInt(payload.data.total());
            buf.writeUtf(payload.data.status());
        }

        public static NeoForgeSyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new NeoForgeSyncProgressPayload(new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf()));
        }
    }

    // ===== 服务端已安装适配器 =====

    public record NeoForgeServerInstalledPayload(ServerInstalledPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeServerInstalledPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SERVER_INSTALLED_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeServerInstalledPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeServerInstalledPayload::encode, NeoForgeServerInstalledPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeServerInstalledPayload payload) {
            buf.writeUtf(payload.data.version());
            buf.writeLong(payload.data.lastGenerationTimestamp());
            buf.writeInt(payload.data.autoSyncIntervalMinutes());
        }

        public static NeoForgeServerInstalledPayload decode(RegistryFriendlyByteBuf buf) {
            return new NeoForgeServerInstalledPayload(new ServerInstalledPayload(buf.readUtf(), buf.readLong(), buf.readInt()));
        }
    }

    // ===== 贡献请求适配器 =====

    public record NeoForgeContributionRequestPayload(ContributionRequestPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeContributionRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_REQUEST_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeContributionRequestPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeContributionRequestPayload::encode, NeoForgeContributionRequestPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeContributionRequestPayload payload) {
            buf.writeInt(payload.data.requestId());
            buf.writeInt(payload.data.regions().size());
            for (ContributionRegionMeta region : payload.data.regions()) {
                buf.writeUtf(region.relativePath());
                buf.writeInt(region.regionX());
                buf.writeInt(region.regionZ());
                buf.writeUtf(region.dimension());
                buf.writeInt(region.caveLayer());
                buf.writeLong(region.serverTimestampSeconds());
                buf.writeUtf(region.serverHash());
            }
            buf.writeUtf(payload.data.status());
        }

        public static NeoForgeContributionRequestPayload decode(RegistryFriendlyByteBuf buf) {
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
            String status = buf.readUtf();
            return new NeoForgeContributionRequestPayload(new ContributionRequestPayload(requestId, regions, status));
        }
    }

    // ===== 贡献数据适配器 =====

    public record NeoForgeContributionDataPayload(ContributionDataPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeContributionDataPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_DATA_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeContributionDataPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeContributionDataPayload::encode, NeoForgeContributionDataPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeContributionDataPayload payload) {
            buf.writeInt(payload.data.requestId());
            encodeChunkMapData(buf, payload.data.chunk());
            buf.writeUtf(payload.data.relativePath());
            buf.writeLong(payload.data.observedServerTimestampSeconds());
            buf.writeUtf(payload.data.observedServerHash());
        }

        public static NeoForgeContributionDataPayload decode(RegistryFriendlyByteBuf buf) {
            int requestId = buf.readInt();
            ChunkMapData chunk = decodeChunkMapData(buf);
            String relativePath = buf.readUtf();
            long observedServerTimestampSeconds = buf.readLong();
            String observedServerHash = buf.readUtf();
            return new NeoForgeContributionDataPayload(new ContributionDataPayload(
                requestId,
                chunk,
                relativePath,
                observedServerTimestampSeconds,
                observedServerHash
            ));
        }
    }

    // ===== 贡献完成适配器 =====

    public record NeoForgeContributionCompletePayload(ContributionCompletePayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeContributionCompletePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_COMPLETE_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeContributionCompletePayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeContributionCompletePayload::encode, NeoForgeContributionCompletePayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeContributionCompletePayload payload) {
            buf.writeInt(payload.data.requestId());
            buf.writeInt(payload.data.sentRegions());
            buf.writeUtf(payload.data.status());
        }

        public static NeoForgeContributionCompletePayload decode(RegistryFriendlyByteBuf buf) {
            return new NeoForgeContributionCompletePayload(new ContributionCompletePayload(
                buf.readInt(),
                buf.readInt(),
                buf.readUtf()
            ));
        }
    }

    // ===== 贡献结果适配器 =====

    public record NeoForgeContributionResultPayload(ContributionResultPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeContributionResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.CONTRIBUTION_RESULT_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeContributionResultPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeContributionResultPayload::encode, NeoForgeContributionResultPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeContributionResultPayload payload) {
            buf.writeInt(payload.data.requestId());
            buf.writeInt(payload.data.accepted());
            buf.writeInt(payload.data.rejected());
            buf.writeUtf(payload.data.status());
        }

        public static NeoForgeContributionResultPayload decode(RegistryFriendlyByteBuf buf) {
            return new NeoForgeContributionResultPayload(new ContributionResultPayload(
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readUtf()
            ));
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
        buf.writeBoolean(data.totalParts > 1);
        if (data.totalParts > 1) {
            buf.writeInt(data.partIndex);
            buf.writeInt(data.totalParts);
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
