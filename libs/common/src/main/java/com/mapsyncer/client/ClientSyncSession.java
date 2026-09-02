package com.mapsyncer.client;

import com.mapsyncer.sync.SyncOutcome;
import com.mapsyncer.sync.SyncPhase;

/**
 * 客户端同步会话状态机（generation + phase + 超时）。
 * 所有 handler 入口通过 {@link #isCurrent(int)} 校验 generation。
 */
public final class ClientSyncSession {

    private static final ClientSyncSession INSTANCE = new ClientSyncSession();

    /** 陈旧同步超时（10 分钟） */
    public static final long STALE_TIMEOUT_MS = 10 * 60 * 1000L;

    private volatile int generation = 0;
    private volatile SyncPhase phase = SyncPhase.IDLE;
    private volatile long startedAt = 0;
    private volatile boolean reflectionFailed = false;
    private volatile SyncOutcome outcome = SyncOutcome.NONE;

    /** 服务端统一标识名（多入口复用同一地图缓存），由握手包下发 */
    private volatile String serverName = "";

    private ClientSyncSession() {}

    public static ClientSyncSession get() {
        return INSTANCE;
    }

    public int generation() {
        return generation;
    }

    public SyncPhase phase() {
        return phase;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean reflectionFailed() {
        return reflectionFailed;
    }

    public SyncOutcome outcome() {
        return outcome;
    }

    /** 获取服务端统一标识名（空串表示未配置，客户端应回退到 IP 命名） */
    public String getServerName() {
        return serverName;
    }

    /** 设置服务端统一标识名（由握手包处理器调用） */
    public void setServerName(String name) {
        this.serverName = name != null ? name : "";
    }

    public boolean isCurrent(int gen) {
        return gen == generation;
    }

    /** 会话是否占用同步通道（含视距外重载排空阶段；新 sync 是否可发起见 {@code MapPacketHandler.isSyncInProgress}） */
    public boolean isSessionActive() {
        return phase != SyncPhase.IDLE;
    }

    public boolean isStale() {
        if (phase != SyncPhase.RECEIVING || startedAt == 0) {
            return false;
        }
        return System.currentTimeMillis() - startedAt > STALE_TIMEOUT_MS;
    }

    /** 断线或全量清状态时作废已入队 handler */
    public void invalidate() {
        generation++;
        resetSession();
    }

    public void beginReceiving() {
        phase = SyncPhase.RECEIVING;
        startedAt = System.currentTimeMillis();
        reflectionFailed = false;
        outcome = SyncOutcome.NONE;
    }

    public void markReflectionFailed() {
        reflectionFailed = true;
        if (outcome == SyncOutcome.NONE || outcome == SyncOutcome.SUCCESS) {
            outcome = SyncOutcome.PARTIAL_SUCCESS;
        }
    }

    public void setOutcome(SyncOutcome newOutcome) {
        outcome = newOutcome;
    }

    public void beginDrainingReload() {
        phase = SyncPhase.DRAINING_RELOAD;
    }

    /** 视距外队列排空后回到 IDLE */
    public void completeSession() {
        phase = SyncPhase.IDLE;
        startedAt = 0;
        reflectionFailed = false;
    }

    private void resetSession() {
        phase = SyncPhase.IDLE;
        startedAt = 0;
        reflectionFailed = false;
        outcome = SyncOutcome.NONE;
    }
}
