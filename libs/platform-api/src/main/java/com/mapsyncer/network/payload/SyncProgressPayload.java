package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

/**
 * 同步进度包 - 平台无关版本
 *
 * 服务端发送同步进度通知给客户端，用于显示进度条。
 *
 * @param processed 已处理的region数量
 * @param total 总region数量
 * @param status 状态描述文本
 */
public record SyncProgressPayload(int processed, int total, String status) {
    public static final String ID = NetworkHandler.SYNC_PROGRESS_ID;
}