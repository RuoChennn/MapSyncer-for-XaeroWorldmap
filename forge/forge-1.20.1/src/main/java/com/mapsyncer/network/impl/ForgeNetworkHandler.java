package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.ForgePayloadAdapters;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncRequestMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncResponseMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncProgressMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeServerInstalledMessage;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Forge 网络处理器实现（传统 SimpleNetworkWrapper 方式）
 *
 * Forge 1.20.1 使用 SimpleNetworkWrapper.newSimpleChannel() 创建网络通道，
 * 与 NeoForge 的 ChannelBuilder API 不同。
 */
public class ForgeNetworkHandler implements NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";
    private static SimpleChannel CHANNEL;

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    private boolean registered = false;

    /**
     * 初始化网络通道（在模组构造时调用）
     */
    public void init() {
        if (CHANNEL != null) return;

        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(MapSyncer.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        // 注册同步请求消息（客户端 -> 服务端）
        CHANNEL.registerMessage(0, ForgeSyncRequestMessage.class,
                ForgeSyncRequestMessage::encode,
                ForgeSyncRequestMessage::decode,
                this::handleSyncRequest);

        // 注册同步响应消息（服务端 -> 客户端）
        CHANNEL.registerMessage(1, ForgeSyncResponseMessage.class,
                ForgeSyncResponseMessage::encode,
                ForgeSyncResponseMessage::decode,
                this::handleSyncResponse);

        // 注册同步进度消息（服务端 -> 客户端）
        CHANNEL.registerMessage(2, ForgeSyncProgressMessage.class,
                ForgeSyncProgressMessage::encode,
                ForgeSyncProgressMessage::decode,
                this::handleSyncProgress);

        // 注册服务端已安装消息（服务端 -> 客户端）
        CHANNEL.registerMessage(3, ForgeServerInstalledMessage.class,
                ForgeServerInstalledMessage::encode,
                ForgeServerInstalledMessage::decode,
                this::handleServerInstalled);
    }

    private void handleSyncRequest(ForgeSyncRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (syncRequestHandler != null) {
            syncRequestHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    private void handleSyncResponse(ForgeSyncResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (syncResponseHandler != null) {
            syncResponseHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    private void handleSyncProgress(ForgeSyncProgressMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (syncProgressHandler != null) {
            syncProgressHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    private void handleServerInstalled(ForgeServerInstalledMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (serverInstalledHandler != null) {
            serverInstalledHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    @Override
    public void registerHandlers(Object event) {
        // Forge 1.20 在构造时直接注册，不需要事件
        init();
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        CHANNEL.sendToServer(new ForgeSyncRequestMessage(payload));
    }

    @Override
    public void sendToPlayer(Object player, SyncResponsePayload payload) {
        ServerPlayer sp = (ServerPlayer) player;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new ForgeSyncResponseMessage(payload));
    }

    @Override
    public void sendToPlayer(Object player, SyncProgressPayload payload) {
        ServerPlayer sp = (ServerPlayer) player;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new ForgeSyncProgressMessage(payload));
    }

    @Override
    public void sendToPlayer(Object player, ServerInstalledPayload payload) {
        ServerPlayer sp = (ServerPlayer) player;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new ForgeServerInstalledMessage(payload));
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
        Supplier<NetworkEvent.Context> forgeCtx = (Supplier<NetworkEvent.Context>) context.getPlatformContext();
        forgeCtx.get().enqueueWork(work);
    }

    @Override
    public Object getPlayerFromContext(PayloadContext context) {
        Supplier<NetworkEvent.Context> forgeCtx = (Supplier<NetworkEvent.Context>) context.getPlatformContext();
        return forgeCtx.get().getSender();
    }
}