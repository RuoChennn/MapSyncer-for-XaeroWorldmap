package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.MapSyncerCommand;
import com.mapsyncer.client.ClientJoinHandler;
import com.mapsyncer.network.PacketHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * MapSyncer 客户端入口点 - Fabric 版本
 *
 * 负责注册客户端侧的网络包处理器和事件监听器。
 */
public class MapSyncerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 注册客户端接收处理
        registerClientPackets();

        // 注册客户端命令
        MapSyncerCommand.register();

        // 注册客户端事件
        registerClientEvents();

        MapSyncer.LOGGER.info("MapSyncer initialized (client mode)");
    }

    /**
     * 注册客户端侧的网络包处理
     */
    private void registerClientPackets() {
        // 客户端接收 服务端->客户端 的包
        ClientPlayNetworking.registerGlobalReceiver(
                PacketHandler.SyncResponsePayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        MapPacketReceiver.handleSyncResponse(payload);
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                PacketHandler.SyncProgressPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        MapPacketReceiver.handleProgressUpdate(payload);
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                PacketHandler.ServerInstalledPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        MapPacketReceiver.handleServerInstalled(payload);
                    });
                }
        );
    }

    /**
     * 注册客户端事件
     */
    private void registerClientEvents() {
        // 客户端断开连接事件
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            MapPacketReceiver.resetServerStatus();
            MapPacketReceiver.clearSyncData();
            MapSyncer.LOGGER.info("Client disconnected from server, reset server status");
        });

        // 客户端加入服务器事件
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientJoinHandler.onPlayerLoggingIn(client);
        });
    }
}
