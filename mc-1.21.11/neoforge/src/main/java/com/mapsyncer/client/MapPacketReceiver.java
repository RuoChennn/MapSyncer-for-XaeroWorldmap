package com.mapsyncer.client;

import com.mapsyncer.network.NetworkManager;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 地图数据包接收器 - NeoForge 平台包装器
 *
 * 核心逻辑委托给 {@link MapPacketHandler}。
 */
public class MapPacketReceiver {

    public static void register(final RegisterPayloadHandlersEvent event) {
        NetworkManager.registerHandlers(event);
        MapPacketHandler.registerHandlers();
    }

    public static boolean isSyncInProgress() { return MapPacketHandler.isSyncInProgress(); }
    public static boolean isServerInstalled() { return MapPacketHandler.isServerInstalled(); }
    public static void resetServerStatus() { MapPacketHandler.resetServerStatus(); }
    public static void clearSyncData() { MapPacketHandler.clearSyncData(); }
    public static void clearReceivedChunks() { MapPacketHandler.clearReceivedChunks(); }
    public static boolean isSyncStale() { return MapPacketHandler.isSyncStale(); }
    public static void prepareSyncForDimension(String targetDimension) { MapPacketHandler.prepareSyncForDimension(targetDimension); }
    public static void onDisconnect() { MapPacketHandler.onDisconnect(); }
}
