package com.mapsyncer.network.impl;

import com.mapsyncer.network.NeoForgePayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * NeoForge 网络处理器实现
 */
public class NeoForgeNetworkHandler implements NetworkHandler<ServerPlayer, RegisterPayloadHandlersEvent> {

    private final AtomicBoolean payloadsRegistered = new AtomicBoolean(false);

    /** 已确认安装了 MapSyncer 的玩家 — 仅对这些玩家发送自定义 payload */
    private final Set<UUID> confirmedPlayers = ConcurrentHashMap.newKeySet();

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    @Override
    public void registerHandlers(RegisterPayloadHandlersEvent event) {
        if (!payloadsRegistered.compareAndSet(false, true)) return;

        PayloadRegistrar registrar = event.registrar("1").optional();

        // 同步请求（客户端 -> 服务端）：收到即确认该客户端安装了 MapSyncer
        registrar.playToServer(
            NeoForgePayloadAdapters.NeoForgeSyncRequestPayload.TYPE,
            NeoForgePayloadAdapters.NeoForgeSyncRequestPayload.STREAM_CODEC,
            (payload, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    confirmedPlayers.add(sp.getUUID());
                }
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
        ClientPacketDistributor.sendToServer(new NeoForgePayloadAdapters.NeoForgeSyncRequestPayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        PacketDistributor.sendToPlayer(player,
            new NeoForgePayloadAdapters.NeoForgeSyncResponsePayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        PacketDistributor.sendToPlayer(player,
            new NeoForgePayloadAdapters.NeoForgeSyncProgressPayload(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        PacketDistributor.sendToPlayer(player,
            new NeoForgePayloadAdapters.NeoForgeServerInstalledPayload(payload));
    }

    /** 玩家断线时清理确认状态 */
    public void onPlayerDisconnect(UUID playerId) {
        confirmedPlayers.remove(playerId);
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
