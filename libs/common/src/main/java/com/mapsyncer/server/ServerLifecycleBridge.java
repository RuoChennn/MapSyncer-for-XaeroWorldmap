package com.mapsyncer.server;

import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.XaeroMapDataHandler;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.BlockColorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端生命周期统一入口 — 三 Loader 停服时必须执行相同清理清单。
 */
public final class ServerLifecycleBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerLifecycleBridge.class);

    private ServerLifecycleBridge() {}

    /**
     * 服务端停止时的完整清理（checklist）。
     */
    public static void onServerStopped() {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        ConversionOrchestrator.shutdownExecutor();

        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
        IncrementalUpdateHandler.resetInstance();

        MapPacketHandler.clearReceivedChunks();
        XaeroMapDataHandler.clearRegionTracking();
        BlockColorMapper.clearCache();
        BlockPropertyResolver.clearCache();
        PlatformManager.getPlatform().clearBlockPropertiesCache();
        ClientHashManager.shutdown();

        ServerSyncHandlerLogic.cleanup();

        LOGGER.info("Singleton cache cleanup completed");
    }
}
