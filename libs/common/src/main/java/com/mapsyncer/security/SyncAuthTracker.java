package com.mapsyncer.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端：记录玩家是否已通过登录验证、允许同步。
 */
public final class SyncAuthTracker {

    private static final Map<UUID, Boolean> readyByPlayer = new ConcurrentHashMap<>();

    private SyncAuthTracker() {}

    public static void markReady(UUID playerId) {
        if (playerId != null) {
            readyByPlayer.put(playerId, Boolean.TRUE);
        }
    }

    public static void markNotReady(UUID playerId) {
        if (playerId != null) {
            readyByPlayer.remove(playerId);
        }
    }

    public static boolean isReady(UUID playerId) {
        return playerId != null && readyByPlayer.containsKey(playerId);
    }

    public static void clear() {
        readyByPlayer.clear();
    }
}
