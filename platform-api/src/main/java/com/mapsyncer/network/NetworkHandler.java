package com.mapsyncer.network;

import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;

import java.util.function.BiConsumer;

/**
 * 网络处理器抽象接口
 *
 * <p>定义跨平台网络操作的抽象接口，各平台（NeoForge、Forge、Fabric）需要实现此接口。</p>
 *
 * <p>类型安全设计：</p>
 * <ul>
 *   <li>使用 {@code PLAYER_TYPE} 泛型参数支持平台特定的玩家类型</li>
 *   <li>使用 {@code EVENT_TYPE} 泛型参数支持平台特定的注册事件类型</li>
 *   <li>Payload DTO 是平台无关的纯 record</li>
 * </ul>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>各平台实现需要提供类型转换验证</li>
 *   <li>序列化逻辑由各平台实现处理</li>
 * </ul>
 *
 * @param <PLAYER_TYPE> 平台特定的玩家类型（如 ServerPlayer）
 * @param <EVENT_TYPE> 平台特定的注册事件类型
 */
public interface NetworkHandler<PLAYER_TYPE, EVENT_TYPE> {

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
     * <p>在模组初始化时调用，平台实现将事件转换为平台特定类型并注册处理器。</p>
     *
     * @param event 平台特定的注册事件（RegisterPayloadHandlersEvent）
     */
    void registerHandlers(EVENT_TYPE event);

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
     * @param player 服务端玩家对象（平台特定类型）
     * @param payload 同步响应包
     */
    void sendToPlayer(PLAYER_TYPE player, SyncResponsePayload payload);

    /**
     * 发送同步进度到指定玩家（服务端调用）
     *
     * @param player 服务端玩家对象
     * @param payload 同步进度包
     */
    void sendToPlayer(PLAYER_TYPE player, SyncProgressPayload payload);

    /**
     * 发送服务端已安装通知到指定玩家（服务端调用）
     *
     * @param player 服务端玩家对象
     * @param payload 服务端已安装通知包
     */
    void sendToPlayer(PLAYER_TYPE player, ServerInstalledPayload payload);

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
     * @return 服务端玩家对象（类型安全）
     */
    PLAYER_TYPE getPlayerFromContext(PayloadContext context);
}