package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.ServerSyncHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    public MapSyncer(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(this::commonSetup);

        modContainer.registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Client-side initialization
            modBus.addListener(this::onRegisterKeyMappings);
            modBus.addListener(MapPacketReceiver::register);
            LOGGER.info("MapSyncer initialized (client mode)");
        } else {
            // Server-side initialization
            modBus.addListener(ServerSyncHandler::register);
            NeoForge.EVENT_BUS.register(this);
            LOGGER.info("MapSyncer initialized (server mode)");
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PacketHandler::init);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(event.getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        IncrementalUpdateHandler.getInstance().stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CacheGenerateCommand.register(event.getDispatcher());
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
    }
}
