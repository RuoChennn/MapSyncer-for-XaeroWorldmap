package com.mapsyncer.server;

/**
 * NeoForge 平台薄包装器。
 * 所有业务逻辑已提取到 {@link ServerSyncHandlerLogic}（minecraft-common）。
 */
public class ServerSyncHandler {

    /**
     * 注册网络数据包处理器。
     * NeoForge 通过 modBus.addListener(ServerSyncHandler::register) 调用。
     *
     * @param event RegisterPayloadHandlersEvent（未使用）
     */
    public static void register(final Object event) {
        ServerSyncHandlerLogic.registerHandlers();
    }
}