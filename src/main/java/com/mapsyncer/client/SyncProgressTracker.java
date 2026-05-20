package com.mapsyncer.client;

import com.mapsyncer.util.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SyncProgressTracker {

    private static volatile boolean tracking = false;
    private static volatile int processed = 0;
    private static volatile int total = 0;
    private static volatile String status = "";
    private static volatile long startTime = 0;
    private static volatile int lastDisplayedPercent = -1;

    private static volatile boolean receivedFirstResponse = false;
    private static final long SERVER_RESPONSE_TIMEOUT_MS = 5000;
    private static ScheduledExecutorService timeoutChecker = null;

    public static void startTracking() {
        tracking = true;
        processed = 0;
        total = 0;
        status = Component.translatable("mapsyncer.sync.waiting").getString();
        startTime = System.currentTimeMillis();
        lastDisplayedPercent = -1;
        receivedFirstResponse = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(ChatUtils.message("mapsyncer.sync.start"), false);
        }

        startTimeoutChecker();
    }

    private static void startTimeoutChecker() {
        if (timeoutChecker != null) {
            timeoutChecker.shutdownNow();
        }
        timeoutChecker = Executors.newSingleThreadScheduledExecutor();
        timeoutChecker.schedule(() -> {
            if (tracking && !receivedFirstResponse) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.server_not_installed"), false);
                }
                cancelTracking();
            }
        }, SERVER_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public static void update(int processed, int total, String status) {
        if (!receivedFirstResponse) {
            receivedFirstResponse = true;
            stopTimeoutChecker();
        }

        SyncProgressTracker.processed = processed;
        SyncProgressTracker.total = total;
        SyncProgressTracker.status = status;

        if (total > 0) {
            int percent = (processed * 100) / total;
            if (percent != lastDisplayedPercent && percent % 10 == 0) {
                lastDisplayedPercent = percent;
                displayProgress();
            }
        }
    }

    public static void complete() {
        tracking = false;
        status = Component.translatable("mapsyncer.sync.completed", total, getElapsedSeconds()).getString();
        stopTimeoutChecker();

        long elapsed = getElapsedSeconds();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(ChatUtils.success("mapsyncer.sync.completed", total, elapsed), false);
        }
    }

    public static void cancelTracking() {
        tracking = false;
        status = Component.translatable("mapsyncer.sync.cancelled").getString();
        stopTimeoutChecker();
    }

    private static void stopTimeoutChecker() {
        if (timeoutChecker != null) {
            timeoutChecker.shutdownNow();
            timeoutChecker = null;
        }
    }

    private static void displayProgress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && tracking) {
            if (total > 0) {
                mc.player.displayClientMessage(ChatUtils.message("mapsyncer.sync.progress", processed, total, lastDisplayedPercent), false);
            } else {
                mc.player.displayClientMessage(ChatUtils.prefix().append(Component.literal(status)), false);
            }
        }
    }

    public static boolean isTracking() {
        return tracking;
    }

    public static long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
