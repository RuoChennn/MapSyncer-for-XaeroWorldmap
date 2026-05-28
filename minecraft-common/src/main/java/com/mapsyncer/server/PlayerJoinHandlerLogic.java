package com.mapsyncer.server;

import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.XaeroMapIntegrator;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.util.BlockColorMapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家登录事件处理逻辑。
 * 包含所有平台共享的业务逻辑，平台特定的事件注册由各平台薄包装器处理。
 */
public class PlayerJoinHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandlerLogic.class);

    /** 定期清理检查间隔（tick数）- 每60秒检查一次（1200 ticks） */
    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;

    /** tick计数器 */
    private static int cleanupTickCounter = 0;

    /**
     * 玩家登录事件处理。
     * 发送服务端已安装通知给客户端，并启动增量更新处理器。
     *
     * @param player 服务端玩家实例
     * @param server Minecraft服务器实例
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        // 发送服务端已安装通知给客户端（跨加载器兼容：无论客户端使用什么加载器都能接收）
        NetworkManager.sendToPlayer(player, new ServerInstalledPayload(getModVersion()));

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(server);
        }
    }

    /**
     * 玩家离开事件处理。
     * 中断正在进行的该玩家的地图同步任务。
     *
     * @param playerId 玩家UUID
     */
    public static void onPlayerLeave(UUID playerId) {
        ServerSyncHandlerLogic.onPlayerDisconnect(playerId);
    }

    /**
     * 服务器停止事件处理。
     * 清理所有单例缓存实例，防止专用服务器重启时的内存泄漏。
     */
    public static void onServerStopped() {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        // Shutdown conversion thread pool first
        ConversionOrchestrator.shutdownExecutor();

        // Reset singleton instances to release memory
        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
        IncrementalUpdateHandler.resetInstance();

        // Clear client-side static caches (for dedicated server restart scenario)
        MapPacketHandler.clearReceivedChunks();
        XaeroMapIntegrator.clearRegionTracking();
        BlockColorMapper.clearCache();
        BlockPropertyResolver.clearCache();
        ClientHashManager.shutdown();

        // Clear sync tracking data
        ServerSyncHandlerLogic.cleanup();

        LOGGER.info("Singleton cache cleanup completed");
    }

    /**
     * 服务器Tick事件处理。
     * 定期清理异常断线玩家的残留状态，防止内存泄漏。
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
        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        // 检查并清理离线玩家的残留状态
        ServerSyncHandlerLogic.cleanupOfflinePlayers(onlinePlayerIds);
    }

    /**
     * 获取模组版本号。
     * 优先使用 PlatformManager，回退到 MapSyncer.VERSION。
     */
    private static String getModVersion() {
        try {
            return com.mapsyncer.MapSyncer.VERSION;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
