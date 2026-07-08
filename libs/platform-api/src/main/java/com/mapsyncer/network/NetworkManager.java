package com.mapsyncer.network;

import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;

import java.util.function.BiConsumer;

/**
 * 网络管理器 - 单例模式
 *
 * <p>管理全局 NetworkHandler 实例，提供便捷的静态方法。</p>
 * <p>遵循 PlatformManager 的设计模式。</p>
 *
 * <p>类型安全设计：使用原始类型存储 handler，便捷方法接收 Object 类型参数，
 * 具体实现类负责类型转换验证。</p>
 *
 * <p>使用方式：</p>
 * <ol>
 *   <li>在模组初始化时调用 initialize()</li>
 *   <li>业务代码通过静态方法发送包或注册处理器</li>
 * </ol>
 */
public final class NetworkManager {

    /** 网络处理器实例（使用原始类型，具体实现由平台提供） */
    private static volatile NetworkHandler<?, ?> instance;

    private NetworkManager() {}

    /**
     * 初始化网络处理器
     *
     * @param handler 平台特定的 NetworkHandler 实现
     */
    public static void initialize(NetworkHandler<?, ?> handler) {
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
    public static NetworkHandler<?, ?> getHandler() {
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
     * 发送贡献数据到服务端
     */
    public static void sendToServer(ContributionDataPayload payload) {
        getHandler().sendToServer(payload);
    }

    /**
     * 发送贡献完成通知到服务端
     */
    public static void sendToServer(ContributionCompletePayload payload) {
        getHandler().sendToServer(payload);
    }

    /**
     * 发送同步响应到玩家
     *
     * <p>类型安全由具体实现类保证</p>
     *
     * @param player 玩家对象（平台特定类型）
     * @param payload 同步响应包
     */
    public static void sendToPlayer(Object player, SyncResponsePayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    /**
     * 发送同步进度到玩家
     *
     * @param player 玩家对象
     * @param payload 同步进度包
     */
    public static void sendToPlayer(Object player, SyncProgressPayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    /**
     * 发送服务端已安装通知到玩家
     *
     * @param player 玩家对象
     * @param payload 服务端已安装通知包
     */
    public static void sendToPlayer(Object player, ServerInstalledPayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    /**
     * 发送贡献请求到玩家
     *
     * @param player 玩家对象
     * @param payload 贡献请求包
     */
    public static void sendToPlayer(Object player, ContributionRequestPayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    /**
     * 发送贡献结果到玩家
     *
     * @param player 玩家对象
     * @param payload 贡献结果包
     */
    public static void sendToPlayer(Object player, ContributionResultPayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    // ===== 便捷注册方法 =====

    /**
     * 注册贡献请求处理器
     *
     * @param handler 处理函数
     */
    public static void registerContributionRequestHandler(
            BiConsumer<ContributionRequestPayload, PayloadContext> handler
    ) {
        getHandler().registerContributionRequestHandler(handler);
    }

    /**
     * 注册贡献数据处理器
     *
     * @param handler 处理函数
     */
    public static void registerContributionDataHandler(BiConsumer<ContributionDataPayload, PayloadContext> handler) {
        getHandler().registerContributionDataHandler(handler);
    }

    /**
     * 注册贡献完成处理器
     *
     * @param handler 处理函数
     */
    public static void registerContributionCompleteHandler(
            BiConsumer<ContributionCompletePayload, PayloadContext> handler
    ) {
        getHandler().registerContributionCompleteHandler(handler);
    }

    /**
     * 注册贡献结果处理器
     *
     * @param handler 处理函数
     */
    public static void registerContributionResultHandler(BiConsumer<ContributionResultPayload, PayloadContext> handler) {
        getHandler().registerContributionResultHandler(handler);
    }

    /**
     * 注册网络处理器
     *
     * @param event 平台特定的注册事件
     */
    public static void registerHandlers(Object event) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.registerHandlers(event);
    }
}
