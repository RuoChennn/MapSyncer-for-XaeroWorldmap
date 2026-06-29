package com.mapsyncer.security;

/**
 * 业务层权限门控入口。
 *
 * <p>调用方（命令、SyncRequest 处理等）应使用此类，而非直接访问 {@link GateManager}。</p>
 */
public final class PermissionGates {

    private PermissionGates() {}

    public static boolean has(Object commandSource, String permissionNode) {
        return GateManager.permissions().has(commandSource, permissionNode);
    }

    public static boolean canSync(Object commandSource) {
        return has(commandSource, MapSyncerPermissions.SYNC);
    }

    public static boolean canSyncAll(Object commandSource) {
        return has(commandSource, MapSyncerPermissions.SYNC_ALL);
    }

    public static boolean canAdmin(Object commandSource) {
        return has(commandSource, MapSyncerPermissions.ADMIN);
    }
}
