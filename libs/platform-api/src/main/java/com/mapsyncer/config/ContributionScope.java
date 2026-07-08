package com.mapsyncer.config;

import java.util.Locale;

/**
 * 服务端接受客户端地图贡献的权限范围。
 */
public enum ContributionScope {
    DISABLED,
    OPS,
    WHITELIST,
    OPS_AND_WHITELIST,
    ALL;

    public boolean allowsAnyContributor() {
        return this == ALL;
    }

    public boolean allowsWhitelist() {
        return this == WHITELIST || this == OPS_AND_WHITELIST;
    }

    public boolean allowsOperators() {
        return this == OPS || this == OPS_AND_WHITELIST;
    }

    public static ContributionScope fromConfig(String value, ContributionScope fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return ContributionScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
