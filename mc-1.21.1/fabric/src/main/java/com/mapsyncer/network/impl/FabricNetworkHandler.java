package com.mapsyncer.network.impl;

import com.mapsyncer.network.FabricPayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Fabric 1.21.1 网络处理器实现
 *
 * <p>Fabric 1.21.1 使用 Fabric Networking API v1 (PayloadTypeRegistry + CustomPacketPayload + StreamCodec)</p>
 * <p>Payload DTOs 在 platform-api 中定义为平台无关的纯 record，通过 FabricPayloadAdapters 包装为 CustomPacketPayload。</p>
 * <p>类型安全：PLAYER_TYPE=ServerPlayer, EVENT_TYPE=Object</p>
 */
public class FabricNetworkHandler implements NetworkHandler<ServerPlayer, Object> {

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;
    private BiConsumer<ContributionRequestPayload, PayloadContext> contributionRequestHandler;
    private BiConsumer<ContributionDataPayload, PayloadContext> contributionDataHandler;
    private BiConsumer<ContributionCompletePayload, PayloadContext> contributionCompleteHandler;
    private BiConsumer<ContributionOnlyRequestPayload, PayloadContext> contributionOnlyRequestHandler;
    private BiConsumer<ContributionResultPayload, PayloadContext> contributionResultHandler;

    /** 防止 Payload 类型被重复注册（集成客户端环境下 main + client 入口点各调用一次） */
    private final AtomicBoolean payloadTypesRegistered = new AtomicBoolean(false);

    /**
     * 注册 Payload 类型到 PayloadTypeRegistry（幂等操作）
     */
    private void registerPayloadTypes() {
        if (!payloadTypesRegistered.compareAndSet(false, true)) {
            return; // 已注册过，跳过
        }

        PayloadTypeRegistry.playC2S().register(
                FabricPayloadAdapters.SYNC_REQUEST_TYPE,
                FabricPayloadAdapters.SYNC_REQUEST_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.SYNC_RESPONSE_TYPE,
                FabricPayloadAdapters.SYNC_RESPONSE_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.SYNC_PROGRESS_TYPE,
                FabricPayloadAdapters.SYNC_PROGRESS_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.SERVER_INSTALLED_TYPE,
                FabricPayloadAdapters.SERVER_INSTALLED_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.CONTRIBUTION_REQUEST_TYPE,
                FabricPayloadAdapters.CONTRIBUTION_REQUEST_CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                FabricPayloadAdapters.CONTRIBUTION_DATA_TYPE,
                FabricPayloadAdapters.CONTRIBUTION_DATA_CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                FabricPayloadAdapters.CONTRIBUTION_COMPLETE_TYPE,
                FabricPayloadAdapters.CONTRIBUTION_COMPLETE_CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                FabricPayloadAdapters.CONTRIBUTION_ONLY_REQUEST_TYPE,
                FabricPayloadAdapters.CONTRIBUTION_ONLY_REQUEST_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.CONTRIBUTION_RESULT_TYPE,
                FabricPayloadAdapters.CONTRIBUTION_RESULT_CODEC
        );
    }

    @Override
    public void registerHandlers(Object event) {
        // 注册 Payload 类型
        registerPayloadTypes();

        // 注册服务端接收器
        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SYNC_REQUEST_TYPE, (wrapper, context) -> {
            if (syncRequestHandler != null) {
                syncRequestHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.CONTRIBUTION_DATA_TYPE, (wrapper, context) -> {
            if (contributionDataHandler != null) {
                contributionDataHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.CONTRIBUTION_COMPLETE_TYPE, (wrapper, context) -> {
            if (contributionCompleteHandler != null) {
                contributionCompleteHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.CONTRIBUTION_ONLY_REQUEST_TYPE, (wrapper, context) -> {
            if (contributionOnlyRequestHandler != null) {
                contributionOnlyRequestHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });
    }

    /**
     * 注册客户端接收器（在客户端初始化时调用）
     */
    public void registerClientHandlers() {
        // 确保 payload 类型已注册（客户端可能在服务端之前初始化）
        registerPayloadTypes();

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SYNC_RESPONSE_TYPE, (wrapper, context) -> {
            if (syncResponseHandler != null) {
                syncResponseHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SYNC_PROGRESS_TYPE, (wrapper, context) -> {
            if (syncProgressHandler != null) {
                syncProgressHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SERVER_INSTALLED_TYPE, (wrapper, context) -> {
            if (serverInstalledHandler != null) {
                serverInstalledHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.CONTRIBUTION_REQUEST_TYPE, (wrapper, context) -> {
            if (contributionRequestHandler != null) {
                contributionRequestHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.CONTRIBUTION_RESULT_TYPE, (wrapper, context) -> {
            if (contributionResultHandler != null) {
                contributionResultHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        ClientPlayNetworking.send(new FabricPayloadAdapters.SyncRequestWrapper(payload));
    }

    @Override
    public void sendToServer(ContributionDataPayload payload) {
        ClientPlayNetworking.send(new FabricPayloadAdapters.ContributionDataWrapper(payload));
    }

    @Override
    public void sendToServer(ContributionCompletePayload payload) {
        ClientPlayNetworking.send(new FabricPayloadAdapters.ContributionCompleteWrapper(payload));
    }

    @Override
    public void sendToServer(ContributionOnlyRequestPayload payload) {
        ClientPlayNetworking.send(new FabricPayloadAdapters.ContributionOnlyRequestWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        ServerPlayNetworking.send(player, new FabricPayloadAdapters.SyncResponseWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        ServerPlayNetworking.send(player, new FabricPayloadAdapters.SyncProgressWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        ServerPlayNetworking.send(player, new FabricPayloadAdapters.ServerInstalledWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionRequestPayload payload) {
        ServerPlayNetworking.send(player, new FabricPayloadAdapters.ContributionRequestWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionResultPayload payload) {
        ServerPlayNetworking.send(player, new FabricPayloadAdapters.ContributionResultWrapper(payload));
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
    public void registerContributionRequestHandler(BiConsumer<ContributionRequestPayload, PayloadContext> handler) {
        this.contributionRequestHandler = handler;
    }

    @Override
    public void registerContributionDataHandler(BiConsumer<ContributionDataPayload, PayloadContext> handler) {
        this.contributionDataHandler = handler;
    }

    @Override
    public void registerContributionCompleteHandler(BiConsumer<ContributionCompletePayload, PayloadContext> handler) {
        this.contributionCompleteHandler = handler;
    }

    @Override
    public void registerContributionOnlyRequestHandler(
            BiConsumer<ContributionOnlyRequestPayload, PayloadContext> handler
    ) {
        this.contributionOnlyRequestHandler = handler;
    }

    @Override
    public void registerContributionResultHandler(BiConsumer<ContributionResultPayload, PayloadContext> handler) {
        this.contributionResultHandler = handler;
    }

    @Override
    public void enqueueWork(PayloadContext context, Runnable work) {
        // Fabric 的 context 已经提供了线程安全的执行方式
        Object platformCtx = context.getPlatformContext();
        if (platformCtx instanceof ServerPlayNetworking.Context serverCtx) {
            serverCtx.server().execute(work);
        } else if (platformCtx instanceof ClientPlayNetworking.Context clientCtx) {
            clientCtx.client().execute(work);
        }
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        Object platformCtx = context.getPlatformContext();
        if (platformCtx instanceof ServerPlayNetworking.Context serverCtx) {
            return serverCtx.player();
        }
        return null;
    }
}
