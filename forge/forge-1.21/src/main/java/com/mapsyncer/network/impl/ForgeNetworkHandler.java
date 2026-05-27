package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.ForgePayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.BiConsumer;

/**
 * Forge 1.21 网络处理器实现
 *
 * 实现 NetworkHandler 接口，适配 Forge 1.21 的网络 API。
 * Forge 1.21 使用 ChannelBuilder/SimpleChannel API（与 NeoForge 的 PayloadRegistrar 不同）。
 * 使用 ForgePayloadAdapters 将平台无关 Payload 转换为 Forge 特定类型。
 */
public class ForgeNetworkHandler implements NetworkHandler {

    private static final SimpleChannel CHANNEL = ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath(MapSyncer.MOD_ID, "main"))
        .networkProtocolVersion(1)
        .simpleChannel();

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    // 静态初始化：注册所有 Payload 类型
    static {
        // 同步请求（客户端 -> 服务端）
        CHANNEL.messageBuilder(ForgePayloadAdapters.ForgeSyncRequestPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ForgePayloadAdapters.ForgeSyncRequestPayload::encode)
            .decoder(ForgePayloadAdapters.ForgeSyncRequestPayload::decode)
            .consumerMainThread((payload, context) -> {
                // 这里需要通过实例方法处理，使用静态引用
                NetworkHandler handler = NetworkManager.getHandler();
                if (handler instanceof ForgeNetworkHandler forgeHandler && forgeHandler.syncRequestHandler != null) {
                    forgeHandler.syncRequestHandler.accept(payload.data(), new PayloadContext(context));
                }
            })
            .add();

        // 同步响应（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgePayloadAdapters.ForgeSyncResponsePayload.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgePayloadAdapters.ForgeSyncResponsePayload::encode)
            .decoder(ForgePayloadAdapters.ForgeSyncResponsePayload::decode)
            .consumerMainThread((payload, context) -> {
                NetworkHandler handler = NetworkManager.getHandler();
                if (handler instanceof ForgeNetworkHandler forgeHandler && forgeHandler.syncResponseHandler != null) {
                    forgeHandler.syncResponseHandler.accept(payload.data(), new PayloadContext(context));
                }
            })
            .add();

        // 同步进度（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgePayloadAdapters.ForgeSyncProgressPayload.class, 2, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgePayloadAdapters.ForgeSyncProgressPayload::encode)
            .decoder(ForgePayloadAdapters.ForgeSyncProgressPayload::decode)
            .consumerMainThread((payload, context) -> {
                NetworkHandler handler = NetworkManager.getHandler();
                if (handler instanceof ForgeNetworkHandler forgeHandler && forgeHandler.syncProgressHandler != null) {
                    forgeHandler.syncProgressHandler.accept(payload.data(), new PayloadContext(context));
                }
            })
            .add();

        // 服务端已安装通知（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgePayloadAdapters.ForgeServerInstalledPayload.class, 3, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgePayloadAdapters.ForgeServerInstalledPayload::encode)
            .decoder(ForgePayloadAdapters.ForgeServerInstalledPayload::decode)
            .consumerMainThread((payload, context) -> {
                NetworkHandler handler = NetworkManager.getHandler();
                if (handler instanceof ForgeNetworkHandler forgeHandler && forgeHandler.serverInstalledHandler != null) {
                    forgeHandler.serverInstalledHandler.accept(payload.data(), new PayloadContext(context));
                }
            })
            .add();
    }

    @Override
    public void registerHandlers(Object event) {
        // Forge 1.21 使用静态 Channel 注册，此方法保留为空（兼容接口）
        // 实际注册在 static 块中完成
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        CHANNEL.send(new ForgePayloadAdapters.ForgeSyncRequestPayload(payload), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToPlayer(Object player, SyncResponsePayload payload) {
        CHANNEL.send(new ForgePayloadAdapters.ForgeSyncResponsePayload(payload),
            PacketDistributor.PLAYER.with((ServerPlayer) player));
    }

    @Override
    public void sendToPlayer(Object player, SyncProgressPayload payload) {
        CHANNEL.send(new ForgePayloadAdapters.ForgeSyncProgressPayload(payload),
            PacketDistributor.PLAYER.with((ServerPlayer) player));
    }

    @Override
    public void sendToPlayer(Object player, ServerInstalledPayload payload) {
        CHANNEL.send(new ForgePayloadAdapters.ForgeServerInstalledPayload(payload),
            PacketDistributor.PLAYER.with((ServerPlayer) player));
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
        // Forge 的 NetworkEvent.Context 提供 enqueueWork 方法
        Object platformContext = context.getPlatformContext();
        if (platformContext instanceof net.minecraftforge.network.NetworkEvent.Context forgeCtx) {
            forgeCtx.enqueueWork(work);
        } else {
            work.run();
        }
    }

    @Override
    public Object getPlayerFromContext(PayloadContext context) {
        Object platformContext = context.getPlatformContext();
        if (platformContext instanceof net.minecraftforge.network.NetworkEvent.Context forgeCtx) {
            return forgeCtx.getSender();
        }
        return null;
    }
}