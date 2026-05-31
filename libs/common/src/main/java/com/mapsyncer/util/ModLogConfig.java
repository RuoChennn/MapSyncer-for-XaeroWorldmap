package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志配置工具 — 控制 Dev/Release 模式下的日志级别。
 *
 * <p>Release 模式（默认）：只输出 INFO/WARN/ERROR。
 *    DEBUG/TRACE 级别日志自动被 Log4j2 过滤（默认 root level = INFO）。</p>
 *
 * <p>Dev 模式：通过配置文件 {@code enableDebugLogging = true} 启用，
 *    将所有 mapsyncer 包的 logger 设为 DEBUG 级别以输出详细追踪信息。</p>
 *
 * <p>使用方式：在服务端启动时调用 {@link #applyDebugLogging()}。</p>
 */
public final class ModLogConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModLogConfig.class);
    private static volatile boolean debugEnabled = false;

    private ModLogConfig() {}

    /**
     * 检查调试日志是否已启用。
     */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    /**
     * 应用调试日志配置。
     * 如果 {@code enableDebugLogging} 配置为 true，将 mapsyncer 包日志级别设为 DEBUG。
     * 通过反射调用 Log4j2 Configurator，避免编译时依赖。
     */
    public static void applyDebugLogging() {
        try {
            boolean enableDebug = com.mapsyncer.platform.PlatformManager.getPlatform().isDebugLoggingEnabled();
            if (enableDebug == debugEnabled) return;
            debugEnabled = enableDebug;
            if (enableDebug) {
                setLoggerLevel("com.mapsyncer", "DEBUG");
                LOGGER.info("Debug logging enabled");
            } else {
                setLoggerLevel("com.mapsyncer", "INFO");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to configure debug logging level", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setLoggerLevel(String packageName, String level) {
        try {
            // 通过反射调用 Log4j2 API，避免 libs/common 对 log4j-core 的编译依赖
            Class<?> configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");

            Object logger = logManagerClass.getMethod("getLogger", String.class).invoke(null, packageName);
            Object logLevel = levelClass.getMethod("toLevel", String.class).invoke(null, level);
            configuratorClass.getMethod("setAllLevels", String.class, levelClass)
                    .invoke(null, logger.getClass().getMethod("getName").invoke(logger), logLevel);
        } catch (ClassNotFoundException ignored) {
            // Log4j2 not available
        } catch (Exception e) {
            LOGGER.warn("Failed to set logger level via reflection", e);
        }
    }
}
