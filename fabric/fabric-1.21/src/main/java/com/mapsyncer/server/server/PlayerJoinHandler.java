package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.MapSyncer;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.XaeroMapIntegrator;
import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.util.BlockColorMapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

/**
 * 玩家登录事件处理器 - 处理玩家加入/离开事件和服务器停止清理
 *
 * 注意：Fabric 版本的事件注册在 MapSyncer 主类中使用 ServerPlayConnectionEvents。
 */
public class PlayerJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandler.class);

    /** 定期清理检查间隔（tick数）- 每60秒检查一次（1200 ticks） */
    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;

    /** tick计数器 */
    private static int cleanupTickCounter = 0;

    /**
     * 玩家登录事件处理
     *
     * 当玩家登录时，如果增量更新处理器未运行且配置未禁用，
     * 则启动增量更新处理器开始定时扫描。
     *
     * @param player 服务端玩家实例
     * @param server Minecraft服务器实例
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        // 发送服务端已安装通知给客户端
        NetworkManager.sendToPlayer(player,
                new ServerInstalledPayload(MapSyncer.VERSION));

        UpdateMode mode = ModConfig.SERVER().getIncrementalUpdateMode();
        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(server);
        }
    }

    /**
     * 玩家离开事件处理
     *
     * 中断正在进行的该玩家的地图同步任务。
     *
     * @param playerId 玩家UUID
     */
    public static void onPlayerLeave(UUID playerId) {
        ServerSyncHandler.onPlayerDisconnect(playerId);
    }

    /**
     * 服务器停止事件处理
     *
     * 清理所有单例缓存实例，防止专用服务器重启时的内存泄漏。
     */
    public static void onServerStopped() {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        // Reset singleton instances to release memory
        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
        IncrementalUpdateHandler.resetInstance();

        // Clear client-side static caches (for dedicated server restart scenario)
        MapPacketReceiver.clearReceivedChunks();
        MapPacketReceiver.resetServerStatus();
        XaeroMapIntegrator.clearRegionTracking();
        BlockColorMapper.clearCache();
        BlockPropertyResolver.clearCache();
        ClientHashManager.shutdown();

        // Clear sync tracking data
        ServerSyncHandler.cleanup();

        LOGGER.info("Singleton cache cleanup completed");
    }

    /**
     * 服务器Tick事件处理 - 定期清理异常断线玩家的残留状态
     *
     * <p>玩家异常断线（网络中断、客户端崩溃等）时，onPlayerLeave事件可能不会触发，
     * 导致syncingPlayers等Map中残留无效的玩家状态。定期检查并清理这些状态，
     * 防止内存泄漏。</p>
     *
     * @param server Minecraft服务器实例
     */
    public static void onServerTick(MinecraftServer server) {
        cleanupTickCounter++;

        // 每60秒检查一次
        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        if (server == null) return;

        // 获取当前在线玩家的UUID集合
        Set<UUID> onlinePlayerIds = new java.util.HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        // 检查并清理离线玩家的残留状态
        ServerSyncHandler.cleanupOfflinePlayers(onlinePlayerIds);
    }
}