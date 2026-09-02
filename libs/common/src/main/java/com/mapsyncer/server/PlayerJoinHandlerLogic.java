package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
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

    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;

    private static int cleanupTickCounter = 0;

    /**
     * 发送服务端已安装通知给客户端，并启动增量更新处理器。
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        // 发送 ServerInstalled 通知（跨加载器兼容：无论客户端使用什么加载器都能接收）
        long lastGenTime = GenerationCache.getInstance(ConversionOrchestrator.getCacheDir()).getLastGenerationTime();
        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        int intervalTicks = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
        int autoInterval = AutoSyncConfig.computeInterval(mode, intervalTicks);
        String serverName = PlatformManager.getServerName();
        NetworkManager.sendToPlayer(player,
            new ServerInstalledPayload(getModVersion(), lastGenTime, autoInterval, mode, intervalTicks, serverName));

        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(server);
        }
    }

    /**
     * 中断正在进行的该玩家的地图同步任务。
     */
    public static void onPlayerLeave(UUID playerId) {
        ServerSyncHandlerLogic.onPlayerDisconnect(playerId);
    }

    /**
     * 清理所有单例缓存实例，防止专用服务器重启时的内存泄漏。
     */
    public static void onServerStopped() {
        ServerLifecycleBridge.onServerStopped();
    }

    /**
     * 定期清理异常断线玩家的残留状态，防止内存泄漏。
     */
    public static void onServerTick(MinecraftServer server) {
        cleanupTickCounter++;

        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        if (server == null) return;

        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        ServerSyncHandlerLogic.cleanupOfflinePlayers(onlinePlayerIds);
    }

    private static String getModVersion() {
        try {
            return com.mapsyncer.MapSyncer.VERSION;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
