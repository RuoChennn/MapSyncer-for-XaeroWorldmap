package com.mapsyncer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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

    // 服务端响应超时检测
    private static volatile boolean receivedFirstResponse = false;
    private static final long SERVER_RESPONSE_TIMEOUT_MS = 5000; // 5秒超时
    private static ScheduledExecutorService timeoutChecker = null;

    /**
     * 创建带颜色的前缀组件
     */
    private static MutableComponent prefix() {
        return Component.translatable("mapsyncer.prefix").withStyle(style -> style.withColor(0xFFE55E)); // 黄色
    }

    public static void startTracking() {
        tracking = true;
        processed = 0;
        total = 0;
        status = Component.translatable("mapsyncer.sync.waiting").getString();
        startTime = System.currentTimeMillis();
        lastDisplayedPercent = -1;
        receivedFirstResponse = false;

        // 在聊天栏显示开始消息
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    prefix().append(Component.translatable("mapsyncer.sync.start").withStyle(style -> style.withColor(0xFFFFFF))),
                    false);
        }

        // 启动超时检测
        startTimeoutChecker();
    }

    private static void startTimeoutChecker() {
        if (timeoutChecker != null) {
            timeoutChecker.shutdownNow();
        }
        timeoutChecker = Executors.newSingleThreadScheduledExecutor();
        timeoutChecker.schedule(() -> {
            if (tracking && !receivedFirstResponse) {
                // 超时未收到服务端响应，弹出提示
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            prefix().append(Component.translatable("mapsyncer.sync.server_not_installed").withStyle(style -> style.withColor(0xFF5555))),
                            false);
                }
                cancelTracking();
            }
        }, SERVER_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public static void update(int processed, int total, String status) {
        // 标记收到服务端响应，取消超时检测
        if (!receivedFirstResponse) {
            receivedFirstResponse = true;
            stopTimeoutChecker();
        }

        SyncProgressTracker.processed = processed;
        SyncProgressTracker.total = total;
        SyncProgressTracker.status = status;

        // 每10%进度更新一次聊天栏
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

        // 在聊天栏显示完成消息
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    prefix().append(Component.translatable("mapsyncer.sync.completed", total, elapsed).withStyle(style -> style.withColor(0x55FF55))),
                    false);
        }
    }

    /**
     * 取消同步追踪（服务端未响应时调用）
     */
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
            MutableComponent message;
            if (total > 0) {
                message = prefix().append(Component.translatable("mapsyncer.sync.progress", processed, total, lastDisplayedPercent).withStyle(style -> style.withColor(0xFFFFFF)));
            } else {
                message = prefix().append(Component.literal(status).withStyle(style -> style.withColor(0xFFFFFF)));
            }
            mc.player.displayClientMessage(message, false);
        }
    }

    public static boolean isTracking() {
        return tracking;
    }

    public static int getProcessed() {
        return processed;
    }

    public static int getTotal() {
        return total;
    }

    public static String getStatus() {
        return status;
    }

    public static float getProgress() {
        if (total <= 0) return 0;
        return (float) processed / total;
    }

    public static long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
