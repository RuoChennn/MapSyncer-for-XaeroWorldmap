package com.mapsyncer.server;

/**
 * Forge 平台薄包装器。
 * 所有业务逻辑已提取到 {@link ServerSyncHandlerLogic}（minecraft-common）。
 */
public class ServerSyncHandler {

    /**
     * 注册网络数据包处理器。
     *
     * @param event 事件对象（未使用）
     */
    public static void register(final Object event) {
        ServerSyncHandlerLogic.registerHandlers();
    }

    /**
     * 注册网络数据包处理器（无参数版本）。
     */
    public static void register() {
        ServerSyncHandlerLogic.registerHandlers();
    }
}
