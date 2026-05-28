package com.mapsyncer.platform;

/**
 * 增量更新模式
 *
 * 定义服务端地图增量更新的触发方式
 */
public enum UpdateMode {
    /**
     * 禁用增量更新
     */
    DISABLED,

    /**
     * Tick 周期模式（按固定 tick 间隔更新）
     */
    TICK,

    /**
     * 每日定时模式（在指定时间更新）
     */
    SCHEDULED
}