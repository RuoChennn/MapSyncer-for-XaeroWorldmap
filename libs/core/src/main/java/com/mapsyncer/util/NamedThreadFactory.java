package com.mapsyncer.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程命名工厂
 *
 * <p>为线程池创建命名线程，使用原子计数器保证线程名称唯一且稳定。</p>
 *
 * <p>替代 {@code r -> new Thread(r, "name-" + r.hashCode())} 的不稳定命名方式。</p>
 */
public final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final String baseName;
    private final boolean daemon;

    /**
     * 创建命名线程工厂
     *
     * @param baseName 线程名称前缀（如 "mapsyncer-converter"）
     */
    public NamedThreadFactory(String baseName) {
        this(baseName, false);
    }

    /**
     * 创建命名线程工厂
     *
     * @param baseName 线程名称前缀
     * @param daemon 是否为守护线程
     */
    public NamedThreadFactory(String baseName, boolean daemon) {
        this.baseName = baseName;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r, baseName + "-" + counter.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    /**
     * 获取已创建的线程数量
     *
     * @return 已创建线程数量
     */
    public int getCreatedCount() {
        return counter.get();
    }
}