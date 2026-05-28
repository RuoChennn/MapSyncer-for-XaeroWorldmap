package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

import java.util.List;

/**
 * 同步响应包 - 平台无关版本
 *
 * 服务端发送需要更新的地图数据给客户端。
 * 可能包含多个region的数据，并标识是否为最后一包。
 *
 * @param chunks 地图数据列表
 * @param isComplete 是否为最后一包
 * @param worldId 世界ID
 * @param status 同步状态："ok", "uptodate", "no_cache", "dim_not_available"
 */
public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId, String status) {
    public static final String ID = NetworkHandler.SYNC_RESPONSE_ID;
}