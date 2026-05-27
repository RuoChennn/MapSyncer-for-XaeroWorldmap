package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.ForgePlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.ServerSyncHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer模组的主类 - Forge 1.21 版本
 *
 * Forge 1.21 使用 ChannelBuilder API 进行网络注册，
 * 网络层在 ForgeNetworkHandler 的静态初始化中完成注册。
 */
@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    public MapSyncer(IEventBus modBus, ModContainer modContainer) {
        VERSION = modContainer.getModInfo().getVersion().toString();

        // 初始化 Platform（Forge 1.21 实现）
        PlatformManager.initialize(new ForgePlatform());
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        // 初始化 NetworkManager（Forge 1.21 实现）
        // 网络注册在 ForgeNetworkHandler 的静态初始化中完成
        NetworkManager.initialize(new ForgeNetworkHandler());
        LOGGER.info("NetworkManager initialized for Forge 1.21");

        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MapPacketReceiver.register();
            MinecraftForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode, Forge 1.21)");
        } else {
            ServerSyncHandler.register();
            MinecraftForge.EVENT_BUS.register(this);
            LOGGER.info("MapSyncer initialized (server mode, Forge 1.21)");
        }
    }

    @EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
    public static class ClientEventHandler {
        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            MapPacketReceiver.resetServerStatus();
            MapPacketReceiver.clearSyncData();
            LOGGER.info("Client disconnected from server, reset server status");
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        DimensionRegistry.registerAllDimensions(event.getServer());

        Platform platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
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
}