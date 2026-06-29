package com.mapsyncer.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * fabric-permissions-api（LuckPerms 等）反射桥接。
 */
public final class FabricPermissionsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricPermissionsBridge.class);

    private FabricPermissionsBridge() {}

    public static PermissionChecker optional() {
        try {
            Class<?> permissions = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            var check = permissions.getMethod("check", Object.class, String.class, boolean.class);
            LOGGER.info("Permission bridge enabled: fabric-permissions-api");
            return (source, node) -> {
                try {
                    Object result = check.invoke(null, source, node, false);
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
