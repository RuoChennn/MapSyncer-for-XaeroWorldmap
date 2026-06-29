package com.mapsyncer;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.FabricNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.FabricPlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * MapSyncer模组的主类 - Fabric 1.21.1 版本
 *
 * 实现 FabricModInitializer 接口，在模组初始化时设置平台和网络处理器。
 */
public class MapSyncer implements ModInitializer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    /** Fabric 网络处理器实例 */
    private static FabricNetworkHandler networkHandler;

    /** Fabric 平台实例 */
    private static FabricPlatform platform;

    @Override
    public void onInitialize() {
        // 从 fabric.mod.json 获取版本
        try {
            var container = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID);
            if (container.isPresent()) {
                VERSION = container.get().getMetadata().getVersion().getFriendlyString();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get mod version from Fabric Loader", e);
        }

        // 初始化 Platform（Fabric 实现）
        platform = new FabricPlatform();
        PlatformManager.initialize(platform);
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        // 初始化 NetworkManager（Fabric 网络实现）
        networkHandler = new FabricNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized");

        // 注册服务端生命周期事件
        registerServerEvents();

        // 注册服务端命令（/mapsyncerserver，避免与客户端 /mapsyncer 树根冲突）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CacheGenerateCommand.register(dispatcher, "mapsyncerserver");
        });

        // 注册服务端网络接收器（同时注册 PayloadTypeRegistry 类型）
        networkHandler.registerHandlers(null);
        ServerSyncHandler.register(null);

        LOGGER.info("MapSyncer initialized (Fabric 1.21), version: {}", VERSION);
    }

    /**
     * 注册服务端生命周期事件
     */
    private void registerServerEvents() {
        // 服务端启动时
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("Server starting, initializing MapSyncer...");

            // 初始化配置（使用游戏根目录下的 config 目录）
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            ModConfig.getServerConfig(configDir);

            // 设置平台的服务器实例
            platform.setServer(server);

            // 内置服务器：复用 Xaero 客户端地图目录作缓存
            ConversionOrchestrator.tryInitIntegratedServerCache(server, FabricLoader.getInstance().getGameDir());

            // 注册所有维度
            DimensionRegistry.registerAllDimensions(server);

            // 启动增量更新处理器（如果配置启用）
            Platform platformImpl = PlatformManager.getPlatform();
            UpdateMode mode = platformImpl.getIncrementalUpdateMode();
            if (mode != UpdateMode.DISABLED) {
                IncrementalUpdateHandlerLogic.getInstance().start(server);
                LOGGER.info("Incremental update handler started with mode: {}", mode);
            }
        });

        // 服务端停止时
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, cleaning up MapSyncer...");

            IncrementalUpdateHandlerLogic.getInstance().stop();
            com.mapsyncer.server.ConversionOrchestrator.shutdownExecutor();
            com.mapsyncer.server.PlayerJoinHandler.onServerStopped();

            platform.setServer(null);

            LOGGER.info("MapSyncer cleanup completed");
        });

        // 玩家加入时
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            com.mapsyncer.server.PlayerJoinHandler.onPlayerJoin(handler.player, server);
        });

        // 玩家离开时
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            com.mapsyncer.server.PlayerJoinHandler.onPlayerLeave(handler.player.getUUID());
        });

        // 服务端 Tick 事件
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            IncrementalUpdateHandlerLogic.getInstance().onServerTick();
            com.mapsyncer.server.PlayerJoinHandler.onServerTick(server);
        });
    }

    /**
     * 获取网络处理器实例
     */
    public static FabricNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    /**
     * 获取平台实例
     */
    public static FabricPlatform getPlatform() {
        return platform;
    }
}