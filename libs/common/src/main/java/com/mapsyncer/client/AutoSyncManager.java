package com.mapsyncer.client;

import com.mapsyncer.client.ClientTimestampCache.CacheEntry;
import com.mapsyncer.platform.PlatformManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 客户端自动同步管理器。
 *
 * 在收到服务端安装通知后，比对服务端最后地图生成时间与客户端最后同步时间，
 * 结合冷却间隔决定是否触发自动同步。
 */
public class AutoSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoSyncManager.class);

    private static final ScheduledExecutorService EXECUTOR =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MapSyncer-AutoSync");
            t.setDaemon(true);
            return t;
        });

    private static volatile long lastAutoSyncTimeMs = 0;
    private static volatile ScheduledFuture<?> pendingTask;
    private static volatile boolean active = false;

    /**
     * 根据 autoSyncIntervalMinutes 获取状态消息翻译键和参数。
     * 0=禁用, <1440=每X分钟, ≥1440=每天。
     *
     * @return Object[]{translationKey, arg} 或 Object[]{translationKey}
     */
    public static Object[] getStatusKey(int intervalMinutes) {
        if (intervalMinutes <= 0) return new Object[]{"mapsyncer.autosync.status.disabled"};
        if (intervalMinutes < 1440) return new Object[]{"mapsyncer.autosync.status.minutes", intervalMinutes};
        return new Object[]{"mapsyncer.autosync.status.daily"};
    }

    /**
     * 评估是否应该触发自动同步。
     *
     * @param serverGenTime   服务端最后地图生成时间戳（秒）
     * @param intervalMinutes 自动同步冷却间隔（分钟，0 表示禁用）
     * @return true 表示满足自动同步条件
     */
    public static boolean shouldAutoSync(long serverGenTime, int intervalMinutes) {
        if (intervalMinutes <= 0) {
            LOGGER.debug("Auto-sync disabled (interval={})", intervalMinutes);
            return false;
        }
        if (serverGenTime <= 0) {
            LOGGER.debug("Auto-sync skipped: server has no generation data");
            return false;
        }

        long clientLastSync = getClientLastSyncTimestamp();
        if (serverGenTime <= clientLastSync) {
            LOGGER.debug("Auto-sync skipped: client up-to-date (client={}, server={})",
                clientLastSync, serverGenTime);
            return false;
        }

        long elapsed = System.currentTimeMillis() - lastAutoSyncTimeMs;
        long cooldown = TimeUnit.MINUTES.toMillis(intervalMinutes);
        if (elapsed < cooldown && lastAutoSyncTimeMs > 0) {
            LOGGER.debug("Auto-sync skipped: cooldown ({}m remaining)",
                (cooldown - elapsed) / 60_000);
            return false;
        }

        LOGGER.info("Auto-sync conditions met: serverGen={}, clientSync={}, interval={}m",
            serverGenTime, clientLastSync, intervalMinutes);
        return true;
    }

    /**
     * 调度延迟任务。
     *
     * @param task     要执行的任务
     * @param delaySeconds 延迟秒数
     */
    public static void schedule(Runnable task, int delaySeconds) {
        cancelPending();
        pendingTask = EXECUTOR.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("Auto-sync task failed", e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public static void markStarted() {
        active = true;
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    public static void markComplete() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void cancel() {
        active = false;
        cancelPending();
    }

    private static void cancelPending() {
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
    }

    public static void shutdown() {
        cancel();
        EXECUTOR.shutdownNow();
    }

    /**
     * 从 ClientTimestampCache 获取客户端最后一次同步的时间戳。
     */
    private static long getClientLastSyncTimestamp() {
        try {
            Path baseDir = ClientTimestampCache.getLastBaseDir();
            if (baseDir == null) return 0;

            ClientTimestampCache cache = ClientTimestampCache.getInstance(baseDir);
            if (cache == null) return 0;

            return cache.getAll().values().stream()
                .mapToLong(CacheEntry::timestampSeconds)
                .max().orElse(0);
        } catch (Exception e) {
            LOGGER.debug("Failed to get client last sync timestamp: {}", e.getMessage());
            return 0;
        }
    }
}
