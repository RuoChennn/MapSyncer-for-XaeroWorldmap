package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仅贡献请求包 - 平台无关版本
 *
 * <p>客户端退出前发送本地元数据到服务端，仅请求服务端生成贡献候选，
 * 不触发服务端地图分发。</p>
 */
public record ContributionOnlyRequestPayload(
        int requestId,
        int partIndex,
        int totalParts,
        Map<String, ClientMeta> clientMeta,
        String reason
) {
    public static final String ID = NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID;
    public static final int MAX_PAYLOAD_BYTES = SyncRequestPayload.MAX_PAYLOAD_BYTES;

    public ContributionOnlyRequestPayload {
        if (partIndex < 0) {
            throw new IllegalArgumentException("partIndex must be >= 0");
        }
        if (totalParts < 1) {
            throw new IllegalArgumentException("totalParts must be >= 1");
        }
        if (partIndex >= totalParts) {
            throw new IllegalArgumentException("partIndex must be < totalParts");
        }
        clientMeta = clientMeta == null ? Map.of() : Map.copyOf(clientMeta);
        reason = reason == null ? "" : reason;
    }

    public static List<ContributionOnlyRequestPayload> split(
            int requestId,
            Map<String, ClientMeta> clientMeta,
            String reason
    ) {
        SyncRequestPayload[] syncParts = SyncRequestPayload.split(clientMeta == null ? Map.of() : clientMeta);
        List<ContributionOnlyRequestPayload> parts = new ArrayList<>(syncParts.length);
        for (SyncRequestPayload syncPart : syncParts) {
            int totalParts = syncPart.totalParts() < 1 ? 1 : syncPart.totalParts();
            parts.add(new ContributionOnlyRequestPayload(
                    requestId,
                    syncPart.partIndex(),
                    totalParts,
                    syncPart.clientMeta(),
                    reason
            ));
        }
        return List.copyOf(parts);
    }
}
