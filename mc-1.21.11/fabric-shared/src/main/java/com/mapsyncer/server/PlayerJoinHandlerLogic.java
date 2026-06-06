package com.mapsyncer.server;

import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.XaeroMapDataHandler;
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
 * 鐜╁鐧诲綍浜嬩欢澶勭悊閫昏緫銆?
 * 鍖呭惈鎵€鏈夊钩鍙板叡浜殑涓氬姟閫昏緫锛屽钩鍙扮壒瀹氱殑浜嬩欢娉ㄥ唽鐢卞悇骞冲彴钖勫寘瑁呭櫒澶勭悊銆?
 */
public class PlayerJoinHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandlerLogic.class);

    /** 瀹氭湡娓呯悊妫€鏌ラ棿闅旓紙tick鏁帮級- 姣?0绉掓鏌ヤ竴娆★紙1200 ticks锛?*/
    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;

    /** tick璁℃暟鍣?*/
    private static int cleanupTickCounter = 0;

    /**
     * 鐜╁鐧诲綍浜嬩欢澶勭悊銆?
     * 鍙戦€佹湇鍔＄宸插畨瑁呴€氱煡缁欏鎴风锛屽苟鍚姩澧為噺鏇存柊澶勭悊鍣ㄣ€?
     *
     * @param player 鏈嶅姟绔帺瀹跺疄渚?
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        // 鍙戦€佹湇鍔＄宸插畨瑁呴€氱煡缁欏鎴风锛堣法鍔犺浇鍣ㄥ吋瀹癸細鏃犺瀹㈡埛绔娇鐢ㄤ粈涔堝姞杞藉櫒閮借兘鎺ユ敹锛?
                long lastGenTime = GenerationCache.getInstance(ConversionOrchestrator.CACHE_DIR).getLastGenerationTime();
        int autoInterval = AutoSyncConfig.computeInterval(
            PlatformManager.getPlatform().getIncrementalUpdateMode(),
            PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks());
        NetworkManager.sendToPlayer(player,
            new ServerInstalledPayload(getModVersion(), lastGenTime, autoInterval));

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(server);
        }
    }

    /**
     * 鐜╁绂诲紑浜嬩欢澶勭悊銆?
     * 涓柇姝ｅ湪杩涜鐨勮鐜╁鐨勫湴鍥惧悓姝ヤ换鍔°€?
     *
     * @param playerId 鐜╁UUID
     */
    public static void onPlayerLeave(UUID playerId) {
        ServerSyncHandlerLogic.onPlayerDisconnect(playerId);
    }

    /**
     * 鏈嶅姟鍣ㄥ仠姝簨浠跺鐞嗐€?
     * 娓呯悊鎵€鏈夊崟渚嬬紦瀛樺疄渚嬶紝闃叉涓撶敤鏈嶅姟鍣ㄩ噸鍚椂鐨勫唴瀛樻硠婕忋€?
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
        XaeroMapDataHandler.clearRegionTracking();
        BlockColorMapper.clearCache();
        BlockPropertyResolver.clearCache();
        // 清理平台级方块属性缓存
        PlatformManager.getPlatform().clearBlockPropertiesCache();
        ClientHashManager.shutdown();

        // Clear sync tracking data
        ServerSyncHandlerLogic.cleanup();

        LOGGER.info("Singleton cache cleanup completed");
    }

    /**
     * 鏈嶅姟鍣═ick浜嬩欢澶勭悊銆?
     * 瀹氭湡娓呯悊寮傚父鏂嚎鐜╁鐨勬畫鐣欑姸鎬侊紝闃叉鍐呭瓨娉勬紡銆?
     *
     * @param server Minecraft鏈嶅姟鍣ㄥ疄渚?
     */
    public static void onServerTick(MinecraftServer server) {
        cleanupTickCounter++;

        // 姣?0绉掓鏌ヤ竴娆?
        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        if (server == null) return;

        // 鑾峰彇褰撳墠鍦ㄧ嚎鐜╁鐨刄UID闆嗗悎
        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        // 妫€鏌ュ苟娓呯悊绂荤嚎鐜╁鐨勬畫鐣欑姸鎬?
        ServerSyncHandlerLogic.cleanupOfflinePlayers(onlinePlayerIds);
    }

    /**
     * 鑾峰彇妯＄粍鐗堟湰鍙枫€?
     * 浼樺厛浣跨敤 PlatformManager锛屽洖閫€鍒?MapSyncer.VERSION銆?
     */
    private static String getModVersion() {
        try {
            return com.mapsyncer.MapSyncer.VERSION;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
