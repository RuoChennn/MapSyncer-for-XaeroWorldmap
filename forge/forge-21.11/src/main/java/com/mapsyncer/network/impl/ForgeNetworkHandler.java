package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.ForgePayloadAdapters;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncRequestMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncResponseMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncProgressMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeServerInstalledMessage;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.util.function.BiConsumer;

/**
 * Forge 21.11 网络处理器实现
 *
 * <p>实现 NetworkHandler 泛型接口，适配 Forge 21.11 的网络 API。</p>
 * <p>Forge 21.11 使用 ChannelBuilder/SimpleChannel API。</p>
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

    // 初始化：注册所有消息类型
    public void init() {
        // 同步请求（客户端 -> 服务端）
        CHANNEL.messageBuilder(ForgeSyncRequestMessage.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ForgeSyncRequestMessage::encode)
            .decoder(ForgeSyncRequestMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (syncRequestHandler != null) {
                    syncRequestHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();

        // 同步响应（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgeSyncResponseMessage.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgeSyncResponseMessage::encode)
            .decoder(ForgeSyncResponseMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (syncResponseHandler != null) {
                    syncResponseHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();

        // 同步进度（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgeSyncProgressMessage.class, 2, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgeSyncProgressMessage::encode)
            .decoder(ForgeSyncProgressMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (syncProgressHandler != null) {
                    syncProgressHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();

        // 服务端已安装通知（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgeServerInstalledMessage.class, 3, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgeServerInstalledMessage::encode)
            .decoder(ForgeServerInstalledMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (serverInstalledHandler != null) {
                    serverInstalledHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();
    }

    @Override
    public void registerHandlers(Object event) {
        // Forge 1.21 在构造时直接注册，不需要事件
        init();
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        CHANNEL.send(new ForgeSyncRequestMessage(payload), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        CHANNEL.send(new ForgeSyncResponseMessage(payload), PacketDistributor.PLAYER.with(player));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        CHANNEL.send(new ForgeSyncProgressMessage(payload), PacketDistributor.PLAYER.with(player));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        CHANNEL.send(new ForgeServerInstalledMessage(payload), PacketDistributor.PLAYER.with(player));
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
        Object platformContext = context.getPlatformContext();
        if (platformContext instanceof CustomPayloadEvent.Context forgeCtx) {
            forgeCtx.enqueueWork(work);
        } else {
            work.run();
        }
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        Object platformContext = context.getPlatformContext();
        if (platformContext instanceof CustomPayloadEvent.Context forgeCtx) {
            return forgeCtx.getSender();
        }
        return null;
    }
}