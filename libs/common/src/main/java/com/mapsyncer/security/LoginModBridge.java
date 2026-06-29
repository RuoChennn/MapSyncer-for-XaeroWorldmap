package com.mapsyncer.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登录 mod 桥接（反射，无 compile 依赖）。
 *
 * <p>当前支持 EasyAuth（{@code easyAuth$isAuthenticated()}）。</p>
 */
public final class LoginModBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginModBridge.class);

    private static final String EASYAUTH_MOD_ID = "easyauth";
    private static final String EASYAUTH_PLAYER_AUTH =
            "xyz.nikitacartes.easyauth.interfaces.PlayerAuth";

    private static volatile Boolean available;
    private static volatile Class<?> playerAuthInterface;
    private static volatile java.lang.reflect.Method isAuthenticatedMethod;
    private static volatile java.lang.reflect.Method canSkipAuthMethod;

    private LoginModBridge() {}

    public static boolean isAvailable() {
        ensureInitialized();
        return available;
    }

    public static boolean isAuthenticated(Object serverPlayer) {
        if (serverPlayer == null) {
            return false;
        }
        ensureInitialized();
        if (!available || playerAuthInterface == null) {
            return false;
        }
        if (!playerAuthInterface.isInstance(serverPlayer)) {
            return false;
        }
        try {
            if (canSkipAuthMethod != null) {
                Object skip = canSkipAuthMethod.invoke(serverPlayer);
                if (skip instanceof Boolean b && b) {
                    return true;
                }
            }
            if (isAuthenticatedMethod != null) {
                Object result = isAuthenticatedMethod.invoke(serverPlayer);
                return result instanceof Boolean b && b;
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("EasyAuth auth check failed: {}", e.getMessage());
        }
        return false;
    }

    private static void ensureInitialized() {
        if (available != null) {
            return;
        }
        synchronized (LoginModBridge.class) {
            if (available != null) {
                return;
            }
            available = false;
            if (!isModLoaded(EASYAUTH_MOD_ID)) {
                return;
            }
            try {
                playerAuthInterface = Class.forName(EASYAUTH_PLAYER_AUTH);
                isAuthenticatedMethod = playerAuthInterface.getMethod("easyAuth$isAuthenticated");
                canSkipAuthMethod = playerAuthInterface.getMethod("easyAuth$canSkipAuth");
                available = true;
                LOGGER.info("Login mod bridge enabled: EasyAuth");
            } catch (ReflectiveOperationException e) {
                LOGGER.debug("EasyAuth API not found: {}", e.getMessage());
            }
        }
    }

    private static boolean isModLoaded(String modId) {
        if (tryFabricModLoaded(modId)) {
            return true;
        }
        if (tryModListLoaded("net.neoforged.fml.ModList", modId)) {
            return true;
        }
        return tryModListLoaded("net.minecraftforge.fml.ModList", modId);
    }

    private static boolean tryFabricModLoaded(String modId) {
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            Object result = fabricLoader.getMethod("isModLoaded", String.class).invoke(instance, modId);
            return result instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean tryModListLoaded(String modListClass, String modId) {
        try {
            Class<?> modList = Class.forName(modListClass);
            Object instance = modList.getMethod("get").invoke(null);
            Object result = instance.getClass().getMethod("isLoaded", String.class).invoke(instance, modId);
            return result instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
