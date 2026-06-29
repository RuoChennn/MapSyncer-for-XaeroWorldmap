package com.mapsyncer.security;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 同步请求权限推断（服务端）。
 */
public final class SyncPolicyHelper {

    private SyncPolicyHelper() {}

    /**
     * 推断是否为 sync all 请求（空 meta、多维度或占位符首次全量）。
     */
    public static boolean isSyncAllRequest(Map<String, ?> clientMeta) {
        if (clientMeta == null || clientMeta.isEmpty()) {
            return true;
        }
        Set<String> dimensions = new HashSet<>();
        for (String key : clientMeta.keySet()) {
            dimensions.add(firstPathSegment(key));
        }
        if (dimensions.size() > 1) {
            return true;
        }
        if (clientMeta.size() == 1) {
            String onlyKey = clientMeta.keySet().iterator().next();
            return onlyKey.contains("_placeholder_");
        }
        return false;
    }

    /**
     * 校验玩家是否有权发起此次同步。
     */
    public static boolean canProcessSyncRequest(Object commandSource, Map<String, ?> clientMeta) {
        if (!PermissionGates.canSync(commandSource)) {
            return false;
        }
        if (isSyncAllRequest(clientMeta)) {
            return PermissionGates.canSyncAll(commandSource);
        }
        return PermissionGates.has(commandSource, MapSyncerPermissions.SYNC_DIMENSION);
    }

    private static String firstPathSegment(String path) {
        int slash = path.indexOf('/');
        return slash >= 0 ? path.substring(0, slash) : path;
    }
}
