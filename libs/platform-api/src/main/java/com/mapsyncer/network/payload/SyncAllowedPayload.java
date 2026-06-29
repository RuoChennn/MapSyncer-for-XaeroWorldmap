package com.mapsyncer.network.payload;

/**
 * 服务端通知客户端：玩家已通过登录验证，可以开始自动同步。
 *
 * <p>当 {@link com.mapsyncer.security.PolicySettings#deferAutoSyncUntilLogin()} 为 true 时，
 * 客户端应等待此包后再调度 {@code AutoSyncManager}，而非在进服后立即 sync。</p>
 *
 * @param autoSyncDelaySeconds 建议延迟（秒），通常来自 {@link com.mapsyncer.security.PolicySettings#autoSyncDelayAfterLoginSeconds()}
 */
public record SyncAllowedPayload(int autoSyncDelaySeconds) {
    public static final String ID = "sync_allowed";
}
