package com.mapsyncer.network.impl;

import com.mapsyncer.network.FabricPayloadAdapters;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric 1.20.1 客户端网络处理器
 *
 * <p>此类引用客户端类 {@link ClientPlayNetworking}，
 * 仅在客户端环境中加载。在专用服务器上不会被加载。</p>
 *
 * <p>通过持有 {@link FabricNetworkHandler} 的引用，延迟读取 handler 字段，
 * 确保在 {@code MapPacketHandler.registerHandlers()} 设置 handler 之后仍能正确访问。</p>
 */
public class FabricClientNetworkHandler {

    private static FabricNetworkHandler networkHandler;

    /**
     * 初始化：保存对 FabricNetworkHandler 的引用并注册客户端接收器。
     *
     * <p>在 {@link com.mapsyncer.MapSyncerClient#onInitializeClient()} 中调用。</p>
     */
    public static void init(FabricNetworkHandler handler) {
        networkHandler = handler;
        registerReceivers();
    }

    private static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SYNC_RESPONSE_ID,
                (client, handler, buf, responseSender) -> {
                    if (networkHandler != null) {
                        var h = networkHandler.getSyncResponseHandler();
                        if (h != null) {
                            SyncResponsePayload payload = FabricPayloadAdapters.readSyncResponse(buf);
                            h.accept(payload, new PayloadContext(client));
                        }
                    }
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SYNC_PROGRESS_ID,
                (client, handler, buf, responseSender) -> {
                    if (networkHandler != null) {
                        var h = networkHandler.getSyncProgressHandler();
                        if (h != null) {
                            SyncProgressPayload payload = FabricPayloadAdapters.readSyncProgress(buf);
                            h.accept(payload, new PayloadContext(client));
                        }
                    }
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SERVER_INSTALLED_ID,
                (client, handler, buf, responseSender) -> {
                    if (networkHandler != null) {
                        var h = networkHandler.getServerInstalledHandler();
                        if (h != null) {
                            ServerInstalledPayload payload = FabricPayloadAdapters.readServerInstalled(buf);
                            h.accept(payload, new PayloadContext(client));
                        }
                    }
                }
        );
    }

    /**
     * 客户端发送数据包到服务端。
     */
    public static void sendToServer(ResourceLocation channelId, FriendlyByteBuf buf) {
        ClientPlayNetworking.send(channelId, buf);
    }

    /**
     * 在客户端主线程执行任务。
     */
    public static void enqueueClientWork(Runnable work) {
        net.minecraft.client.Minecraft.getInstance().execute(work);
    }
}
