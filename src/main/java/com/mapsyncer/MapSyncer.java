package com.mapsyncer;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.PlayerJoinHandler;
import com.mapsyncer.server.ServerSyncHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * MapSyncer模组的主类 - Fabric 版本
 *
 * 该模组用于将服务端的Xaero's World Map地图数据同步到客户端，
 * 支持增量更新和全量同步两种模式。
 */
public class MapSyncer implements ModInitializer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    @Override
    public void onInitialize() {
        // 获取版本号
        VERSION = getModVersion();

        // 初始化配置
        Path configDir = Path.of("config");
        ModConfig.init(configDir);

        // 注册网络包类型（服务端方向）
        registerServerPackets();

        // 注册服务端事件
        registerServerEvents();

        LOGGER.info("MapSyncer initialized (server mode), version: {}", VERSION);
    }

    /**
     * 注册服务端方向的网络包
     */
    private void registerServerPackets() {
        // 注册客户端->服务端的包类型
        PayloadTypeRegistry.playC2S().register(
                PacketHandler.SyncRequestPayload.ID,
                PacketHandler.SyncRequestPayload.CODEC
        );

        // 注册服务端->客户端的包类型
        PayloadTypeRegistry.playS2C().register(
                PacketHandler.SyncResponsePayload.ID,
                PacketHandler.SyncResponsePayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                PacketHandler.SyncProgressPayload.ID,
                PacketHandler.SyncProgressPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                PacketHandler.ServerInstalledPayload.ID,
                PacketHandler.ServerInstalledPayload.CODEC
        );

        // 注册服务端接收处理
        ServerPlayNetworking.registerGlobalReceiver(
                PacketHandler.SyncRequestPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        ServerSyncHandler.handleSyncRequest(payload, context.player());
                    });
                }
        );
    }

    /**
     * 注册服务端事件
     */
    private void registerServerEvents() {
        // 服务器启动事件
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            DimensionRegistry.registerAllDimensions(server);
            UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
            if (mode != UpdateMode.DISABLED) {
                IncrementalUpdateHandler.getInstance().start(server);
            }
        });

        // 服务器停止事件
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            IncrementalUpdateHandler.getInstance().stop();
        });

        // 服务器完全停止后清理
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PlayerJoinHandler.onServerStopped();
        });

        // 命令注册
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CacheGenerateCommand.register(dispatcher);
        });

        // 玩家加入事件
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerJoinHandler.onPlayerJoin(handler.player, server);
        });

        // 玩家离开事件
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PlayerJoinHandler.onPlayerLeave(handler.player);
        });

        // Tick 事件
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            IncrementalUpdateHandler.onServerTick(server);
            PlayerJoinHandler.onServerTick(server);
        });
    }

    /**
     * 获取模组版本号
     */
    private static String getModVersion() {
        try {
            var modContainer = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer(MOD_ID);
            if (modContainer.isPresent()) {
                return modContainer.get().getMetadata().getVersion().getFriendlyString();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get mod version", e);
        }
        return "unknown";
    }
}
