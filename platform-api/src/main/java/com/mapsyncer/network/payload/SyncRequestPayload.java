package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

import java.util.Map;

/**
 * 同步请求包 - 平台无关版本
 *
 * 客户端发送各region的元数据（时间戳+哈希）到服务端，
 * 服务端据此判断哪些数据需要同步。
 *
 * @param clientMeta 客户端元数据映射，键为region路径，值为时间戳和哈希值
 */
public record SyncRequestPayload(Map<String, ClientMeta> clientMeta) {
    public static final String ID = NetworkHandler.SYNC_REQUEST_ID;
}