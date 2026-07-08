package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

/**
 * 贡献数据包 - 客户端上传单个 region 地图数据。
 *
 * @param requestId 请求 ID
 * @param chunk 地图 region 数据
 * @param relativePath 相对地图缓存路径
 * @param observedServerTimestampSeconds 客户端发包时观察到的服务端时间戳（秒）
 * @param observedServerHash 客户端发包时观察到的服务端哈希
 */
public record ContributionDataPayload(
        int requestId,
        ChunkMapData chunk,
        String relativePath,
        long observedServerTimestampSeconds,
        String observedServerHash
) {
    public static final String ID = NetworkHandler.CONTRIBUTION_DATA_ID;
}
