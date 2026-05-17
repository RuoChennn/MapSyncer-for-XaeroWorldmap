package com.mapsyncer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class SyncProgressTracker {

    private static volatile boolean tracking = false;
    private static volatile int processed = 0;
    private static volatile int total = 0;
    private static volatile String status = "";
    private static volatile long startTime = 0;
    private static volatile int lastDisplayedPercent = -1;

    public static void startTracking() {
        tracking = true;
        processed = 0;
        total = 0;
        status = "waiting...";
        startTime = System.currentTimeMillis();
        lastDisplayedPercent = -1;

        // 在聊天栏显示开始消息
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("§e[MapSyncer] §fStarting sync..."),
                    false);
        }
    }

    public static void update(int processed, int total, String status) {
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
        status = "completed";

        long elapsed = getElapsedSeconds();

        // 在聊天栏显示完成消息
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal(String.format("§e[MapSyncer] §aSync completed! §f%d regions in %ds", total, elapsed)),
                    false);
        }
    }

    private static void displayProgress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && tracking) {
            String message;
            if (total > 0) {
                message = String.format("§e[MapSyncer] §fProgress: %d/%d (%d%%)", processed, total, lastDisplayedPercent);
            } else {
                message = "§e[MapSyncer] §f" + status;
            }
            mc.player.displayClientMessage(Component.literal(message), false);
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
