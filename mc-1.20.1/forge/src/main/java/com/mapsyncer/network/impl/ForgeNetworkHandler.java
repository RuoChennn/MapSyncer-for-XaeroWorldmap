package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.ForgePayloadAdapters;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionCompleteMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionDataMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionRequestMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionResultMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncRequestMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncResponseMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncProgressMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeServerInstalledMessage;
import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Forge 网络处理器实现（传统 SimpleNetworkWrapper 方式）
 */
public class ForgeNetworkHandler implements NetworkHandler<ServerPlayer, Object> {

    private static final String PROTOCOL_VERSION = "1";
    private static SimpleChannel CHANNEL;

    /** 已确认安装 MapSyncer 的客户端 — 仅对这些玩家发送自定义 payload */
    static final Set<UUID> confirmedPlayers = ConcurrentHashMap.newKeySet();

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;
    private BiConsumer<ContributionRequestPayload, PayloadContext> contributionRequestHandler;
    private BiConsumer<ContributionDataPayload, PayloadContext> contributionDataHandler;
    private BiConsumer<ContributionCompletePayload, PayloadContext> contributionCompleteHandler;
    private BiConsumer<ContributionResultPayload, PayloadContext> contributionResultHandler;

    private boolean registered = false;

    public void init() {
        if (CHANNEL != null) return;

        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(MapSyncer.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        CHANNEL.registerMessage(0, ForgeSyncRequestMessage.class,
                ForgeSyncRequestMessage::encode,
                ForgeSyncRequestMessage::decode,
                this::handleSyncRequest);

        CHANNEL.registerMessage(1, ForgeSyncResponseMessage.class,
                ForgeSyncResponseMessage::encode,
                ForgeSyncResponseMessage::decode,
                this::handleSyncResponse);

        CHANNEL.registerMessage(2, ForgeSyncProgressMessage.class,
                ForgeSyncProgressMessage::encode,
                ForgeSyncProgressMessage::decode,
                this::handleSyncProgress);

        CHANNEL.registerMessage(3, ForgeServerInstalledMessage.class,
                ForgeServerInstalledMessage::encode,
                ForgeServerInstalledMessage::decode,
                this::handleServerInstalled);

        CHANNEL.registerMessage(4, ForgeContributionRequestMessage.class,
                ForgeContributionRequestMessage::encode,
                ForgeContributionRequestMessage::decode,
                this::handleContributionRequest,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(5, ForgeContributionDataMessage.class,
                ForgeContributionDataMessage::encode,
                ForgeContributionDataMessage::decode,
                this::handleContributionData,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(6, ForgeContributionCompleteMessage.class,
                ForgeContributionCompleteMessage::encode,
                ForgeContributionCompleteMessage::decode,
                this::handleContributionComplete,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(7, ForgeContributionResultMessage.class,
                ForgeContributionResultMessage::encode,
                ForgeContributionResultMessage::decode,
                this::handleContributionResult,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private void handleSyncRequest(ForgeSyncRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            confirmPlayer(sender.getUUID());
        }
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

    private void handleContributionRequest(ForgeContributionRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (contributionRequestHandler != null) {
            contributionRequestHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    private void handleContributionData(ForgeContributionDataMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            confirmPlayer(sender.getUUID());
        }
        if (contributionDataHandler != null) {
            contributionDataHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    private void handleContributionComplete(ForgeContributionCompleteMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            confirmPlayer(sender.getUUID());
        }
        if (contributionCompleteHandler != null) {
            contributionCompleteHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    private void handleContributionResult(ForgeContributionResultMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (contributionResultHandler != null) {
            contributionResultHandler.accept(msg.getData(), new PayloadContext(ctx));
        }
    }

    @Override
    public void registerHandlers(Object event) {
        if (registered) return;
        registered = true;
        init();
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        CHANNEL.sendToServer(new ForgeSyncRequestMessage(payload));
    }

    @Override
    public void sendToServer(ContributionDataPayload payload) {
        CHANNEL.sendToServer(new ForgeContributionDataMessage(payload));
    }

    @Override
    public void sendToServer(ContributionCompletePayload payload) {
        CHANNEL.sendToServer(new ForgeContributionCompleteMessage(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ForgeSyncResponseMessage(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ForgeSyncProgressMessage(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ForgeServerInstalledMessage(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionRequestPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ForgeContributionRequestMessage(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionResultPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ForgeContributionResultMessage(payload));
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
    public void registerContributionResultHandler(BiConsumer<ContributionResultPayload, PayloadContext> handler) {
        this.contributionResultHandler = handler;
    }

    @Override
    public void enqueueWork(PayloadContext context, Runnable work) {
        Supplier<NetworkEvent.Context> forgeCtx = (Supplier<NetworkEvent.Context>) context.getPlatformContext();
        forgeCtx.get().enqueueWork(work);
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        Supplier<NetworkEvent.Context> forgeCtx = (Supplier<NetworkEvent.Context>) context.getPlatformContext();
        return forgeCtx.get().getSender();
    }

    /** 确认客户端安装了 MapSyncer（收到客户端请求时调用） */
    public static void confirmPlayer(UUID playerId) {
        confirmedPlayers.add(playerId);
    }

    /** 玩家断线时清理确认状态 */
    public static void onPlayerDisconnect(UUID playerId) {
        confirmedPlayers.remove(playerId);
    }
}
