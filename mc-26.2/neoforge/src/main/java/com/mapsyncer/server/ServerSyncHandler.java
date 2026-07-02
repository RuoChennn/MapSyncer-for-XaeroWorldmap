package com.mapsyncer.server;

import com.mapsyncer.network.NetworkManager;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge 平台薄包装器。
 * 所有业务逻辑已提取到 {@link ServerSyncHandlerLogic}（minecraft-common）。
 */
public class ServerSyncHandler {

    /**
     * 注册网络数据包处理器。
     * NeoForge 通过 modBus.addListener(ServerSyncHandler::register) 调用。
     *
     * @param event RegisterPayloadHandlersEvent
     */
    public static void register(final RegisterPayloadHandlersEvent event) {
        NetworkManager.registerHandlers(event);
        ServerSyncHandlerLogic.registerHandlers();
    }
}
