package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 增量更新处理器 - 负责定时扫描并更新已修改的区域地图
 *
 * 支持两种更新模式：
 * - TICK模式：每隔指定tick数执行一次增量扫描
 * - SCHEDULED模式：每天在指定时间执行增量扫描
 *
 * 通过MCA文件时间戳检测哪些区域需要重新生成，
 * 仅更新有变化的区域以提高效率。
 */
@EventBusSubscriber(value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.GAME)
public class IncrementalUpdateHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalUpdateHandler.class);

    /** 单例实例 */
    private static volatile IncrementalUpdateHandler instance;

    /** Minecraft服务器实例 */
    private volatile MinecraftServer server;

    /** 处理器是否正在运行 */
    private volatile boolean running = false;

    /** Tick计数器，用于TICK模式计时 */
    private final AtomicInteger tickCounter = new AtomicInteger(0);

    /** 上次计划更新的时间，用于防止同一天多次执行 */
    private volatile LocalDateTime lastScheduledUpdate = null;

    /**
     * 获取单例实例
     *
     * @return 增量更新处理器实例
     */
    public static IncrementalUpdateHandler getInstance() {
        if (instance == null) {
            synchronized (IncrementalUpdateHandler.class) {
                if (instance == null) {
                    instance = new IncrementalUpdateHandler();
                }
            }
        }
        return instance;
    }

    /**
     * 启动增量更新处理器
     *
     * @param server Minecraft服务器实例
     */
    public void start(MinecraftServer server) {
        if (running) {
            LOGGER.warn("Incremental update handler already running");
            return;
        }
        this.server = server;
        this.running = true;
        this.tickCounter.set(0);
        this.lastScheduledUpdate = null;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.TICK) {
            LOGGER.info("Incremental update handler started (TICK mode, interval: {} ticks = {} seconds)",
                ModConfig.SERVER.incrementalUpdateIntervalTicks.get(),
                ModConfig.SERVER.incrementalUpdateIntervalTicks.get() / 20);
        } else if (mode == UpdateMode.SCHEDULED) {
            LOGGER.info("Incremental update handler started (SCHEDULED mode, daily at {}:{})",
                ModConfig.SERVER.scheduledUpdateHour.get(),
                ModConfig.SERVER.scheduledUpdateMinute.get());
        }
    }

    /**
     * 停止增量更新处理器
     */
    public void stop() {
        running = false;
        server = null;
        tickCounter.set(0);
        lastScheduledUpdate = null;
        LOGGER.info("Incremental update handler stopped");
    }

    /**
     * 检查处理器是否正在运行
     *
     * @return true表示正在运行，false表示已停止
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 服务器Tick事件处理
     *
     * 每个服务器tick都会调用此方法，根据配置的更新模式
     * 检查是否需要执行增量扫描。
     *
     * @param event 服务器Tick后事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        IncrementalUpdateHandler handler = getInstance();
        if (!handler.running || handler.server == null) return;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.DISABLED) return;

        switch (mode) {
            case TICK:
                handler.checkTickMode();
                break;
            case SCHEDULED:
                handler.checkScheduledMode();
                break;
            case DISABLED:
                // Do nothing
                break;
        }
    }

    /**
     * 检查TICK模式是否需要执行更新
     */
    private void checkTickMode() {
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
        int currentTick = tickCounter.incrementAndGet();

        if (currentTick >= interval) {
            tickCounter.set(0);
            performScheduledUpdate("TICK mode interval");
        }
    }

    /**
     * 检查SCHEDULED模式是否需要执行更新
     *
     * 在目标时间前后1分钟的窗口内检查，确保只在每天执行一次。
     */
    private void checkScheduledMode() {
        LocalDateTime now = LocalDateTime.now();
        int targetHour = ModConfig.SERVER.scheduledUpdateHour.get();
        int targetMinute = ModConfig.SERVER.scheduledUpdateMinute.get();
        LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
        LocalTime currentTime = now.toLocalTime();

        // Check if we've reached the scheduled time (within 1 minute window)
        // and haven't already updated today
        if (currentTime.isAfter(targetTime) && currentTime.isBefore(targetTime.plusMinutes(1))) {
            if (lastScheduledUpdate == null || !lastScheduledUpdate.toLocalDate().equals(now.toLocalDate())) {
                lastScheduledUpdate = now;
                performScheduledUpdate("SCHEDULED mode daily update at " + targetHour + ":" + targetMinute);
            }
        }
    }

    /**
     * 执行计划更新
     *
     * @param reason 更新原因描述
     */
    private void performScheduledUpdate(String reason) {
        LOGGER.info("Performing incremental update: {}", reason);

        try {
            ConversionOrchestrator.performIncrementalScan(server);
        } catch (Exception e) {
            LOGGER.error("Error during scheduled incremental update", e);
        }
    }

    /**
     * 获取处理器状态信息
     *
     * 返回当前状态和下次更新的预计时间，用于status命令显示。
     *
     * @return 状态信息字符串
     */
    public String getStatusInfo() {
        if (!running) {
            return "Stopped";
        }

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        switch (mode) {
            case DISABLED:
                return "Running but disabled";
            case TICK:
                int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
                int remaining = interval - tickCounter.get();
                return String.format("TICK mode: next update in %d ticks (%.1f seconds)",
                    remaining, remaining / 20.0f);
            case SCHEDULED:
                int targetHour = ModConfig.SERVER.scheduledUpdateHour.get();
                int targetMinute = ModConfig.SERVER.scheduledUpdateMinute.get();
                LocalDateTime now = LocalDateTime.now();
                LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
                LocalDateTime nextUpdate = now.toLocalDate().atTime(targetTime);
                if (now.toLocalTime().isAfter(targetTime)) {
                    nextUpdate = nextUpdate.plusDays(1);
                }
                long secondsUntil = java.time.Duration.between(now, nextUpdate).getSeconds();
                return String.format("SCHEDULED mode: next update at %02d:%02d (in %dh %dm)",
                    targetHour, targetMinute, secondsUntil / 3600, (secondsUntil % 3600) / 60);
            default:
                return "Unknown mode";
        }
    }

    /**
     * 重置单例实例以释放内存
     *
     * 在服务器停止时调用，防止专用服务器重启时的内存泄漏。
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
            LOGGER.info("IncrementalUpdateHandler instance reset");
        }
    }
}