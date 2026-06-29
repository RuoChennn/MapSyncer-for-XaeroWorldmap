package com.mapsyncer.security;

/**
 * 权限检查门面接口。
 *
 * <p>平台实现通过 LuckPerms / FTB Ranks / fabric-permissions-api 等链式查询；
 * 无权限 mod 时使用原版 OP 等级兜底。</p>
 *
 * @param commandSource 平台命令源，通常为 {@code CommandSourceStack}
 */
@FunctionalInterface
public interface PermissionChecker {

    /**
     * @param commandSource 命令源（服务端）
     * @param permissionNode 权限节点，见 {@link MapSyncerPermissions}
     * @return 是否拥有权限
     */
    boolean has(Object commandSource, String permissionNode);
}
