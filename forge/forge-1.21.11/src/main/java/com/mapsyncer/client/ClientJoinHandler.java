package com.mapsyncer.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端玩家加入事件处理器 - Forge 事件注册包装器
 *
 * 核心逻辑委托给 {@link SyncResumeHelper}。
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
public class ClientJoinHandler {

    @SubscribeEvent
    public static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        SyncResumeHelper.onPlayerLoggingIn();
    }

    public static void clearSyncState() {
        SyncResumeHelper.clearSyncState();
    }
}
