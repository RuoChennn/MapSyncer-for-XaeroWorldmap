package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.ForgePayloadAdapters;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionCompleteMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionDataMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionOnlyRequestMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionRequestMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeContributionResultMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncRequestMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncResponseMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeSyncProgressMessage;
import com.mapsyncer.network.ForgePayloadAdapters.ForgeServerInstalledMessage;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Forge 1.21 网络处理器实现
 */
public class ForgeNetworkHandler implements NetworkHandler<ServerPlayer, Object> {

    private static final SimpleChannel CHANNEL = ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath(MapSyncer.MOD_ID, "main"))
        .networkProtocolVersion(1)
        .simpleChannel();

    private static volatile boolean initialized = false;

    /** 已确认安装 MapSyncer 的客户端 — 仅对这些玩家发送自定义 payload */
    static final Set<UUID> confirmedPlayers = ConcurrentHashMap.newKeySet();

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;
    private BiConsumer<ContributionRequestPayload, PayloadContext> contributionRequestHandler;
    private BiConsumer<ContributionDataPayload, PayloadContext> contributionDataHandler;
    private BiConsumer<ContributionCompletePayload, PayloadContext> contributionCompleteHandler;
    private BiConsumer<ContributionOnlyRequestPayload, PayloadContext> contributionOnlyRequestHandler;
    private BiConsumer<ContributionResultPayload, PayloadContext> contributionResultHandler;

    public void init() {
        // 同步请求（客户端 -> 服务端）：收到即确认该客户端安装了 MapSyncer
        CHANNEL.messageBuilder(ForgeSyncRequestMessage.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ForgeSyncRequestMessage::encode)
            .decoder(ForgeSyncRequestMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (ctx.getSender() != null) {
                    confirmPlayer(ctx.getSender().getUUID());
                }
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

        // 贡献请求（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgeContributionRequestMessage.class, 4, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgeContributionRequestMessage::encode)
            .decoder(ForgeContributionRequestMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (contributionRequestHandler != null) {
                    contributionRequestHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();

        // 贡献数据（客户端 -> 服务端）：收到即确认该客户端安装了 MapSyncer
        CHANNEL.messageBuilder(ForgeContributionDataMessage.class, 5, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ForgeContributionDataMessage::encode)
            .decoder(ForgeContributionDataMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (ctx.getSender() != null) {
                    confirmPlayer(ctx.getSender().getUUID());
                }
                if (contributionDataHandler != null) {
                    contributionDataHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();

        // 贡献完成（客户端 -> 服务端）：收到即确认该客户端安装了 MapSyncer
        CHANNEL.messageBuilder(ForgeContributionCompleteMessage.class, 6, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ForgeContributionCompleteMessage::encode)
            .decoder(ForgeContributionCompleteMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (ctx.getSender() != null) {
                    confirmPlayer(ctx.getSender().getUUID());
                }
                if (contributionCompleteHandler != null) {
                    contributionCompleteHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();

        // 贡献结果（服务端 -> 客户端）
        CHANNEL.messageBuilder(ForgeContributionResultMessage.class, 7, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ForgeContributionResultMessage::encode)
            .decoder(ForgeContributionResultMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (contributionResultHandler != null) {
                    contributionResultHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();

        // 仅贡献请求（客户端 -> 服务端）：收到即确认该客户端安装了 MapSyncer
        CHANNEL.messageBuilder(ForgeContributionOnlyRequestMessage.class, 8, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ForgeContributionOnlyRequestMessage::encode)
            .decoder(ForgeContributionOnlyRequestMessage::decode)
            .consumerMainThread((msg, ctx) -> {
                if (ctx.getSender() != null) {
                    confirmPlayer(ctx.getSender().getUUID());
                }
                if (contributionOnlyRequestHandler != null) {
                    contributionOnlyRequestHandler.accept(msg.getData(), new PayloadContext(ctx));
                }
            })
            .add();
    }

    @Override
    public void registerHandlers(Object event) {
        if (initialized) return;
        initialized = true;
        init();
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        CHANNEL.send(new ForgeSyncRequestMessage(payload), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToServer(ContributionDataPayload payload) {
        CHANNEL.send(new ForgeContributionDataMessage(payload), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToServer(ContributionCompletePayload payload) {
        CHANNEL.send(new ForgeContributionCompleteMessage(payload), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToServer(ContributionOnlyRequestPayload payload) {
        CHANNEL.send(new ForgeContributionOnlyRequestMessage(payload), PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(new ForgeSyncResponseMessage(payload), PacketDistributor.PLAYER.with(player));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(new ForgeSyncProgressMessage(payload), PacketDistributor.PLAYER.with(player));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(new ForgeServerInstalledMessage(payload), PacketDistributor.PLAYER.with(player));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionRequestPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(new ForgeContributionRequestMessage(payload), PacketDistributor.PLAYER.with(player));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ContributionResultPayload payload) {
        if (!confirmedPlayers.contains(player.getUUID())) return;
        CHANNEL.send(new ForgeContributionResultMessage(payload), PacketDistributor.PLAYER.with(player));
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

    /** 确认客户端安装了 MapSyncer（收到客户端请求时调用） */
    public static void confirmPlayer(UUID playerId) {
        confirmedPlayers.add(playerId);
    }

    /** 玩家断线时清理确认状态 */
    public static void onPlayerDisconnect(UUID playerId) {
        confirmedPlayers.remove(playerId);
    }
}
