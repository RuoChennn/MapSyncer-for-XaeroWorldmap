package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.FabricPayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Identifier;

import java.util.function.BiConsumer;

/**
 * Fabric 网络处理器实现
 *
 * Fabric 1.20+ 使用 Fabric Networking API v1 (PayloadTypeRegistry + ServerPlayNetworking/ClientPlayNetworking)
 */
public class FabricNetworkHandler implements NetworkHandler {

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    @Override
    public void registerHandlers(Object event) {
        // 注册 Payload 类型
        PayloadTypeRegistry.playC2S().register(SyncRequestPayload.ID, SyncRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncResponsePayload.ID, SyncResponsePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncProgressPayload.ID, SyncProgressPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerInstalledPayload.ID, ServerInstalledPayload.CODEC);

        // 注册服务端接收器
        ServerPlayNetworking.registerGlobalReceiver(SyncRequestPayload.ID, (payload, context) -> {
            if (syncRequestHandler != null) {
                syncRequestHandler.accept(payload, new PayloadContext(context));
            }
        });
    }

    /**
     * 注册客户端接收器（在客户端初始化时调用）
     */
    public void registerClientHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(SyncResponsePayload.ID, (payload, context) -> {
            if (syncResponseHandler != null) {
                syncResponseHandler.accept(payload, new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(SyncProgressPayload.ID, (payload, context) -> {
            if (serverInstalledHandler != null) {
                serverInstalledHandler.accept(payload, new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(ServerInstalledPayload.ID, (payload, context) -> {
            if (serverInstalledHandler != null) {
                serverInstalledHandler.accept(payload, new PayloadContext(context));
            }
        });
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void sendToPlayer(Object player, SyncResponsePayload payload) {
        ServerPlayNetworking.send((ServerPlayer) player, payload);
    }

    @Override
    public void sendToPlayer(Object player, SyncProgressPayload payload) {
        ServerPlayNetworking.send((ServerPlayer) player, payload);
    }

    @Override
    public void sendToPlayer(Object player, ServerInstalledPayload payload) {
        ServerPlayNetworking.send((ServerPlayer) player, payload);
    }

    @Override
    public void registerSyncResponseHandler(BiConsumer<SyncResponsePayload, PayloadContext> handler) {
        this.syncResponseHandler = handler;
    }

    @Override
    public void registerSyncProgressHandler(BiConsumer<SyncProgressPayload, PayloadContext> handler) {
        // Fabric 进度处理也通过 syncResponseHandler 处理
    }

    @Override
    public void registerServerInstalledHandler(BiConsumer<ServerInstalledPayload, PayloadContext> handler) {
        this.serverInstalledHandler = handler;
    }

    @Override
    public void registerSyncRequestHandler(BiConsumer<SyncRequestPayload, PayloadContext> handler) {
        this.syncRequestHandler = handler;
    }

    @Override
    public void enqueueWork(PayloadContext context, Runnable work) {
        // Fabric 的 context.client() 或 context.server() 已经提供了线程安全的执行方式
        context.getPlatformContext().enqueueWork(work);
    }

    @Override
    public Object getPlayerFromContext(PayloadContext context) {
        return context.getPlatformContext().player();
    }
}