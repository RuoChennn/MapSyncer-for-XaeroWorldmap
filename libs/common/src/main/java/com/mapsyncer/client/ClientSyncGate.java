package com.mapsyncer.client;

/**
 * 客户端：是否已收到服务端 {@code SyncAllowedPayload}，允许自动/探针同步。
 */
public final class ClientSyncGate {

    private static volatile boolean syncAllowed;
    private static volatile int autoSyncDelaySeconds;

    private ClientSyncGate() {}

    public static void grant(int delaySeconds) {
        syncAllowed = true;
        autoSyncDelaySeconds = Math.max(0, delaySeconds);
    }

    public static void reset() {
        syncAllowed = false;
        autoSyncDelaySeconds = 0;
    }

    public static boolean isSyncAllowed() {
        return syncAllowed;
    }

    public static int getAutoSyncDelaySeconds() {
        return autoSyncDelaySeconds;
    }
}
