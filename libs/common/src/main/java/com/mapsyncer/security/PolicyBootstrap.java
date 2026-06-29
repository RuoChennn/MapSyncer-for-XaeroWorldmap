package com.mapsyncer.security;

/**
 * 在 mod 入口注册 {@link GateManager} 的便捷方法。
 */
public final class PolicyBootstrap {

    private PolicyBootstrap() {}

    public static void initialize(PermissionChecker permissions, AuthReadiness auth) {
        GateManager.initialize(permissions, auth);
    }

    /**
     * 原版兜底：OP4 权限 + 不推迟自动同步。
     *
     * <p>各 loader 可在后续替换为 {@link #initialize(PermissionChecker, AuthReadiness)}
     * 并注入 fabric-permissions-api / NeoForge PermissionAPI 等实现。</p>
     */
    public static void initializeVanilla() {
        PermissionChecker permissions = new ChainedPermissionChecker(
                FabricPermissionsBridge.optional(),
                NeoForgePermissionsBridge.optional()
        );
        initialize(permissions, ConfigurableAuthReadiness.fromPlatform());
    }
}
