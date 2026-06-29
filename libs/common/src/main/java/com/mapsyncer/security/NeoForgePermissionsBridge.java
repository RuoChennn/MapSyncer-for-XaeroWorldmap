package com.mapsyncer.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge PermissionAPI 反射桥接。
 */
public final class NeoForgePermissionsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgePermissionsBridge.class);

    private NeoForgePermissionsBridge() {}

    public static PermissionChecker optional() {
        try {
            Class<?> permissionApi = Class.forName("net.neoforged.neoforge.server.permission.PermissionAPI");
            var getPermission = permissionApi.getMethod("getPermission", Object.class, String.class);
            LOGGER.info("Permission bridge enabled: NeoForge PermissionAPI");
            return (source, node) -> {
                try {
                    Object result = getPermission.invoke(null, source, node);
                    return result instanceof Boolean b && b;
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            };
        } catch (ReflectiveOperationException e) {
            return (source, node) -> false;
        }
    }
}
