package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.MapSyncerCommand;
import com.mapsyncer.client.SyncProgressTracker;
import com.mapsyncer.network.impl.FabricNetworkHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer客户端初始化类 - Fabric 26.x 版本
 *
 * 实现 ClientModInitializer 接口，在客户端初始化时注册网络接收器和命令。
 * API 预估与 Fabric 1.21 高度兼容。
 */
public class MapSyncerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerClient.class);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing MapSyncer client...");

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
        });

        ClientPlayConnectionEvents.DISJOIN.register((handler, client) -> {
            LOGGER.info("Client disconnected from server, resetting state...");
            // 重置服务端安装状态
            MapPacketReceiver.resetServerStatus();
            MapPacketReceiver.clearSyncData();
            // 清理 XaeroMapIntegrator 区域追踪
            com.mapsyncer.client.XaeroMapIntegrator.clearRegionTracking();
            // 关闭进度追踪器的线程池（防止内存泄漏）
            SyncProgressTracker.shutdown();
        });

        LOGGER.info("MapSyncer client initialized");
    }
}