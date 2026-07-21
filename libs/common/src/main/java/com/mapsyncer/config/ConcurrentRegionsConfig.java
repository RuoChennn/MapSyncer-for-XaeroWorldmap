package com.mapsyncer.config;

/**
 * 服务端同时转换 region 数解析：配置 {@code 0} 为自动，正数为手动。
 *
 * <p>自动：{@code max(1, min(16, availableProcessors - 2))}，
 * 使用 JVM 可见逻辑处理器数（含容器限制）。</p>
 */
public final class ConcurrentRegionsConfig {

    /** 手动/自动生效上限（与历史配置上限一致） */
    public static final int MAX_CONCURRENT = 16;

    /** 配置值：自动模式 */
    public static final int AUTO = 0;

    private ConcurrentRegionsConfig() {}

    /**
     * @param configured 配置文件中的值；{@code <= 0} 表示自动
     * @return 实际用于线程池的并发数，范围 {@code [1, MAX_CONCURRENT]}
     */
    public static int resolve(int configured) {
        if (configured > 0) {
            return Math.max(1, Math.min(MAX_CONCURRENT, configured));
        }
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(MAX_CONCURRENT, processors - 2));
    }

    /** 将写入配置的原始值规范到 {@code [0, MAX_CONCURRENT]}（0=自动）。 */
    public static int clampConfigured(int configured) {
        return Math.max(AUTO, Math.min(MAX_CONCURRENT, configured));
    }
}
