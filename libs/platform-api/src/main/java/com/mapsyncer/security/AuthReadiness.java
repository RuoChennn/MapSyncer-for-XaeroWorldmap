package com.mapsyncer.security;

/**
 * 登录/鉴权就绪检查门面。
 *
 * <p>用于在登录 mod（如 EasyAuth）验证完成前阻止自动同步与 SyncRequest 处理。
 * 无登录 mod 或未启用延时时，实现应返回 {@code true}（原版兜底）。</p>
 *
 * @param serverPlayer 服务端玩家对象，通常为 {@code ServerPlayer}
 */
@FunctionalInterface
public interface AuthReadiness {

    /**
     * @param serverPlayer 服务端玩家
     * @return 是否允许进行地图同步
     */
    boolean isReadyForSync(Object serverPlayer);
}
