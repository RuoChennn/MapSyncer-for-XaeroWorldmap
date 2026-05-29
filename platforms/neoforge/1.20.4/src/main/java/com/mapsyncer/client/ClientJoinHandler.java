package com.mapsyncer.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端玩家加入事件处理器 - NeoForge 事件注册包装器
 *
 * 核心逻辑委托给 {@link SyncResumeHelper}。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientJoinHandler {

    @SubscribeEvent
    public static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        SyncResumeHelper.onPlayerLoggingIn();
    }

    public static void clearSyncState() {
        SyncResumeHelper.clearSyncState();
    }
}
