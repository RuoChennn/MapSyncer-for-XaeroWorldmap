package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.GAME)
public class PlayerJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandler.class);

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.getServer();
        if (server == null) return;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(server);
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        // Abort any ongoing sync for this player
        ServerSyncHandler.onPlayerDisconnect(event.getEntity().getUUID());
    }

    /**
     * Clean up singleton instances when server stops to prevent memory leaks.
     * This is important for dedicated servers that may restart without JVM restart.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        // Reset singleton instances to release memory
        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
        IncrementalUpdateHandler.resetInstance();

        // Clear sync tracking data
        ServerSyncHandler.cleanup();

        LOGGER.info("Singleton cache cleanup completed");
    }
}
