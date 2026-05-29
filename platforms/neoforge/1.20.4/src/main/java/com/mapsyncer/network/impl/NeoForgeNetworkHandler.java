package com.mapsyncer.network.impl;

import com.mapsyncer.network.NeoForgePayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;

import java.util.function.BiConsumer;

/**
 * NeoForge 网络处理器实现 - 1.20.4 版本
 *
 * <p>实现 NetworkHandler 泛型接口，适配 NeoForge 20.4.x 的网络 API。</p>
 * <p>NeoForge 20.4.x 使用 play() + IDirectionAwarePayloadHandlerBuilder 模式注册处理器。</p>
 * <p>类型安全：PLAYER_TYPE=ServerPlayer, EVENT_TYPE=RegisterPayloadHandlerEvent</p>
 */
public class NeoForgeNetworkHandler implements NetworkHandler<ServerPlayer, RegisterPayloadHandlerEvent> {

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    @Override
    public void registerHandlers(RegisterPayloadHandlerEvent event) {
        IPayloadRegistrar registrar = event.registrar("mapsyncer").optional();

        // 同步请求（客户端 -> 服务端）
        registrar.play(
            NeoForgePayloadAdapters.NeoForgeSyncRequestPayload.ID,
            NeoForgePayloadAdapters.NeoForgeSyncRequestPayload.READER,
            builder -> builder.server((payload, ctx) -> {
                if (syncRequestHandler != null) {
                    syncRequestHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            })
        );

        // 同步响应（服务端 -> 客户端）
        registrar.play(
            NeoForgePayloadAdapters.NeoForgeSyncResponsePayload.ID,
            NeoForgePayloadAdapters.NeoForgeSyncResponsePayload.READER,
            builder -> builder.client((payload, ctx) -> {
                if (syncResponseHandler != null) {
                    syncResponseHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            })
        );

        // 同步进度（服务端 -> 客户端）
        registrar.play(
            NeoForgePayloadAdapters.NeoForgeSyncProgressPayload.ID,
            NeoForgePayloadAdapters.NeoForgeSyncProgressPayload.READER,
            builder -> builder.client((payload, ctx) -> {
                if (syncProgressHandler != null) {
                    syncProgressHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            })
        );

        // 服务端已安装通知（服务端 -> 客户端）
        registrar.play(
            NeoForgePayloadAdapters.NeoForgeServerInstalledPayload.ID,
            NeoForgePayloadAdapters.NeoForgeServerInstalledPayload.READER,
            builder -> builder.client((payload, ctx) -> {
                if (serverInstalledHandler != null) {
                    serverInstalledHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            })
        );
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        PacketDistributor.SERVER.noArg().send(new NeoForgePayloadAdapters.NeoForgeSyncRequestPayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        PacketDistributor.PLAYER.with(player).send(new NeoForgePayloadAdapters.NeoForgeSyncResponsePayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        PacketDistributor.PLAYER.with(player).send(new NeoForgePayloadAdapters.NeoForgeSyncProgressPayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        PacketDistributor.PLAYER.with(player).send(new NeoForgePayloadAdapters.NeoForgeServerInstalledPayload(payload));
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
        neoCtx.workHandler().execute(work);
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        IPayloadContext neoCtx = (IPayloadContext) context.getPlatformContext();
        return (ServerPlayer) neoCtx.player().orElseThrow();
    }
}
