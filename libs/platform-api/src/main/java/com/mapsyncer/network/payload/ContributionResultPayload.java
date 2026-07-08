package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

/**
 * 贡献结果包 - 服务端返回贡献处理结果。
 *
 * @param requestId 请求 ID
 * @param accepted 接受的 region 数量
 * @param rejected 拒绝的 region 数量
 * @param status 结果状态
 */
public record ContributionResultPayload(int requestId, int accepted, int rejected, String status) {
    public static final String ID = NetworkHandler.CONTRIBUTION_RESULT_ID;
}
