package com.mapsyncer.security;

import com.mapsyncer.platform.PlatformManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 {@link PolicySettings} 与原版行为的登录就绪检查。
 *
 * <p>后续可在此链上追加 EasyAuth / AuthMe 等 mod 桥接。</p>
 */
public final class ConfigurableAuthReadiness implements AuthReadiness {

    private final Supplier<PolicySettings> settings;

    public ConfigurableAuthReadiness(Supplier<PolicySettings> settings) {
        this.settings = settings;
    }

    @Override
    public boolean isReadyForSync(Object serverPlayer) {
        if (serverPlayer == null) {
            return false;
        }
        PolicySettings policy = settings.get();
        if (!policy.deferAutoSyncUntilLogin()) {
            return true;
        }
        UUID playerId = extractPlayerId(serverPlayer);
        if (playerId == null) {
            return false;
        }
        if (SyncAuthTracker.isReady(playerId)) {
            return true;
        }
        if (LoginModBridge.isAvailable() && LoginModBridge.isAuthenticated(serverPlayer)) {
            return true;
        }
        return false;
    }

    private static UUID extractPlayerId(Object serverPlayer) {
        try {
            Object uuid = serverPlayer.getClass().getMethod("getUUID").invoke(serverPlayer);
            if (uuid instanceof UUID id) {
                return id;
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return null;
    }

    /** 从当前平台读取策略配置 */
    public static ConfigurableAuthReadiness fromPlatform() {
        return new ConfigurableAuthReadiness(() -> PlatformManager.getPlatform().getPolicySettings());
    }
}
