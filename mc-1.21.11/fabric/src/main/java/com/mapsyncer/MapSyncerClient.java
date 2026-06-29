package com.mapsyncer;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.MapSyncerCommand;
import com.mapsyncer.client.SyncResumeHelper;
import com.mapsyncer.client.SyncProgressTracker;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.impl.FabricNetworkHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer 客户端初始化类 - Fabric 1.21.11 版本
 *
 * 实现 ClientModInitializer 接口，在客户端初始化时注册网络接收器和命令。
 */
public class MapSyncerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerClient.class);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing MapSyncer client...");

        // 初始化客户端配置（生成默认配置文件）
        ModConfig.getClientConfig(FabricLoader.getInstance().getConfigDir());

        // 注册客户端网络接收器
        FabricNetworkHandler networkHandler = MapSyncer.getNetworkHandler();
        if (networkHandler != null) {
            networkHandler.registerClientHandlers();
            LOGGER.info("Client network handlers registered");
        }

        // 注册客户端命令
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            MapSyncerCommand.registerClientCommands(dispatcher);
            LOGGER.info("Client commands registered");
        });

        // 注册客户端连接事件
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LOGGER.info("Client joined server, checking sync state...");
            // 注册网络接收器
            MapPacketReceiver.register();
            SyncResumeHelper.onPlayerLoggingIn();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Client disconnected from server, resetting state...");
            MapPacketHandler.onDisconnect();
            SyncProgressTracker.shutdown();
        });

        // 注册 ClientTick 事件：每 tick 排放一个视距外 region 到 Xaero MapProcessor
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MapPacketHandler.drainPendingLoadQueue();
        });

        LOGGER.info("MapSyncer client initialized");
    }
}
