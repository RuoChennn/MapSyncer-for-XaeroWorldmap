package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.network.PacketHandler;
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
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer模组的主类。
 *
 * 该模组用于将服务端的Xaero's World Map地图数据同步到客户端，
 * 支持增量更新和全量同步两种模式。通过解析服务端的MCA文件，
 * 转换为Xaero地图格式并传输到客户端进行渲染。
 *
 * 主要功能：
 * - 服务端：MCA文件解析、地图缓存生成、增量更新处理
 * - 客户端：地图数据接收、缓存合并、进度显示
 *
 * @author MapSyncer Team
 * @see PacketHandler 网络包处理器
 * @see ServerSyncHandler 服务端同步处理器
 * @see MapPacketReceiver 客户端数据包接收器
 */
@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    /**
     * 模组ID，用于标识本模组。
     */
    public static final String MOD_ID = "mapsyncer";

    /**
     * 模组版本号，从 modContainer 获取。
     */
    public static String VERSION = "unknown";

    /**
     * 日志记录器，用于输出模组运行日志。
     */
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    /**
     * 模组主类构造器。
     *
     * 根据运行环境（客户端/服务端）初始化不同的组件：
     * - 客户端模式：注册网络包接收器
     * - 服务端模式：注册网络包处理器和事件监听器
     *
     * @param modBus 模组事件总线，用于注册模组生命周期事件
     * @param modContainer 模组容器，用于注册配置文件
     */
    public MapSyncer(IEventBus modBus, ModContainer modContainer) {
        VERSION = modContainer.getModInfo().getVersion().toString();
        modBus.addListener(this::commonSetup);

        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 客户端初始化：注册网络包接收器和事件监听器
            modBus.addListener(MapPacketReceiver::register);
            NeoForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode)");
        } else {
            // 服务端初始化：注册网络包处理器和事件监听器
            modBus.addListener(ServerSyncHandler::register);
            NeoForge.EVENT_BUS.register(this);
            LOGGER.info("MapSyncer initialized (server mode)");
        }
    }

    /**
     * 客户端事件处理器 - 处理客户端玩家断开连接事件
     */
    @EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static class ClientEventHandler {
        /**
         * 玩家断开连接事件处理
         *
         * 重置服务端安装状态和同步数据
         *
         * @param event 玩家断开连接事件
         */
        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            MapPacketReceiver.resetServerStatus();
            MapPacketReceiver.clearSyncData();
            LOGGER.info("Client disconnected from server, reset server status");
        }
    }

    /**
     * 公共初始化事件处理。
     *
     * 在模组加载的公共设置阶段初始化网络包处理器。
     * 该方法在主线程上异步执行，确保网络注册的正确顺序。
     *
     * @param event 公共设置事件
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PacketHandler::init);
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
        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
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