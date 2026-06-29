package com.mapsyncer.server;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 增量更新处理器逻辑 - 负责定时扫描并更新已修改的区域地图
 *
 * 支持两种更新模式：
 * - TICK模式：每隔指定tick数执行一次增量扫描
 * - SCHEDULED模式：每天在指定时间执行增量扫描
 *
 * 通过MCA文件时间戳检测哪些区域需要重新生成，
 * 仅更新有变化的区域以提高效率。
 *
 * 注意：此类包含所有平台共享的业务逻辑，平台特定的事件注册由各平台薄包装器处理。
 */
public class IncrementalUpdateHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalUpdateHandlerLogic.class);

    /** 单例实例 */
    private static volatile IncrementalUpdateHandlerLogic instance;

    /** Minecraft服务器实例 */
    private volatile MinecraftServer server;

    /** 处理器是否正在运行 */
    private volatile boolean running = false;

    /** Tick计数器，用于TICK模式计时 */
    private final AtomicInteger tickCounter = new AtomicInteger(0);

    /** 上次计划更新的时间，用于防止同一天多次执行 */
    private volatile LocalDateTime lastScheduledUpdate = null;

    /** 后台执行器，用于异步执行增量扫描，避免阻塞 Server 线程 */
    private volatile ExecutorService updateExecutor = null;

    /** 标记是否已有增量更新任务正在执行 */
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    private IncrementalUpdateHandlerLogic() {
        // 私有构造器，禁止外部实例化
        // 注意：此构造器必须无外部依赖且不能抛异常，
        // 否则 DCL volatile 保证在 Java 5+ 下仍需要局部变量防御
    }

    /**
     * 获取单例实例
     *
     * @return 增量更新处理器逻辑实例
     */
    public static IncrementalUpdateHandlerLogic getInstance() {
        if (instance == null) {
            synchronized (IncrementalUpdateHandlerLogic.class) {
                if (instance == null) {
                    instance = new IncrementalUpdateHandlerLogic();
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

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        if (mode == UpdateMode.TICK) {
            LOGGER.info("Incremental update handler started (TICK mode, interval: {} ticks = {} seconds)",
                PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks(),
                PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks() / 20);
        } else if (mode == UpdateMode.SCHEDULED) {
            LOGGER.info("Incremental update handler started (SCHEDULED mode, daily at {}:{})",
                PlatformManager.getPlatform().getScheduledUpdateHour(),
                PlatformManager.getPlatform().getScheduledUpdateMinute());
        }
    }

    /**
     * 停止增量更新处理器
     */
    public void stop() {
        running = false;
        updateInProgress.set(false);
        shutdownExecutor();
        server = null;
        tickCounter.set(0);
        lastScheduledUpdate = null;
        LOGGER.info("Incremental update handler stopped");
    }

    /**
     * 获取或创建后台更新执行器（单线程，惰性初始化）
     */
    private ExecutorService getUpdateExecutor() {
        if (updateExecutor == null) {
            synchronized (this) {
                if (updateExecutor == null) {
                    updateExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "mapsyncer-incremental-update");
                        t.setPriority(Thread.MIN_PRIORITY);
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return updateExecutor;
    }

    /**
     * 关闭后台更新执行器
     */
    private void shutdownExecutor() {
        if (updateExecutor != null) {
            updateExecutor.shutdownNow();
            try {
                if (!updateExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Update executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            updateExecutor = null;
        }
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
     * 获取当前tick计数
     *
     * @return tick计数器值
     */
    public int getTickCounter() {
        return tickCounter.get();
    }

    /**
     * 处理服务器Tick事件
     *
     * 每个服务器tick都会调用此方法，根据配置的更新模式
     * 检查是否需要执行增量扫描。
     * 由平台特定的事件处理器调用。
     */
    public void onServerTick() {
        if (!running || server == null) return;

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        if (mode == UpdateMode.DISABLED) return;

        switch (mode) {
            case TICK:
                checkTickMode();
                break;
            case SCHEDULED:
                checkScheduledMode();
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
        int interval = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
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
        int targetHour = PlatformManager.getPlatform().getScheduledUpdateHour();
        int targetMinute = PlatformManager.getPlatform().getScheduledUpdateMinute();
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
        if (!updateInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Scheduled update already in progress, skipping");
            return;
        }

        LOGGER.info("Performing incremental update: {}", reason);

        // Save chunks on server thread (required for thread safety)
        try {
            server.saveEverything(false, true, true);
        } catch (RuntimeException e) {
            LOGGER.error("Runtime error saving chunks for incremental scan", e);
            updateInProgress.set(false);
            return;
        }

        // Run heavy I/O (MCA scan, conversion, writing) on background thread
        // to avoid blocking the server tick and triggering the watchdog.
        final MinecraftServer currentServer = this.server;
        getUpdateExecutor().submit(() -> {
            try {
                ConversionOrchestrator.performIncrementalScan(currentServer);
            } catch (RuntimeException e) {
                LOGGER.error("Error during scheduled incremental update", e);
            } finally {
                updateInProgress.set(false);
            }

            if (currentServer.getPlayerList().getPlayerCount() == 0) {
                LOGGER.info("No players online after incremental update, stopping handler to save resources");
                currentServer.execute(IncrementalUpdateHandlerLogic.this::stop);
            }
        });
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

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        switch (mode) {
            case DISABLED:
                return "Running but disabled";
            case TICK:
                int interval = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
                int remaining = interval - tickCounter.get();
                return String.format("TICK mode: next update in %d ticks (%.1f seconds)",
                    remaining, remaining / 20.0f);
            case SCHEDULED:
                int targetHour = PlatformManager.getPlatform().getScheduledUpdateHour();
                int targetMinute = PlatformManager.getPlatform().getScheduledUpdateMinute();
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
            LOGGER.info("IncrementalUpdateHandlerLogic instance reset");
        }
    }
}
