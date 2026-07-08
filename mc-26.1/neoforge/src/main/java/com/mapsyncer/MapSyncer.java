package com.mapsyncer;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.NeoForgeNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.NeoForge26Platform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandler;
import com.mapsyncer.util.DimensionPathMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer模组的主类 - NeoForge 26.x 版本
 *
 * 使用抽象网络层进行跨平台网络通信。
 */
@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    /** NeoForge 网络处理器实例（用于发送包） */
    private static NeoForgeNetworkHandler networkHandler;

    public MapSyncer(IEventBus modBus, ModContainer modContainer) {
        VERSION = modContainer.getModInfo().getVersion().toString();

        // 初始化 Platform（NeoForge 26.x 实现）
        PlatformManager.initialize(new NeoForge26Platform());
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        // 初始化 DimensionPathMapping（26.1+ 使用新格式）
        DimensionPathMapping.getInstance().initialize(26);
        LOGGER.info("DimensionPathMapping initialized for version 26+");

        // 初始化 NetworkManager（NeoForge 网络实现）
        networkHandler = new NeoForgeNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized");

        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Loading event) ->
                ModConfig.bindServerConfig(event.getConfig()));

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modBus.addListener(MapPacketReceiver::register);
            NeoForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode)");
        }

        // 服务端处理器始终注册，内置服务器/纯客户端上无副作用（事件不触发）
        modBus.addListener(ServerSyncHandler::register);
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("MapSyncer server handlers registered (integrated server support)");
    }

    /**
     * 获取网络处理器实例（用于发送包）
     */
    public static NeoForgeNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    /**
     * 客户端事件处理器 - 处理客户端玩家断开连接事件
     */
    @EventBusSubscriber(value = Dist.CLIENT, modid = "mapsyncer")
    public static class ClientEventHandler {
        /**
         * 玩家断开连接事件处理
         *
         * 重置服务端安装状态和同步数据，清理线程池资源
         *
         * @param event 玩家断开连接事件
         */
        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            MapPacketReceiver.onDisconnect();
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            MapPacketHandler.drainPendingLoadQueue();
        }
    }

    /**
     * 服务端启动事件处理。
     *
     * 在服务端启动时执行以下操作：
     * 1. 注册所有已加载维度到配置文件
     * 2. 根据配置启动增量更新处理器
     *
     * @param event 服务端启动事件
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ConversionOrchestrator.tryInitIntegratedServerCache(event.getServer(), FMLPaths.GAMEDIR.get());

        // 注册所有已加载维度到配置文件
        DimensionRegistry.registerAllDimensions(event.getServer());

        // 启动增量更新（如果已配置）
        Platform platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(event.getServer());
        }
    }

    /**
     * 服务端停止事件处理。
     *
     * 在服务端停止时停止增量更新处理器，释放相关资源。
     *
     * @param event 服务端停止事件
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        NeoForge.EVENT_BUS.unregister(this);
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    /**
     * 命令注册事件处理。
     *
     * 注册服务端的缓存生成命令（/mapsyncer）。
     *
     * @param event 命令注册事件
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CacheGenerateCommand.register(event.getDispatcher(), "mapsyncer");
    }
}