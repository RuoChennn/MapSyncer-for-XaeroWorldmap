package com.mapsyncer.client;

import com.mapsyncer.platform.PlatformManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class BackgroundSyncManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundSyncManager.class);
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MapSyncer-BackgroundSync");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile ScheduledFuture<?> task;

    private BackgroundSyncManager() {
    }

    public static synchronized void start(Runnable syncAction) {
        stop();
        int minutes = PlatformManager.getPlatform().getBackgroundSyncIntervalMinutes();
        if (minutes <= 0 || !PlatformManager.getPlatform().getClientSyncMode().allowsReceive()) {
            return;
        }
        task = EXECUTOR.scheduleWithFixedDelay(() -> {
            try {
                syncAction.run();
            } catch (RuntimeException e) {
                LOGGER.warn("Background map sync check failed", e);
            }
        }, minutes, minutes, TimeUnit.MINUTES);
        LOGGER.info("Background map sync scheduled every {} minutes", minutes);
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    public static synchronized void shutdown() {
        stop();
        EXECUTOR.shutdownNow();
    }
}
