package com.mapsyncer.network.payload;

import net.minecraft.network.FriendlyByteBuf;

import java.util.HashMap;
import java.util.Map;

/**
 * SyncRequestPayload 网络序列化（需在 MC 模块中编译）。
 */
public final class SyncRequestWireCodec {

    private SyncRequestWireCodec() {}

    public static void write(FriendlyByteBuf buf, SyncRequestPayload payload) {
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
        buf.writeBoolean(payload.syncAll());
        if (!payload.syncAll()) {
            buf.writeUtf(payload.targetDimension());
        }
        buf.writeBoolean(payload.silent());
    }

    public static SyncRequestPayload read(FriendlyByteBuf buf) {
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

        boolean syncAll = false;
        String targetDimension = "";
        if (buf.readableBytes() > 0) {
            syncAll = buf.readBoolean();
            if (!syncAll && buf.readableBytes() > 0) {
                targetDimension = buf.readUtf();
            }
        } else if (metaMap.isEmpty()) {
            syncAll = true;
        }

        boolean silent = false;
        if (buf.readableBytes() > 0) {
            silent = buf.readBoolean();
        }

        return new SyncRequestPayload(metaMap, partIndex, totalParts, syncAll, targetDimension, silent);
    }
}
