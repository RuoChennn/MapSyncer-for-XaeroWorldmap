package com.mapsyncer.server;

import com.mapsyncer.network.impl.ForgeNetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;

@EventBusSubscriber(value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, bus = EventBusSubscriber.Bus.FORGE)
public class PlayerJoinHandler {

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.getServer();
        PlayerJoinHandlerLogic.onPlayerJoin(player, server);
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerJoinHandlerLogic.onPlayerLeave(event.getEntity().getUUID());
        ForgeNetworkHandler.onPlayerDisconnect(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PlayerJoinHandlerLogic.onServerStopped();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PlayerJoinHandlerLogic.onServerTick(event.getServer());
    }
}
