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
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

/**
 * Fabric 1.20.1 网络处理器实现（仅服务端安全）
 *
 * <p>此类不引用任何客户端类（ClientPlayNetworking 等），
 * 确保在专用服务器上类加载不会失败。</p>
 * <p>客户端接收器通过 {@link FabricClientNetworkHandler} 单独注册。</p>
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

    /**
     * 服务端 handler 上下文持有者
     */
    private record ServerPlayerContext(net.minecraft.server.MinecraftServer server, ServerPlayer player) {}

    @Override
    public void registerHandlers(Object event) {
        // 注册服务端接收器 (旧版 5 参数 API)
        ServerPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SYNC_REQUEST_ID,
                (server, player, handler, buf, responseSender) -> {
                    System.out.println("[MapSyncer DEBUG] Server received SYNC_REQUEST from " + player.getName().getString());
                    if (syncRequestHandler != null) {
                        SyncRequestPayload payload = FabricPayloadAdapters.readSyncRequest(buf);
                        System.out.println("[MapSyncer DEBUG] Parsed sync request with " + payload.clientMeta().size() + " entries");
                        syncRequestHandler.accept(payload, new PayloadContext(new ServerPlayerContext(server, player)));
                    } else {
                        System.out.println("[MapSyncer DEBUG] syncRequestHandler is NULL!");
                    }
                }
        );
        System.out.println("[MapSyncer DEBUG] ServerPlayNetworking.registerGlobalReceiver called for " + FabricPayloadAdapters.SYNC_REQUEST_ID);

        ServerPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.CONTRIBUTION_DATA_ID,
                (server, player, handler, buf, responseSender) -> {
                    if (contributionDataHandler != null) {
                        ContributionDataPayload payload = FabricPayloadAdapters.readContributionData(buf);
                        contributionDataHandler.accept(payload, new PayloadContext(new ServerPlayerContext(server, player)));
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.CONTRIBUTION_COMPLETE_ID,
                (server, player, handler, buf, responseSender) -> {
                    if (contributionCompleteHandler != null) {
                        ContributionCompletePayload payload = FabricPayloadAdapters.readContributionComplete(buf);
                        contributionCompleteHandler.accept(payload, new PayloadContext(new ServerPlayerContext(server, player)));
                    }
                }
        );

        ServerPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.CONTRIBUTION_ONLY_REQUEST_ID,
                (server, player, handler, buf, responseSender) -> {
                    if (contributionOnlyRequestHandler != null) {
                        ContributionOnlyRequestPayload payload = FabricPayloadAdapters.readContributionOnlyRequest(buf);
                        contributionOnlyRequestHandler.accept(payload, new PayloadContext(new ServerPlayerContext(server, player)));
                    }
                }
        );
    }

    /**
     * 注册客户端接收器。
     *
     * <p>此方法委托给 {@link FabricClientNetworkHandler}，避免在此类中引用客户端类。
     * 必须在客户端环境中调用。</p>
     */
    public void registerClientHandlers() {
        FabricClientNetworkHandler.init(this);
    }

    // ===== Handler getters（供 FabricClientNetworkHandler 延迟读取） =====

    public BiConsumer<SyncResponsePayload, PayloadContext> getSyncResponseHandler() {
        return syncResponseHandler;
    }

    public BiConsumer<SyncProgressPayload, PayloadContext> getSyncProgressHandler() {
        return syncProgressHandler;
    }

    public BiConsumer<ServerInstalledPayload, PayloadContext> getServerInstalledHandler() {
        return serverInstalledHandler;
    }

    public BiConsumer<ContributionRequestPayload, PayloadContext> getContributionRequestHandler() {
        return contributionRequestHandler;
    }

    public BiConsumer<ContributionResultPayload, PayloadContext> getContributionResultHandler() {
        return contributionResultHandler;
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeSyncRequest(buf, payload);
        // 使用反射避免编译时依赖 ClientPlayNetworking
        FabricClientNetworkHandler.sendToServer(FabricPayloadAdapters.SYNC_REQUEST_ID, buf);
    }

    @Override
    public void sendToServer(ContributionDataPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeContributionData(buf, payload);
        FabricClientNetworkHandler.sendToServer(FabricPayloadAdapters.CONTRIBUTION_DATA_ID, buf);
    }

    @Override
    public void sendToServer(ContributionCompletePayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeContributionComplete(buf, payload);
        FabricClientNetworkHandler.sendToServer(FabricPayloadAdapters.CONTRIBUTION_COMPLETE_ID, buf);
    }

    @Override
    public void sendToServer(ContributionOnlyRequestPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeContributionOnlyRequest(buf, payload);
        FabricClientNetworkHandler.sendToServer(FabricPayloadAdapters.CONTRIBUTION_ONLY_REQUEST_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeSyncResponse(buf, payload);
        ServerPlayNetworking.send(player, FabricPayloadAdapters.SYNC_RESPONSE_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeSyncProgress(buf, payload);
        ServerPlayNetworking.send(player, FabricPayloadAdapters.SYNC_PROGRESS_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeServerInstalled(buf, payload);
        ServerPlayNetworking.send(player, FabricPayloadAdapters.SERVER_INSTALLED_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionRequestPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeContributionRequest(buf, payload);
        ServerPlayNetworking.send(player, FabricPayloadAdapters.CONTRIBUTION_REQUEST_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionResultPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeContributionResult(buf, payload);
        ServerPlayNetworking.send(player, FabricPayloadAdapters.CONTRIBUTION_RESULT_ID, buf);
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
        Object platformCtx = context.getPlatformContext();
        if (platformCtx instanceof ServerPlayerContext spc) {
            spc.server().execute(work);
        } else {
            // 客户端上下文：委托给客户端处理器（避免引用客户端类）
            FabricClientNetworkHandler.enqueueClientWork(work);
        }
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        Object platformCtx = context.getPlatformContext();
        if (platformCtx instanceof ServerPlayerContext spc) {
            return spc.player();
        }
        return null;
    }
}
