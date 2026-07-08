package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

/**
 * 贡献结果包 - 服务端返回贡献处理结果。
 *
 * @param requestId 请求 ID
 * @param accepted 接受的 region 数量
 * @param rejected 拒绝的 region 数量
 * @param status 结果状态
 * @param terminal 是否为该请求/会话的终态结果
 */
public record ContributionResultPayload(int requestId, int accepted, int rejected, String status, boolean terminal) {
    public static final String ID = NetworkHandler.CONTRIBUTION_RESULT_ID;

    public ContributionResultPayload(int requestId, int accepted, int rejected, String status) {
        this(requestId, accepted, rejected, status, false);
    }
}
