package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

/**
 * 服务端已安装通知包 - 平台无关版本
 *
 * 服务端在玩家加入时发送，告知客户端服务端已安装 MapSyncer。
 *
 * @param version 服务端模组版本号
 */
public record ServerInstalledPayload(String version, long lastGenerationTimestamp, int autoSyncIntervalMinutes) {
    public static final String ID = NetworkHandler.SERVER_INSTALLED_ID;
}