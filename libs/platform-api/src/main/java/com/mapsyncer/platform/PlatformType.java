package com.mapsyncer.platform;

/**
 * 平台类型枚举
 *
 * 定义支持的模组加载器类型
 */
public enum PlatformType {
    /**
     * Forge 旧版本 (1.20.1 及之前)
     */
    FORGE_LEGACY,

    /**
     * Forge 新版本 (1.20.4，如果有)
     */
    FORGE_MODERN,

    /**
     * NeoForge (1.20.4+、1.21+、26.x)
     */
    NEO_FORGE,

    /**
     * Fabric 模组加载器
     */
    FABRIC
}