package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.SyncProgressTracker;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.ForgeLegacyPlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandlerLogic;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer模组的主类 - Forge 1.20.1 版本
 */
@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    /** Forge 网络处理器实例 */
    private static ForgeNetworkHandler networkHandler;

    public MapSyncer(IEventBus modBus, ModContainer modContainer) {
        VERSION = modContainer.getModInfo().getVersion().toString();

        // 初始化 Platform（Forge 1.20.1 实现）
        PlatformManager.initialize(new ForgeLegacyPlatform());
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        // 初始化 NetworkManager（Forge 网络实现）
        networkHandler = new ForgeNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized");

        // 注册配置文件（Forge 1.20.1 使用 ModLoadingContext）
        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 客户端：注册网络处理器
            networkHandler.registerHandlers(null);
            MinecraftForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode, Forge 1.20)");
        } else {
            // 服务端：注册网络处理器
            networkHandler.registerHandlers(null);
            ServerSyncHandlerLogic.registerHandlers();
            MinecraftForge.EVENT_BUS.register(this);
            LOGGER.info("MapSyncer initialized (server mode, Forge 1.20)");
        }
    }

    /**
     * 获取网络处理器实例
     */
    public static ForgeNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(value = Dist.CLIENT, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE)
    public static class ClientEventHandler {
        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            MapPacketReceiver.resetServerStatus();
            MapPacketReceiver.clearSyncData();
            SyncProgressTracker.shutdown();
            LOGGER.info("Client disconnected from server, reset server status and cleaned up resources");
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        DimensionRegistry.registerAllDimensions(event.getServer());

        Platform platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(event.getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CacheGenerateCommand.register(event.getDispatcher());
    }
}