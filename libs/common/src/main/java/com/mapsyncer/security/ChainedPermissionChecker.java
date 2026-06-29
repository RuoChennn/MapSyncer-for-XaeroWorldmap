package com.mapsyncer.security;

/**
 * 链式权限检查：依次尝试各 delegate，任一返回 true 即通过；全部为 false 时走原版兜底。
 */
public final class ChainedPermissionChecker implements PermissionChecker {

    private final PermissionChecker[] delegates;
    private final PermissionChecker vanillaFallback;

    public ChainedPermissionChecker(PermissionChecker... delegates) {
        this.delegates = delegates.clone();
        this.vanillaFallback = new VanillaPermissionChecker();
    }

    @Override
    public boolean has(Object commandSource, String permissionNode) {
        for (PermissionChecker delegate : delegates) {
            if (delegate.has(commandSource, permissionNode)) {
                return true;
            }
        }
        return vanillaFallback.has(commandSource, permissionNode);
    }
}
