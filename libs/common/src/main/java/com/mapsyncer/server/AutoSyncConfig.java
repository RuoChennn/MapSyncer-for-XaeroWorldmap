package com.mapsyncer.server;

import com.mapsyncer.platform.UpdateMode;

/**
 * 根据服务端增量更新策略自动计算客户端自动同步间隔。
 */
public class AutoSyncConfig {

    /**
     * 根据服务端增量更新策略自动计算自动同步间隔（分钟）。
     *
     * DISABLED  → 0  (禁用自动同步)
     * TICK      → max(60, intervalTicks / 20 / 60)
     * SCHEDULED → 1440 (24小时)
     */
    public static int computeInterval(UpdateMode mode, int intervalTicks) {
        switch (mode) {
            case DISABLED:  return 0;
            case TICK:      return Math.max(60, intervalTicks / 20 / 60);
            case SCHEDULED: return 1440;
            default:        return 0;
        }
    }
}
