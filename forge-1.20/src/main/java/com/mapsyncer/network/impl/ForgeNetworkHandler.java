package com.mapsyncer.network.impl;

import com.mapsyncer.network.ForgePayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.event.RegisterPayloadHandlersEvent;
import net.minecraftforge.network.handling.IPayloadContext;
import net.minecraftforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;

/**
 * Forge 网络处理器实现
 *
 * 实现 NetworkHandler 接口，适配 Forge 1.20 的网络 API。
 * 使用 ForgePayloadAdapters 将平台无关 Payload 转换为 Forge 特定类型。
 *
 * 与 NeoForge 版本几乎相同，唯一差异是包名前缀。
 */
public class ForgeNetworkHandler implements NetworkHandler {

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    @Override
    public void registerHandlers(Object event) {
        RegisterPayloadHandlersEvent forgeEvent = (RegisterPayloadHandlersEvent) event;
        PayloadRegistrar registrar = forgeEvent.registrar("1").optional();

        // 同步请求（客户端 -> 服务端）
        registrar.playToServer(
            ForgePayloadAdapters.ForgeSyncRequestPayload.TYPE,
            ForgePayloadAdapters.ForgeSyncRequestPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (syncRequestHandler != null) {
                    syncRequestHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );

        // 同步响应（服务端 -> 客户端）
        registrar.playToClient(
            ForgePayloadAdapters.ForgeSyncResponsePayload.TYPE,
            ForgePayloadAdapters.ForgeSyncResponsePayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (syncResponseHandler != null) {
                    syncResponseHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );

        // 同步进度（服务端 -> 客户端）
        registrar.playToClient(
            ForgePayloadAdapters.ForgeSyncProgressPayload.TYPE,
            ForgePayloadAdapters.ForgeSyncProgressPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (syncProgressHandler != null) {
                    syncProgressHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );

        // 服务端已安装通知（服务端 -> 客户端）
        registrar.playToClient(
            ForgePayloadAdapters.ForgeServerInstalledPayload.TYPE,
            ForgePayloadAdapters.ForgeServerInstalledPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (serverInstalledHandler != null) {
                    serverInstalledHandler.accept(payload.data(), new PayloadContext(ctx));
                }
            }
        );
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        PacketDistributor.sendToServer(new ForgePayloadAdapters.ForgeSyncRequestPayload(payload));
    }

    @Override
    public void sendToPlayer(Object player, SyncResponsePayload payload) {
        PacketDistributor.sendToPlayer((ServerPlayer) player,
            new ForgePayloadAdapters.ForgeSyncResponsePayload(payload));
    }

    @Override
    public void sendToPlayer(Object player, SyncProgressPayload payload) {
        PacketDistributor.sendToPlayer((ServerPlayer) player,
            new ForgePayloadAdapters.ForgeSyncProgressPayload(payload));
    }

    @Override
    public void sendToPlayer(Object player, ServerInstalledPayload payload) {
        PacketDistributor.sendToPlayer((ServerPlayer) player,
            new ForgePayloadAdapters.ForgeServerInstalledPayload(payload));
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
        IPayloadContext forgeCtx = (IPayloadContext) context.getPlatformContext();
        forgeCtx.enqueueWork(work);
    }

    @Override
    public Object getPlayerFromContext(PayloadContext context) {
        IPayloadContext forgeCtx = (IPayloadContext) context.getPlatformContext();
        return forgeCtx.player();
    }
}