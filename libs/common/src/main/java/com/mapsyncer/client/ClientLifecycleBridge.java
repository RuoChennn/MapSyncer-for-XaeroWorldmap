package com.mapsyncer.client;

import com.mapsyncer.platform.XaeroReflectionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端生命周期统一入口 — 三 Loader 断线时必须执行相同清理清单。
 */
public final class ClientLifecycleBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientLifecycleBridge.class);

    private ClientLifecycleBridge() {}

    /**
     * 客户端断开连接时的完整清理（checklist）。
     * <ol>
     *   <li>取消自动同步定时器</li>
     *   <li>重置服务端安装探测状态</li>
     *   <li>作废同步会话并清缓冲</li>
     *   <li>释放 Xaero 反射缓存</li>
     *   <li>清 region 追踪与哈希线程池</li>
     *   <li>重置客户端时间戳缓存单例</li>
     * </ol>
     */
    public static void onClientDisconnect() {
        AutoSyncManager.cancel();
        MapPacketHandler.resetServerStatus();
        MapPacketHandler.clearSyncData();
        XaeroReflectionHelper.clearCache();
        XaeroMapDataHandler.clearRegionTracking();
        ClientHashManager.shutdown();
        ClientSyncWriteQueue.shutdown();
        RegionPipelineTracker.endSession();
        ClientTimestampCache.resetInstance();
        LOGGER.info("Client disconnected, all resources cleaned up");
    }
}
