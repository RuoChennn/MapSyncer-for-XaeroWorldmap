package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

/**
 * 贡献完成包 - 客户端声明本次贡献发送结束。
 *
 * @param requestId 请求 ID
 * @param sentRegions 已发送 region 数量
 * @param status 完成状态
 */
public record ContributionCompletePayload(int requestId, int sentRegions, String status) {
    public static final String ID = NetworkHandler.CONTRIBUTION_COMPLETE_ID;
}
