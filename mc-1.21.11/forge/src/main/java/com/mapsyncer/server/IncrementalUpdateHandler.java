package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;

@Mod.EventBusSubscriber(modid = "mapsyncer", value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class IncrementalUpdateHandler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        IncrementalUpdateHandlerLogic.getInstance().onServerTick();
    }

    public static void start(MinecraftServer server) {
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static void stop() {
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    public static boolean isRunning() {
        return IncrementalUpdateHandlerLogic.getInstance().isRunning();
    }

    public static int getTickCounter() {
        return IncrementalUpdateHandlerLogic.getInstance().getTickCounter();
    }

    public static String getStatusInfo() {
        return IncrementalUpdateHandlerLogic.getInstance().getStatusInfo();
    }

    public static void resetInstance() {
        IncrementalUpdateHandlerLogic.resetInstance();
    }
}
