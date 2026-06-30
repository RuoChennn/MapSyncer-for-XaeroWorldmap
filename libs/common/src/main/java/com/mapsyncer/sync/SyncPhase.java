package com.mapsyncer.sync;

/**
 * 客户端同步会话阶段。
 */
public enum SyncPhase {
    /** 无进行中的同步 */
    IDLE,
    /** 正在接收并写入 region 数据 */
    RECEIVING,
    /** 同步数据已收齐，等待视距外 region 重载队列排空 */
    DRAINING_RELOAD
}
