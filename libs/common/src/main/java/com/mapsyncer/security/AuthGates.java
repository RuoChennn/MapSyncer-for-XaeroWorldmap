package com.mapsyncer.security;

/**
 * 业务层登录就绪门控入口。
 */
public final class AuthGates {

    private AuthGates() {}

    public static boolean isReadyForSync(Object serverPlayer) {
        return GateManager.auth().isReadyForSync(serverPlayer);
    }
}
