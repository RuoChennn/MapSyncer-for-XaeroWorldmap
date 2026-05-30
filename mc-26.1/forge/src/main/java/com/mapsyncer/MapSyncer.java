package com.mapsyncer;

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
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandler;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * MapSyncer模组的主类 - Forge 1.20.4 版本
 *
 * 使用 Forge 49.x API，Java 17 环境。
 */
@Mod("mapsyncer")
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "1.0.1-forge-1.20.4";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    /** Forge 网络处理器实例 */
    private static ForgeNetworkHandler networkHandler;

    /** Forge 平台实例 */
    private static ForgePlatform platform;

    public MapSyncer(FMLJavaModLoadingContext context) {
        // 初始化 Platform（Forge 实现）
        platform = new ForgePlatform();
        PlatformManager.initialize(platform);
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        // 初始化 DimensionPathMapping（26.1+ 使用新格式）
        DimensionPathMapping.getInstance().initialize(26);
        LOGGER.info("DimensionPathMapping initialized for version 26+");

        // 注册配置文件
        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);

        // 初始化 NetworkManager（Forge 网络实现）
        networkHandler = new ForgeNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized");

        // 注册设置事件
        context.getModEventBus().register(this);

        // 注册命令
        MinecraftForge.EVENT_BUS.register(this);

        // 注册服务端网络接收器
        ServerSyncHandler.register();

        // 客户端/服务端分离初始化
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            MinecraftForge.EVENT_BUS.register(com.mapsyncer.client.ClientJoinHandler.class);
            com.mapsyncer.client.MapPacketReceiver.register();
        });

        LOGGER.info("MapSyncer initialized (Forge 1.20.4), version: {}", VERSION);
    }

    @SubscribeEvent
    public void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            LOGGER.info("Common setup completed");
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CacheGenerateCommand.register(event.getDispatcher(), "mapsyncer");
    }

    /**
     * 服务端启动事件
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        LOGGER.info("Server started, initializing MapSyncer...");

        // 初始化配置
        Path worldPath = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        Path serverConfigDir = worldPath.resolve("serverconfig");
        ModConfig.getServerConfig(serverConfigDir);

        // 注册所有维度
        DimensionRegistry.registerAllDimensions(server);

        // 启动增量更新处理器
        Platform platformImpl = PlatformManager.getPlatform();
        UpdateMode mode = platformImpl.getIncrementalUpdateMode();
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(server);
            LOGGER.info("Incremental update handler started with mode: {}", mode);
        }
    }

    /**
     * 服务端停止事件
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Server stopping, cleaning up MapSyncer...");

        // 停止增量更新处理器
        IncrementalUpdateHandlerLogic.getInstance().stop();

        // 关闭转换线程池
        com.mapsyncer.server.ConversionOrchestrator.shutdownExecutor();

        // 清理缓存
        com.mapsyncer.server.GenerationCache.resetInstance();
        com.mapsyncer.server.McaTimestampCache.resetInstance();

        LOGGER.info("MapSyncer cleanup completed");
    }

    /**
     * 获取网络处理器实例
     */
    public static ForgeNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    /**
     * 获取平台实例
     */
    public static ForgePlatform getPlatform() {
        return platform;
    }
}