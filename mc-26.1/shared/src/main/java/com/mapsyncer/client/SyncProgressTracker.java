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
 *   <li>检测服务端响应超时</li>
 * </ul>
 *
 * <p>进度显示：</p>
 * <ul>
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

    /** 超时检查器（静态单例，避免重复创建线程池） */
    private static volatile ScheduledExecutorService timeoutChecker = null;

    /** 当前超时检查任务的Future（用于取消） */
    private static volatile java.util.concurrent.ScheduledFuture<?> timeoutFuture = null;

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

        startTimeoutChecker();
    }

    /**
     * 启动超时检查器。
     * 如果在5秒内未收到服务端响应，根据服务端安装状态显示不同提示。
     * 使用静态单例线程池，避免重复创建线程池浪费资源。
     */
    private static void startTimeoutChecker() {
        // 取消之前的超时任务（如果存在）
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
        }

        // 使用静态单例线程池（首次创建后重用）
        if (timeoutChecker == null || timeoutChecker.isShutdown()) {
            timeoutChecker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mapsyncer-sync-progress-timer");
                t.setDaemon(true);
                return t;
            });
        }

        timeoutFuture = timeoutChecker.schedule(() -> {
            if (tracking && !receivedFirstResponse) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    // 根据服务端安装状态显示不同错误
                    if (MapPacketReceiver.isServerInstalled()) {
                        // 服务端已安装但响应超时，可能是网络问题或服务端处理出错
                        mc.player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.timeout"));
                    } else {
                        // 服务端未安装
                        mc.player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.server_not_installed"));
                    }
                }
                cancelTracking();
            }
        }, SERVER_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 更新同步进度。
     * 接收服务端发送的进度信息，显示进度更新。
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

        // 每次进度更新都显示
        if (total > 0) {
            int percent = (processed * 100) / total;
            lastDisplayedPercent = percent;
            displayProgress();
        }
    }

    /**
     * 标记同步完成。
     * 显示完成消息，包含总区域数和耗时。
     */
    public static void complete() {
        completeWithCount(total);
    }

    /**
     * 标记同步完成，使用指定的区域数量。
     * 用于在收到最终响应时显示实际接收的区域数量。
     *
     * @param count 实际接收的区域数量
     */
    public static void completeWithCount(int count) {
        tracking = false;
        status = Component.translatable("mapsyncer.sync.completed", count, getElapsedSeconds()).getString();
        stopTimeoutChecker();

        long elapsed = getElapsedSeconds();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(ChatUtils.success("mapsyncer.sync.completed", count, elapsed));
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
     * 取消当前超时任务，但保留线程池供下次使用。
     */
    private static void stopTimeoutChecker() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /**
     * 关闭线程池（服务器停止时调用）。
     * 用于完全释放资源。
     */
    public static void shutdown() {
        stopTimeoutChecker();
        if (timeoutChecker != null && !timeoutChecker.isShutdown()) {
            timeoutChecker.shutdown();
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
                mc.player.sendSystemMessage(ChatUtils.message("mapsyncer.sync.progress", processed, total, lastDisplayedPercent));
            } else {
                mc.player.sendSystemMessage(ChatUtils.prefix().append(Component.literal(status)));
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
