package com.mapsyncer.security;

/**
 * 权限与登录门控相关配置（平台无关 DTO）。
 *
 * <p>各 loader 从服务端配置文件读取后，通过 {@link com.mapsyncer.platform.Platform#getPolicySettings()} 提供。</p>
 */
public record PolicySettings(
        /** 是否在登录 mod 确认前推迟自动同步 */
        boolean deferAutoSyncUntilLogin,
        /** 登录就绪后、触发自动同步前的额外延迟（秒） */
        int autoSyncDelayAfterLoginSeconds,
        /** 等待登录的最长时间（秒）；0 表示不超时 */
        int loginWaitTimeoutSeconds
) {
    public static final PolicySettings DEFAULT = new PolicySettings(false, 3, 0);

    public PolicySettings {
        if (autoSyncDelayAfterLoginSeconds < 0) {
            autoSyncDelayAfterLoginSeconds = 0;
        }
        if (loginWaitTimeoutSeconds < 0) {
            loginWaitTimeoutSeconds = 0;
        }
    }
}
