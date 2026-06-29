package com.mapsyncer.security;

/**
 * 权限与登录门控的全局注册中心。
 *
 * <p>与 {@link com.mapsyncer.platform.PlatformManager} 类似，由各 loader 在初始化时注入
 * 平台特定的 {@link PermissionChecker} 与 {@link AuthReadiness} 实现。</p>
 */
public final class GateManager {

    private static volatile PermissionChecker permissionChecker;
    private static volatile AuthReadiness authReadiness;

    private GateManager() {}

    /**
     * 注册平台门控实现（每个进程调用一次）。
     */
    public static void initialize(PermissionChecker permissions, AuthReadiness auth) {
        if (permissionChecker != null || authReadiness != null) {
            throw new IllegalStateException("GateManager already initialized");
        }
        permissionChecker = permissions;
        authReadiness = auth;
    }

    public static PermissionChecker permissions() {
        ensureInitialized();
        return permissionChecker;
    }

    public static AuthReadiness auth() {
        ensureInitialized();
        return authReadiness;
    }

    public static boolean isInitialized() {
        return permissionChecker != null && authReadiness != null;
    }

    /** 仅用于测试或进程重启清理 */
    public static void reset() {
        permissionChecker = null;
        authReadiness = null;
    }

    private static void ensureInitialized() {
        if (permissionChecker == null || authReadiness == null) {
            throw new IllegalStateException("GateManager not initialized. Call initialize() from mod entry.");
        }
    }
}
