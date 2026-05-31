package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric版本的事件注册在MapSyncer主类中使用ServerPlayConnectionEvents。
 * 此类提供静态方法供主类调用。
 */
public class PlayerJoinHandler {

    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        PlayerJoinHandlerLogic.onPlayerJoin(player, server);
    }

    public static void onPlayerLeave(java.util.UUID playerId) {
        PlayerJoinHandlerLogic.onPlayerLeave(playerId);
    }

    public static void onServerStopped() {
        PlayerJoinHandlerLogic.onServerStopped();
    }

    public static void onServerTick(MinecraftServer server) {
        PlayerJoinHandlerLogic.onServerTick(server);
    }
}
