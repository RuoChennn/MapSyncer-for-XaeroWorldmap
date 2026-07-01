package com.mapsyncer.client;

/**
 * 视距外 region 加载限速：每 N 个客户端 tick 向 Xaero 传入 1 个 region。
 * 由 ClientTick 驱动，计数器绑定游戏 tick（暂停时不推进）。
 */
public final class RegionLoadThrottle {

    private static int ticksUntilNextLoad = 0;

    private RegionLoadThrottle() {}

    public static void reset() {
        ticksUntilNextLoad = 0;
    }

    public static boolean isUnlimited(int intervalTicks) {
        return intervalTicks == -1;
    }

    public static boolean isViewOnly(int intervalTicks) {
        return intervalTicks == 0;
    }

    /**
     * @return 本 tick 是否应排放 1 个 region（interval=1 时每 tick 返回 true）
     */
    public static boolean shouldDrainOne(int intervalTicks) {
        if (intervalTicks <= 0) {
            return false;
        }
        if (ticksUntilNextLoad > 0) {
            ticksUntilNextLoad--;
            return false;
        }
        ticksUntilNextLoad = intervalTicks - 1;
        return true;
    }
}
