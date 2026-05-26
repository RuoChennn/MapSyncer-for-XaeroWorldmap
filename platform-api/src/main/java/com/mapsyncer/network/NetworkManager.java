package com.mapsyncer.network;

import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;

/**
 * 网络管理器 - 单例模式
 *
 * 管理全局 NetworkHandler 实例，提供便捷的静态方法。
 * 遵循 PlatformManager 的设计模式。
 *
 * 使用方式：
 * 1. 在模组初始化时调用 initialize()
 * 2. 业务代码通过静态方法发送包或注册处理器
 */
public final class NetworkManager {

    private static volatile NetworkHandler instance;

    private NetworkManager() {}

    /**
     * 初始化网络处理器
     *
     * @param handler 平台特定的 NetworkHandler 实现
     */
    public static void initialize(NetworkHandler handler) {
        if (instance != null) {
            throw new IllegalStateException("NetworkHandler already initialized");
        }
        instance = handler;
    }

    /**
     * 获取网络处理器实例
     *
     * @return NetworkHandler 实例
     */
    public static NetworkHandler getHandler() {
        if (instance == null) {
            throw new IllegalStateException("NetworkHandler not initialized");
        }
        return instance;
    }

    /**
     * 检查是否已初始化
     *
     * @return true 表示已初始化
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    // ===== 便捷发送方法 =====

    /**
     * 发送同步请求到服务端
     */
    public static void sendToServer(SyncRequestPayload payload) {
        getHandler().sendToServer(payload);
    }

    /**
     * 发送同步响应到玩家
     */
    public static void sendToPlayer(Object player, SyncResponsePayload payload) {
        getHandler().sendToPlayer(player, payload);
    }

    /**
     * 发送同步进度到玩家
     */
    public static void sendToPlayer(Object player, SyncProgressPayload payload) {
        getHandler().sendToPlayer(player, payload);
    }

    /**
     * 发送服务端已安装通知到玩家
     */
    public static void sendToPlayer(Object player, ServerInstalledPayload payload) {
        getHandler().sendToPlayer(player, payload);
    }

    // ===== 便捷注册方法 =====

    /**
     * 注册网络处理器
     */
    public static void registerHandlers(Object event) {
        getHandler().registerHandlers(event);
    }
}