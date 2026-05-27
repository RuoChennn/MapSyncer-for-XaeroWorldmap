package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.SyncProgressTracker;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.NeoForgeNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.NeoForgePlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.ServerSyncHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer模组的主类 - NeoForge 1.21.1 版本
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

        // 初始化 Platform（NeoForge 1.21 实现）
        PlatformManager.initialize(new NeoForgePlatform());
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        // 初始化 NetworkManager（NeoForge 网络实现）
        networkHandler = new NeoForgeNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized");

        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 客户端初始化：注册网络包接收器
            modBus.addListener(MapPacketReceiver::register);
            NeoForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode)");
        } else {
            // 服务端初始化：注册网络包处理器
            modBus.addListener(ServerSyncHandler::register);
            NeoForge.EVENT_BUS.register(this);
            LOGGER.info("MapSyncer initialized (server mode)");
        }
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
    @EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
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
            // 断开连接时不改变同步状态（保持 in_progress），下次加入时可断点续传
            MapPacketReceiver.resetServerStatus();
            MapPacketReceiver.clearSyncData();
            // 关闭进度追踪器的线程池，释放资源
            SyncProgressTracker.shutdown();
            LOGGER.info("Client disconnected from server, reset server status and cleaned up resources");
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
        // 注册所有已加载维度到配置文件
        DimensionRegistry.registerAllDimensions(event.getServer());

        // 启动增量更新（如果已配置）
        Platform platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(event.getServer());
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
        IncrementalUpdateHandler.getInstance().stop();
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
        CacheGenerateCommand.register(event.getDispatcher());
    }
}