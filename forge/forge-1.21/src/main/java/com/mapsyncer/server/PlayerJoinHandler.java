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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

/**
 * 玩家登录事件处理器 - 使用抽象网络层发送包
 */
@EventBusSubscriber(value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.GAME)
public class PlayerJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandler.class);
    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;
    private static int cleanupTickCounter = 0;

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // 使用 NetworkManager 发送服务端已安装通知
        NetworkManager.sendToPlayer(player, new ServerInstalledPayload(MapSyncer.VERSION));

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(server);
        }
    }

    /**
     * 玩家离开事件处理
     *
     * 中断正在进行的该玩家的地图同步任务。
     *
     * @param event 玩家离开事件
     */
    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        // 中断该玩家的任何正在进行的同步任务
        ServerSyncHandler.onPlayerDisconnect(event.getEntity().getUUID());
    }

    /**
     * 服务器停止事件处理
     *
     * 清理所有单例缓存实例，防止专用服务器重启时的内存泄漏。
     * 对于专用服务器，可能在不重启JVM的情况下重启服务器，
     * 因此必须正确清理缓存。
     *
     * @param event 服务器停止事件
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
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
     * <p>检查逻辑：遍历syncingPlayers集合，验证玩家是否仍然在线。
     * 如果玩家不在服务器玩家列表中，清理其所有状态。</p>
     *
     * @param event 服务器Tick后事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        cleanupTickCounter++;

        // 每60秒检查一次
        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        MinecraftServer server = event.getServer();
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
