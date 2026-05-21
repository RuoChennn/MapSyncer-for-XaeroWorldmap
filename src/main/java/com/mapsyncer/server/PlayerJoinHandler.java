package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.MapSyncer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家登录事件处理器 - 处理玩家加入/离开事件和服务器停止清理
 *
 * 功能：
 * - 玩家加入时启动增量更新处理器（如果未运行且配置启用）
 * - 玩家离开时中断该玩家的同步任务
 * - 服务器停止时清理所有单例缓存，防止内存泄漏
 */
@EventBusSubscriber(value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.GAME)
public class PlayerJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandler.class);

    /**
     * 玩家登录事件处理
     *
     * 当玩家登录时，如果增量更新处理器未运行且配置未禁用，
     * 则启动增量更新处理器开始定时扫描。
     *
     * @param event 玩家登录事件
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // 发送服务端已安装通知给客户端
        PacketDistributor.sendToPlayer(player,
                new PacketHandler.ServerInstalledPayload(MapSyncer.VERSION));

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

        // Clear sync tracking data
        ServerSyncHandler.cleanup();

        LOGGER.info("Singleton cache cleanup completed");
    }
}
