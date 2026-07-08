package com.mapsyncer.client;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.server.AutoSyncConfig;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
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
 * 在收到服务端安装通知后，比对服务端最后地图生成时间与客户端最后同步时间决定是否进服自动同步；
 * SCHEDULED 模式仅做时间戳比对，TICK 模式另加冷却并启在线周期计时器。
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
    private static volatile ScheduledFuture<?> periodicTask;
    private static volatile boolean active = false;
    private static volatile boolean periodicSync = false;

    /** 未收到 ServerInstalled 前为 -1 */
    private static volatile int serverAutoSyncIntervalMinutes = -1;
    private static volatile UpdateMode serverUpdateMode = UpdateMode.DISABLED;
    private static volatile int serverIntervalTicks = 0;

    /**
     * 进服时显示的自动同步状态（翻译键 + 可选参数）。
     * 客户端关闭 autoSyncEnabled 时显示客户端禁用提示，否则展示服务端策略。
     */
    public static Object[] getStatusKey(int intervalMinutes) {
        try {
            if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
                return new Object[]{"mapsyncer.autosync.status.client_disabled"};
            }
        } catch (IllegalStateException ignored) {
            // 配置未初始化时回退为服务端状态
        }
        if (intervalMinutes <= 0) return new Object[]{"mapsyncer.autosync.status.disabled"};
        if (intervalMinutes < 1440) return new Object[]{"mapsyncer.autosync.status.minutes", intervalMinutes};
        return new Object[]{"mapsyncer.autosync.status.daily"};
    }

    public static void configureFromServer(UpdateMode mode, int intervalMinutes, int intervalTicks) {
        serverUpdateMode = mode;
        serverAutoSyncIntervalMinutes = intervalMinutes;
        serverIntervalTicks = intervalTicks;
    }

    public static void resetServerPolicy() {
        serverAutoSyncIntervalMinutes = -1;
        serverUpdateMode = UpdateMode.DISABLED;
        serverIntervalTicks = 0;
    }

    public static boolean isServerPolicyKnown() {
        return serverAutoSyncIntervalMinutes >= 0;
    }

    /** 增量更新已开启且客户端允许自动同步时，加入时自动同步 */
    public static boolean isJoinAutoSyncEnabled() {
        if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
            return false;
        }
        if (serverUpdateMode == UpdateMode.SCHEDULED || serverUpdateMode == UpdateMode.TICK) {
            return true;
        }
        return serverAutoSyncIntervalMinutes > 0;
    }

    /**
     * 日程表模式：客户端最后同步时间早于服务端最后生成时间则同步（无冷却）。
     */
    public static boolean shouldSyncScheduledOnJoin(long serverGenTime) {
        if (serverGenTime <= 0) {
            LOGGER.debug("Scheduled join auto-sync skipped: server has no generation data");
            return false;
        }
        long clientLastSync = getClientLastSyncTimestamp();
        if (clientLastSync >= serverGenTime) {
            LOGGER.debug("Scheduled join auto-sync skipped: client up-to-date (client={}, server={})",
                    clientLastSync, serverGenTime);
            return false;
        }
        LOGGER.info("Scheduled join auto-sync: client behind server (client={}, server={})",
                clientLastSync, serverGenTime);
        return true;
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
     * SCHEDULED：仅比对客户端/服务端时间戳；TICK：时间戳 + 冷却间隔。
     */
    public static boolean shouldAutoSyncOnJoin(long serverGenTime, int intervalMinutes) {
        if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
            LOGGER.debug("Join auto-sync skipped: client auto-sync disabled");
            return false;
        }
        if (hasPendingResume()) {
            LOGGER.info("Join auto-sync: resuming interrupted sync");
            return true;
        }
        // 服务端未启用增量更新时，不发起新的自动同步
        if (serverUpdateMode == UpdateMode.DISABLED) {
            LOGGER.debug("Join auto-sync skipped: server incremental updates disabled");
            return false;
        }
        if (serverUpdateMode == UpdateMode.SCHEDULED) {
            LOGGER.debug("Join auto-sync: SCHEDULED mode, checking timestamps...");
            return shouldSyncScheduledOnJoin(serverGenTime);
        }
        if (intervalMinutes <= 0) {
            LOGGER.debug("Join auto-sync skipped: intervalMinutes={}", intervalMinutes);
            return false;
        }
        LOGGER.debug("Join auto-sync: TICK mode, checking conditions (serverGenTime={}, intervalMinutes={})...", serverGenTime, intervalMinutes);
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
     * TICK 模式下启动与生成周期一致的在线周期同步。
     * 首次触发在完整周期之后（进服 sync 由 shouldAutoSyncOnJoin 单独处理）。
     */
    public static void startTickPeriodicSync(Runnable syncAction) {
        cancelPeriodic();
        if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
            return;
        }
        if (serverUpdateMode != UpdateMode.TICK || serverIntervalTicks <= 0) {
            return;
        }

        long periodMs = AutoSyncConfig.ticksToPeriodMs(serverIntervalTicks);
        LOGGER.info("Starting TICK periodic auto-sync: {} ticks ({} ms)", serverIntervalTicks, periodMs);
        periodicTask = EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                syncAction.run();
            } catch (Exception e) {
                LOGGER.error("TICK periodic auto-sync failed", e);
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

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

    /** 周期 sync 开始时调用（action bar 提示，不发聊天消息） */
    public static void markPeriodicSync() {
        periodicSync = true;
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    public static boolean isPeriodicSync() {
        return periodicSync;
    }

    public static void clearPeriodicSync() {
        periodicSync = false;
    }

    /** 进服自动 sync 开始时调用（显示完成提示） */
    public static void markStarted() {
        active = true;
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    /** 周期 sync 开始时调用（不标记为 active，避免重复完成提示） */
    public static void touchSyncTime() {
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
        periodicSync = false;
        cancelPending();
        cancelPeriodic();
    }

    private static void cancelPending() {
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
    }

    private static void cancelPeriodic() {
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
    }

    /** 停止 TICK 在线周期同步（客户端关闭自动同步时调用） */
    public static void stopPeriodicSync() {
        cancelPeriodic();
    }

    public static void shutdown() {
        cancel();
        resetServerPolicy();
        EXECUTOR.shutdownNow();
    }

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
