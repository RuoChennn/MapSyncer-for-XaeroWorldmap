package com.mapsyncer.client;

import com.mapsyncer.util.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 同步进度追踪器。
 * 用于追踪和显示地图同步的进度状态，包括处理进度、耗时和完成状态。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>追踪同步的开始、进行中和完成状态</li>
 *   <li>定期显示进度百分比（每10%）</li>
 *   <li>计算同步耗时</li>
 *   <li>检测服务端响应超时，提示服务端可能未安装模组</li>
 * </ul>
 *
 * <p>进度显示：</p>
 * <ul>
 *   <li>开始同步时显示开始消息</li>
 *   <li>每10%进度显示一次进度更新</li>
 *   <li>同步完成时显示总耗时和处理的区域数</li>
 * </ul>
 */
public class SyncProgressTracker {

    /** 是否正在追踪进度 */
    private static volatile boolean tracking = false;

    /** 已处理的区域数 */
    private static volatile int processed = 0;

    /** 总区域数 */
    private static volatile int total = 0;

    /** 当前状态描述 */
    private static volatile String status = "";

    /** 同步开始时间 */
    private static volatile long startTime = 0;

    /** 上次显示的百分比，用于避免重复显示 */
    private static volatile int lastDisplayedPercent = -1;

    /** 是否收到第一次响应 */
    private static volatile boolean receivedFirstResponse = false;

    /** 服务端响应超时时间（5秒） */
    private static final long SERVER_RESPONSE_TIMEOUT_MS = 5000;

    /** 超时检查器 */
    private static ScheduledExecutorService timeoutChecker = null;

    /**
     * 开始追踪同步进度。
     * 初始化所有追踪变量，显示开始消息，并启动超时检查器。
     */
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

    /**
     * 启动超时检查器。
     * 如果在5秒内未收到服务端响应，提示服务端可能未安装模组。
     */
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

    /**
     * 更新同步进度。
     * 接收服务端发送的进度信息，并在达到特定百分比时显示进度。
     *
     * @param processed 已处理的区域数
     * @param total 总区域数
     * @param status 当前状态描述
     */
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

    /**
     * 标记同步完成。
     * 显示完成消息，包含总区域数和耗时。
     */
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

    /**
     * 取消进度追踪。
     * 在同步被中断或取消时调用。
     */
    public static void cancelTracking() {
        tracking = false;
        status = Component.translatable("mapsyncer.sync.cancelled").getString();
        stopTimeoutChecker();
    }

    /**
     * 停止超时检查器。
     * 在收到第一次响应或同步完成时调用。
     */
    private static void stopTimeoutChecker() {
        if (timeoutChecker != null) {
            timeoutChecker.shutdownNow();
            timeoutChecker = null;
        }
    }

    /**
     * 显示当前进度。
     * 在玩家聊天栏显示进度百分比信息。
     */
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

    /**
     * 检查是否正在追踪进度。
     *
     * @return 如果正在追踪返回 true；否则返回 false
     */
    public static boolean isTracking() {
        return tracking;
    }

    /**
     * 获取同步耗时（秒）。
     * 从开始追踪到当前的耗时。
     *
     * @return 耗时（秒）
     */
    public static long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
