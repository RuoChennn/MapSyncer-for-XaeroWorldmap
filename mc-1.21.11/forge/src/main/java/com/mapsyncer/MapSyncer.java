package com.mapsyncer;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.ForgePlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandlerLogic;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer模组的主类 - FML 3.0 / eventbus 7.0 版本
 *
 * FML 3.0 / eventbus 7.0 使用 ChannelBuilder API 进行网络注册，
 * 网络层在 ForgeNetworkHandler 的静态初始化中完成注册。
 */
@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    public MapSyncer(EventBus modBus, ModContainer modContainer) {
        VERSION = modContainer.getModInfo().getVersion().toString();

        // 初始化 Platform（FML 3.0 / eventbus 7.0 实现）
        PlatformManager.initialize(new ForgePlatform());
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        // 初始化 DimensionPathMapping（1.21.X 使用传统格式）
        DimensionPathMapping.getInstance().initialize(21);
        LOGGER.info("DimensionPathMapping initialized for version 1.21.X");

        // 注册配置（FML 3.0 / eventbus 7.0 仍使用 ModLoadingContext）
        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
        modBus.addListener(event -> {
            if (event instanceof ModConfigEvent.Loading loading) {
                ModConfig.bindServerConfig(loading.getConfig());
            }
        });

        // 创建网络处理器实例
        ForgeNetworkHandler networkHandler = new ForgeNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized for FML 3.0 / eventbus 7.0");

        // 注册网络处理器（客户端和服务端共用）
        networkHandler.registerHandlers(null);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MapPacketReceiver.register(null);
            MinecraftForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode, FML 3.0 / eventbus 7.0)");
        }

        // 服务端处理器始终注册，内置服务器/纯客户端上无副作用（事件不触发）
        ServerSyncHandlerLogic.registerHandlers();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("MapSyncer server handlers registered (integrated server support)");
    }

    @EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
    public static class ClientEventHandler {
        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            MapPacketReceiver.onDisconnect();
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            MapPacketHandler.drainPendingLoadQueue();
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ConversionOrchestrator.tryInitIntegratedServerCache(event.getServer(), FMLPaths.GAMEDIR.get());

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
        CacheGenerateCommand.register(event.getDispatcher(), "mapsyncer");
    }
}