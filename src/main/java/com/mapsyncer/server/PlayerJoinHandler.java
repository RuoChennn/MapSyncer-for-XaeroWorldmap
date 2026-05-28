package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.MapSyncer;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.XaeroMapIntegrator;
import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.BlockColorMapper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

/**
 * 玩家登录事件处理器 - Fabric 版本
 *
 * 处理玩家加入/离开事件和服务器停止清理。
 */
public class PlayerJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandler.class);

    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;
    private static int cleanupTickCounter = 0;

    /**
     * 玩家登录事件处理
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        // 发送服务端已安装通知给客户端
        ServerPlayNetworking.send(player,
                new PacketHandler.ServerInstalledPayload(MapSyncer.VERSION));

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(server);
        }
    }

    /**
     * 玩家离开事件处理
     */
    public static void onPlayerLeave(ServerPlayer player) {
        ServerSyncHandler.onPlayerDisconnect(player.getUUID());
    }

    /**
     * 服务器停止事件处理
     */
    public static void onServerStopped() {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
        IncrementalUpdateHandler.resetInstance();

        MapPacketReceiver.clearReceivedChunks();
        MapPacketReceiver.resetServerStatus();
        XaeroMapIntegrator.clearRegionTracking();
        BlockColorMapper.clearCache();
        BlockPropertyResolver.clearCache();
        ClientHashManager.shutdown();

        ServerSyncHandler.cleanup();

        LOGGER.info("Singleton cache cleanup completed");
    }

    /**
     * 服务器Tick事件处理 - 定期清理异常断线玩家的残留状态
     */
    public static void onServerTick(MinecraftServer server) {
        cleanupTickCounter++;

        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        if (server == null) return;

        Set<UUID> onlinePlayerIds = new java.util.HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        ServerSyncHandler.cleanupOfflinePlayers(onlinePlayerIds);
    }
}
