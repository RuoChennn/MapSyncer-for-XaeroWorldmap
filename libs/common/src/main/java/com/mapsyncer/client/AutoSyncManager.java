package com.mapsyncer.client;

import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
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

    /** 未收到 ServerInstalled 前为 -1 */
    private static volatile int serverAutoSyncIntervalMinutes = -1;

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
    public static void configureFromServer(int intervalMinutes) {
        serverAutoSyncIntervalMinutes = intervalMinutes;
    }

    public static void resetServerPolicy() {
        serverAutoSyncIntervalMinutes = -1;
    }

    public static boolean isServerPolicyKnown() {
        return serverAutoSyncIntervalMinutes >= 0;
    }

    /** 增量更新已开启，允许加入时自动同步 */
    public static boolean isJoinAutoSyncEnabled() {
        return serverAutoSyncIntervalMinutes > 0;
    }

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
     * 加入服务器时是否应触发一次自动 sync。
     * 增量更新关闭时不触发；开启时若有未完成同步或服务端地图较新则触发。
     */
    public static boolean shouldAutoSyncOnJoin(long serverGenTime, int intervalMinutes) {
        if (intervalMinutes <= 0) {
            return false;
        }
        if (hasPendingResume()) {
            LOGGER.info("Join auto-sync: resuming interrupted sync");
            return true;
        }
        return shouldAutoSync(serverGenTime, intervalMinutes);
    }

    public static boolean hasPendingResume() {
        try {
            Path baseDir = ClientTimestampCache.getLastBaseDir();
            if (baseDir == null) {
                return false;
            }
            ClientTimestampCache cache = ClientTimestampCache.getInstance(baseDir);
            return cache != null && cache.cacheFileExists() && cache.needsResume();
        } catch (Exception e) {
            LOGGER.debug("Failed to check pending resume: {}", e.getMessage());
            return false;
        }
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
        resetServerPolicy();
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
                .mapToLong(TimestampHashEntry::timestampSeconds)
                .max().orElse(0);
        } catch (Exception e) {
            LOGGER.debug("Failed to get client last sync timestamp: {}", e.getMessage());
            return 0;
        }
    }
}
