package com.mapsyncer.network.impl;

import com.mapsyncer.network.NeoForgePayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;

/**
 * NeoForge 网络处理器实现
 *
 * <p>实现 NetworkHandler 泛型接口，适配 NeoForge 1.21 的网络 API。</p>
 * <p>使用 NeoForgePayloadAdapters 将平台无关 Payload 转换为 NeoForge 特定类型。</p>
 * <p>类型安全：PLAYER_TYPE=ServerPlayer, EVENT_TYPE=RegisterPayloadHandlersEvent</p>
 */
public class NeoForgeNetworkHandler implements NetworkHandler<ServerPlayer, RegisterPayloadHandlersEvent> {

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    @Override
    public void registerHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();

        // 同步请求（客户端 -> 服务端）
        registrar.playToServer(
            NeoForgePayloadAdapters.NeoForgeSyncRequestPayload.TYPE,
            NeoForgePayloadAdapters.NeoForgeSyncRequestPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (syncRequestHandler != null) {
                    syncRequestHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );

        // 同步响应（服务端 -> 客户端）
        registrar.playToClient(
            NeoForgePayloadAdapters.NeoForgeSyncResponsePayload.TYPE,
            NeoForgePayloadAdapters.NeoForgeSyncResponsePayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (syncResponseHandler != null) {
                    syncResponseHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );

        // 同步进度（服务端 -> 客户端）
        registrar.playToClient(
            NeoForgePayloadAdapters.NeoForgeSyncProgressPayload.TYPE,
            NeoForgePayloadAdapters.NeoForgeSyncProgressPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (syncProgressHandler != null) {
                    syncProgressHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );

        // 服务端已安装通知（服务端 -> 客户端）
        registrar.playToClient(
            NeoForgePayloadAdapters.NeoForgeServerInstalledPayload.TYPE,
            NeoForgePayloadAdapters.NeoForgeServerInstalledPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (serverInstalledHandler != null) {
                    serverInstalledHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        PacketDistributor.sendToServer(new NeoForgePayloadAdapters.NeoForgeSyncRequestPayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        PacketDistributor.sendToPlayer(player,
            new NeoForgePayloadAdapters.NeoForgeSyncResponsePayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        PacketDistributor.sendToPlayer(player,
            new NeoForgePayloadAdapters.NeoForgeSyncProgressPayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        PacketDistributor.sendToPlayer(player,
            new NeoForgePayloadAdapters.NeoForgeServerInstalledPayload(payload));
    }

    @Override
    public void registerSyncResponseHandler(BiConsumer<SyncResponsePayload, PayloadContext> handler) {
        this.syncResponseHandler = handler;
    }

    @Override
    public void registerSyncProgressHandler(BiConsumer<SyncProgressPayload, PayloadContext> handler) {
        this.syncProgressHandler = handler;
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
        IPayloadContext neoCtx = (IPayloadContext) context.getPlatformContext();
        neoCtx.enqueueWork(work);
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        IPayloadContext neoCtx = (IPayloadContext) context.getPlatformContext();
        return (ServerPlayer) neoCtx.player();
    }
}