package com.mapsyncer.config;

import java.util.Locale;

/**
 * 客户端同步能力模式。
 */
public enum ClientSyncMode {
    DISABLED,
    RECEIVE_ONLY,
    BIDIRECTIONAL;

    public boolean allowsReceive() {
        return this != DISABLED;
    }

    public boolean allowsContribution() {
        return this == BIDIRECTIONAL;
    }

    public static ClientSyncMode fromConfig(String value, ClientSyncMode fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return ClientSyncMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
