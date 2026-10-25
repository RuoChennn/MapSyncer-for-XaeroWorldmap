package com.mapsyncer.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端 per-player 同步版本号与中断语义。
 * <ul>
 *   <li>{@link #interruptOldSyncThread} — 仅中断旧线程，保留 version</li>
 *   <li>{@link #finalizeSession} — 同步结束时清理 version 与线程引用</li>
 * </ul>
 */
public final class ServerSyncSession {

    private static final Map<UUID, Integer> playerSyncVersions = new ConcurrentHashMap<>();

    private ServerSyncSession() {}

    public static int currentVersion(UUID playerId) {
        return playerSyncVersions.getOrDefault(playerId, 0);
    }

    public static boolean isCurrent(UUID playerId, int syncVersion) {
        return playerSyncVersions.getOrDefault(playerId, 0) == syncVersion;
    }

    public static void assignVersion(UUID playerId, int syncVersion) {
        playerSyncVersions.put(playerId, syncVersion);
    }

    public static void removeVersion(UUID playerId) {
        playerSyncVersions.remove(playerId);
    }

    public static void clearAllVersions() {
        playerSyncVersions.clear();
    }

    /** Worker 结束时主线程可能仍有终态包排队；只能移除线程引用，不能作废会话。 */
    public static void removeCompletedThreads(Map<UUID, Thread> syncThreads) {
        syncThreads.forEach((id, thread) -> {
            if (!thread.isAlive()) syncThreads.remove(id, thread);
        });
    }

    /**
     * 新 sync 开始前中断旧线程（不触碰 version — 调用方随后 assignVersion）。
     */
    public static void interruptOldSyncThread(UUID playerId,
            Map<UUID, Thread> syncThreads,
            Runnable clearSpeedLimit) {
        Thread oldThread = syncThreads.get(playerId);
        if (oldThread != null && oldThread.isAlive()) {
            oldThread.interrupt();
            syncThreads.remove(playerId);
            clearSpeedLimit.run();
        }
    }

    /**
     * 同步正常/异常结束时移除 version（与线程清理由 ServerSyncHandlerLogic 协调）。
     */
    public static void finalizeSession(UUID playerId) {
        playerSyncVersions.remove(playerId);
    }
}
