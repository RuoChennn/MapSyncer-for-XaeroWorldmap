package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.impl.NeoForgeNetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, modid = "mapsyncer")
public class PlayerJoinHandler {

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.level().getServer();
        PlayerJoinHandlerLogic.onPlayerJoin(player, server);
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerJoinHandlerLogic.onPlayerLeave(event.getEntity().getUUID());
        NeoForgeNetworkHandler handler = MapSyncer.getNetworkHandler();
        if (handler != null) {
            handler.onPlayerDisconnect(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PlayerJoinHandlerLogic.onServerStopped();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PlayerJoinHandlerLogic.onServerTick(event.getServer());
    }
}
