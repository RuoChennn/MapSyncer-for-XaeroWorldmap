package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.platform.UpdateMode;

/**
 * 服务端已安装通知包 - 平台无关版本
 *
 * 服务端在玩家加入时发送，告知客户端服务端已安装 MapSyncer 及增量更新策略。
 *
 * @param version                         服务端模组版本号
 * @param lastGenerationTimestamp         服务端地图缓存最后生成时间（秒）
 * @param autoSyncIntervalMinutes         自动同步间隔（分钟，用于状态展示与进服冷却）
 * @param updateMode                      增量更新模式
 * @param incrementalUpdateIntervalTicks  TICK 模式下的生成周期（tick 数）
 */
public record ServerInstalledPayload(
        String version,
        long lastGenerationTimestamp,
        int autoSyncIntervalMinutes,
        UpdateMode updateMode,
        int incrementalUpdateIntervalTicks,
        String serverName) {

    public static final String ID = NetworkHandler.SERVER_INSTALLED_ID;

    /** 兼容旧构造：未指定模式时视为 DISABLED，serverName 为空 */
    public ServerInstalledPayload(String version, long lastGenerationTimestamp, int autoSyncIntervalMinutes) {
        this(version, lastGenerationTimestamp, autoSyncIntervalMinutes, UpdateMode.DISABLED, 0, "");
    }

    /** 兼容旧构造：未指定 serverName 时视为空 */
    public ServerInstalledPayload(String version, long lastGenerationTimestamp, int autoSyncIntervalMinutes,
                                   UpdateMode updateMode, int incrementalUpdateIntervalTicks) {
        this(version, lastGenerationTimestamp, autoSyncIntervalMinutes, updateMode, incrementalUpdateIntervalTicks, "");
    }
}
