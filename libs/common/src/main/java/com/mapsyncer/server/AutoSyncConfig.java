package com.mapsyncer.server;

import com.mapsyncer.platform.UpdateMode;

/**
 * 根据服务端增量更新策略自动计算客户端自动同步间隔。
 */
public final class AutoSyncConfig {

    /** TICK 模式默认间隔：5 分钟 */
    public static final int DEFAULT_TICK_INTERVAL = 6000;

    /** TICK 模式最小间隔：2 分钟 */
    public static final int MIN_TICK_INTERVAL = 2400;

    /** TICK 模式最大间隔：60 分钟 */
    public static final int MAX_TICK_INTERVAL = 72000;

    private AutoSyncConfig() {}

    /**
     * 根据服务端增量更新策略自动计算自动同步间隔（分钟）。
     *
     * DISABLED  → 0
     * TICK      → intervalTicks / 20 / 60
     * SCHEDULED → 1440 (24小时)
     */
    public static int computeInterval(UpdateMode mode, int intervalTicks) {
        switch (mode) {
            case DISABLED:  return 0;
            case TICK:      return ticksToMinutes(intervalTicks);
            case SCHEDULED: return 1440;
            default:        return 0;
        }
    }

    public static int ticksToMinutes(int intervalTicks) {
        return Math.max(1, intervalTicks / 20 / 60);
    }

    /** 将服务端 tick 间隔换算为客户端计时器毫秒数（20 TPS）。 */
    public static long ticksToPeriodMs(int intervalTicks) {
        return Math.max(1L, intervalTicks) * 50L;
    }
}
