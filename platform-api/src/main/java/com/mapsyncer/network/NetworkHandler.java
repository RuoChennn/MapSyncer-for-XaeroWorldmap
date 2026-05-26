package com.mapsyncer.network;

import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;

import java.util.function.BiConsumer;

/**
 * 网络处理器抽象接口
 *
 * 定义跨平台网络操作的抽象接口，各平台（NeoForge、Forge）需要实现此接口。
 * 遵循 Platform 抽象层的设计模式，让业务代码不关心具体平台差异。
 *
 * 设计要点：
 * - 使用 Object 作为事件参数类型，避免平台特定类型
 * - Payload DTO 是平台无关的纯 record
 * - 序列化逻辑由各平台实现处理
 */
public interface NetworkHandler {

    // ===== 网络资源标识符 =====

    /** 同步请求包 ID */
    String SYNC_REQUEST_ID = "sync_request";
    /** 同步响应包 ID */
    String SYNC_RESPONSE_ID = "sync_response";
    /** 同步进度包 ID */
    String SYNC_PROGRESS_ID = "sync_progress";
    /** 服务端已安装通知包 ID */
    String SERVER_INSTALLED_ID = "server_installed";

    // ===== 初始化 =====

    /**
     * 注册网络包处理器
     *
     * 在模组初始化时调用，平台实现将事件转换为平台特定类型并注册处理器。
     *
     * @param event 平台特定的注册事件（RegisterPayloadHandlersEvent）
     */
    void registerHandlers(Object event);

    // ===== 发送方法 =====

    /**
     * 发送同步请求到服务端（客户端调用）
     *
     * @param payload 同步请求包
     */
    void sendToServer(SyncRequestPayload payload);

    /**
     * 发送同步响应到指定玩家（服务端调用）
     *
     * @param player 服务端玩家对象（平台特定）
     * @param payload 同步响应包
     */
    void sendToPlayer(Object player, SyncResponsePayload payload);

    /**
     * 发送同步进度到指定玩家（服务端调用）
     *
     * @param player 服务端玩家对象
     * @param payload 同步进度包
     */
    void sendToPlayer(Object player, SyncProgressPayload payload);

    /**
     * 发送服务端已安装通知到指定玩家（服务端调用）
     *
     * @param player 服务端玩家对象
     * @param payload 服务端已安装通知包
     */
    void sendToPlayer(Object player, ServerInstalledPayload payload);

    // ===== 处理器注册 =====

    /**
     * 注册同步响应处理器（客户端）
     *
     * @param handler 处理函数，接收 Payload 和 Context
     */
    void registerSyncResponseHandler(BiConsumer<SyncResponsePayload, PayloadContext> handler);

    /**
     * 注册同步进度处理器（客户端）
     *
     * @param handler 处理函数
     */
    void registerSyncProgressHandler(BiConsumer<SyncProgressPayload, PayloadContext> handler);

    /**
     * 注册服务端已安装处理器（客户端）
     *
     * @param handler 处理函数
     */
    void registerServerInstalledHandler(BiConsumer<ServerInstalledPayload, PayloadContext> handler);

    /**
     * 注册同步请求处理器（服务端）
     *
     * @param handler 处理函数
     */
    void registerSyncRequestHandler(BiConsumer<SyncRequestPayload, PayloadContext> handler);

    // ===== 上下文操作 =====

    /**
     * 在主线程执行任务
     *
     * @param context 平台特定的 PayloadContext
     * @param work 要执行的任务
     */
    void enqueueWork(PayloadContext context, Runnable work);

    /**
     * 从上下文获取服务端玩家对象
     *
     * @param context 平台特定的 PayloadContext
     * @return 服务端玩家对象（ServerPlayer）
     */
    Object getPlayerFromContext(PayloadContext context);
}