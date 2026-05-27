package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.SimpleChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BiConsumer;

/**
 * Forge 26.x 网络处理器实现
 *
 * <p>Forge 56.x 使用 SimpleChannel + FriendlyByteBuf。</p>
 * <p>类型安全：PLAYER_TYPE=ServerPlayer, EVENT_TYPE=Object</p>
 */
public class ForgeNetworkHandler implements NetworkHandler<ServerPlayer, Object> {

    private static final SimpleChannel CHANNEL = ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(MapSyncer.MOD_ID, "main"))
            .networkProtocolVersion(1)
            .simpleChannel();

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    public ForgeNetworkHandler() {
        // 注册 Payload 类型
        CHANNEL.messageBuilder(SyncRequestPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ForgePayloadAdapters::writeSyncRequest)
                .decoder(ForgePayloadAdapters::readSyncRequest)
                .consumerMainThread(this::handleSyncRequest)
                .add();

        CHANNEL.messageBuilder(SyncResponsePayload.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ForgePayloadAdapters::writeSyncResponse)
                .decoder(ForgePayloadAdapters::readSyncResponse)
                .consumerMainThread(this::handleSyncResponse)
                .add();

        CHANNEL.messageBuilder(SyncProgressPayload.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ForgePayloadAdapters::writeSyncProgress)
                .decoder(ForgePayloadAdapters::readSyncProgress)
                .consumerMainThread(this::handleSyncProgress)
                .add();

        CHANNEL.messageBuilder(ServerInstalledPayload.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ForgePayloadAdapters::writeServerInstalled)
                .decoder(ForgePayloadAdapters::readServerInstalled)
                .consumerMainThread(this::handleServerInstalled)
                .add();
    }

    private void handleSyncRequest(SyncRequestPayload payload, NetworkEvent.Context context) {
        if (syncRequestHandler != null) {
            syncRequestHandler.accept(payload, new PayloadContext(context));
        }
    }

    private void handleSyncResponse(SyncResponsePayload payload, NetworkEvent.Context context) {
        if (syncResponseHandler != null) {
            syncResponseHandler.accept(payload, new PayloadContext(context));
        }
    }

    private void handleSyncProgress(SyncProgressPayload payload, NetworkEvent.Context context) {
        if (syncProgressHandler != null) {
            syncProgressHandler.accept(payload, new PayloadContext(context));
        }
    }

    private void handleServerInstalled(ServerInstalledPayload payload, NetworkEvent.Context context) {
        if (serverInstalledHandler != null) {
            serverInstalledHandler.accept(payload, new PayloadContext(context));
        }
    }

    @Override
    public void registerHandlers(Object event) {
        // Forge 在构造方法中已注册
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        CHANNEL.send(payload, NetworkDirection.PLAY_TO_SERVER);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        CHANNEL.send(payload, NetworkDirection.PLAY_TO_CLIENT, player);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        CHANNEL.send(payload, NetworkDirection.PLAY_TO_CLIENT, player);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        CHANNEL.send(payload, NetworkDirection.PLAY_TO_CLIENT, player);
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
        context.getPlatformContext().enqueueWork(work);
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        return context.getPlatformContext().getPlayer();
    }
}