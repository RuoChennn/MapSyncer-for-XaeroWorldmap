package com.mapsyncer.security;

/**
 * 原版 OP 等级权限兜底（OP 4 / 游戏管理员）。
 *
 * <p>链式查询顺序（后续扩展）：fabric-permissions-api → FTB Ranks → 本类。</p>
 */
public final class VanillaPermissionChecker implements PermissionChecker {

    @Override
    public boolean has(Object commandSource, String permissionNode) {
        if (commandSource == null) {
            return false;
        }
        int opLevel = requiredOpLevel(permissionNode);
        return hasVanillaOp(commandSource, opLevel);
    }

    private static int requiredOpLevel(String permissionNode) {
        if (MapSyncerPermissions.ADMIN.equals(permissionNode)
                || MapSyncerPermissions.SYNC_ALL.equals(permissionNode)) {
            return 4;
        }
        // sync / sync.dimension：原版允许所有玩家（与改前行为一致）
        return 0;
    }

    /**
     * 平台实现可调用此方法作为链尾兜底。
     */
    public static boolean hasVanillaOp(Object commandSource, int opLevel) {
        try {
            return invokeHasPermission(commandSource, opLevel);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean invokeHasPermission(Object commandSource, int opLevel)
            throws ReflectiveOperationException {
        var method = commandSource.getClass().getMethod("hasPermission", int.class);
        Object result = method.invoke(commandSource, opLevel);
        return result instanceof Boolean b && b;
    }
}
