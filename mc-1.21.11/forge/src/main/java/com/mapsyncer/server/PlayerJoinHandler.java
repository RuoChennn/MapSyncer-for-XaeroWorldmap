package com.mapsyncer.server;

import com.mapsyncer.network.impl.ForgeNetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;

@EventBusSubscriber(value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, bus = EventBusSubscriber.Bus.FORGE)
public class PlayerJoinHandler {

    private static MinecraftServer currentServer;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        currentServer = event.getServer();
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        PlayerJoinHandlerLogic.onPlayerJoin(player, player.level().getServer());
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerJoinHandlerLogic.onPlayerLeave(event.getEntity().getUUID());
        ForgeNetworkHandler.onPlayerDisconnect(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        currentServer = null;
        PlayerJoinHandlerLogic.onServerStopped();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        if (currentServer != null) {
            PlayerJoinHandlerLogic.onServerTick(currentServer);
        }
    }
}
