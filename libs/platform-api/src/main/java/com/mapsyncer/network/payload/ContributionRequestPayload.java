package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

import java.util.List;
import java.util.Objects;

/**
 * 贡献请求包 - 服务端请求客户端上传更新的 region 数据。
 *
 * @param requestId 请求 ID
 * @param regions 请求贡献的 region 元数据列表
 * @param status 请求状态
 */
public record ContributionRequestPayload(int requestId, List<ContributionRegionMeta> regions, String status) {
    public static final String ID = NetworkHandler.CONTRIBUTION_REQUEST_ID;

    public ContributionRequestPayload {
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        status = Objects.requireNonNull(status, "status");
    }
}
