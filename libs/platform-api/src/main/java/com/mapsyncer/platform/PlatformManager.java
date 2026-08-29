package com.mapsyncer.platform;

/**
 * 平台管理器
 *
 * 提供全局 Platform 实例的获取和管理。
 */
public final class PlatformManager {

    private static volatile Platform instance;

    private PlatformManager() {
        // 私有构造器，禁止实例化
    }

    /**
     * 初始化平台实例
     *
     * @param platform 平台实现实例
     */
    public static void initialize(Platform platform) {
        if (instance != null) {
            throw new IllegalStateException("Platform already initialized");
        }
        instance = platform;
    }

    /**
     * 获取当前平台实例
     *
     * @return 平台实例
     * @throws IllegalStateException 如果平台未初始化
     */
    public static Platform getPlatform() {
        if (instance == null) {
            throw new IllegalStateException("Platform not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * 检查平台是否已初始化
     *
     * @return 如果已初始化返回 true
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * 获取服务端统一标识名（多入口复用同一地图缓存）。
     * 返回空字符串表示未配置，客户端应回退到 IP 命名。
     */
    public static String getServerName() {
        if (instance == null) {
            return "";
        }
        return instance.getServerName();
    }

    /**
     * 重置平台实例（仅用于测试）
     */
    public static void reset() {
        instance = null;
    }
}