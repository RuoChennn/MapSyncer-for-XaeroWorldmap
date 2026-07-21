package com.mapsyncer.server;

import com.mapsyncer.config.DimensionConfigParser;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.ModLogConfig;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 缓存命令处理器 - 平台无关的命令逻辑
 *
 * 各平台模块的命令类调用此类的静态方法执行实际业务逻辑，
 * 仅负责命令注册和参数解析。
 */
public class CacheCommandHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheCommandHandler.class);

    /**
     * 当前 Loader 下的服务端命令前缀。
     * Fabric：{@code mapsyncerserver}；Forge / NeoForge：{@code mapsyncer}。
     */
    public static String serverCommandPrefix() {
        return PlatformManager.getPlatform().getServerCommandPrefix();
    }

    /**
     * 显示帮助信息（使用当前平台命令前缀）
     */
    public static void showHelp(Consumer<net.minecraft.network.chat.Component> sender) {
        showHelp(sender, serverCommandPrefix());
    }

    /**
     * 显示帮助信息
     *
     * @param prefix 服务端命令字面量（不含 /）
     */
    public static void showHelp(Consumer<net.minecraft.network.chat.Component> sender, String prefix) {
        sender.accept(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_dim", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_region", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_force", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.status", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_off", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_tick", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_scheduled", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.reloadconfig", prefix));
    }

    /**
     * 向发送者报告当前增量更新工作模式（配置值 + 运行信息）。
     */
    public static void showIncrementalMode(Consumer<net.minecraft.network.chat.Component> sender) {
        sender.accept(incrementalStatusMessage());
        sender.accept(ChatUtils.desc(
                "mapsyncer.command.incremental_status_hint", serverCommandPrefix()));
    }

    /**
     * 生成状态（带前缀），与 {@link #incrementalStatusMessage()} 共用 lang 组件。
     */
    public static MutableComponent generationStatusMessage() {
        if (ConversionOrchestrator.isRunning()) {
            return ChatUtils.message("mapsyncer.generate.in_progress",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus());
        }
        return ChatUtils.message("mapsyncer.generate.no_progress");
    }

    /**
     * 增量更新状态（带前缀）。按配置模式报告，不因 handler 未跑而误报“未启用”。
     */
    public static MutableComponent incrementalStatusMessage() {
        var platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        IncrementalUpdateHandlerLogic handler = IncrementalUpdateHandlerLogic.getInstance();

        if (mode == UpdateMode.TICK) {
            int interval = platform.getIncrementalUpdateIntervalTicks();
            int remainingTicks = handler.isRunning()
                    ? Math.max(0, interval - handler.getTickCounter())
                    : interval;
            int remainingSeconds = remainingTicks / 20;
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            return ChatUtils.message(
                    "mapsyncer.command.incremental_status_tick",
                    interval, interval / 20.0f, minutes, seconds);
        }
        if (mode == UpdateMode.SCHEDULED) {
            int hour = platform.getScheduledUpdateHour();
            int minute = platform.getScheduledUpdateMinute();
            return ChatUtils.message(
                    "mapsyncer.command.incremental_status_scheduled", hour, minute);
        }
        return ChatUtils.message("mapsyncer.command.incremental_status_disabled");
    }

    /**
     * 生成所有维度的地图缓存
     * @return true 任务已提交，false 已有转换任务在运行
     */
    public static boolean generateAll(MinecraftServer server, Runnable onSuccess) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateAll command");
            return false;
        }
        // Save chunks on server thread before dispatching heavy I/O to background
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateAll(server) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    /**
     * 生成指定维度的地图缓存
     * @return true 任务已提交，false 已有转换任务在运行
     */
    public static boolean generateDimension(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimension command");
            return false;
        }
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateDimension(server, dimensionId) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    /**
     * 强制重新生成指定维度的地图缓存
     * @return true 任务已提交，false 已有转换任务在运行
     */
    public static boolean generateDimensionForce(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimensionForce command");
            return false;
        }
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateDimensionForce(server, dimensionId) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    /**
     * 检查区域是否存在
     */
    public static boolean checkRegionExists(MinecraftServer server, ResourceKey<Level> dimension, int x, int z) {
        return ConversionOrchestrator.checkMcaFileExists(server, dimension, x, z) != null;
    }

    /**
     * 生成单个区域的地图缓存
     */
    public static boolean generateSingleRegion(MinecraftServer server, ResourceKey<Level> dimension, int x, int z,
                                            Consumer<SingleRegionResult> resultHandler) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateSingleRegion command");
            resultHandler.accept(SingleRegionResult.ALREADY_RUNNING);
            return false;
        }
        // Save chunks on server thread before dispatching heavy I/O to background
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            SingleRegionResult result = ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            if (resultHandler != null) {
                resultHandler.accept(result);
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    /**
     * 获取缓存统计信息
     */
    public static List<DimensionCacheStats> getCacheStats() {
        return ConversionOrchestrator.getCacheStats();
    }

    /**
     * 获取已完成的维度列表
     */
    public static List<String> getCompletedDimensions() {
        return ConversionOrchestrator.getCompletedDimensions();
    }

    /**
     * 获取处理计数
     */
    public static int getProcessedCount() {
        return ConversionOrchestrator.getProcessedCount();
    }

    /**
     * 获取总计数
     */
    public static int getTotalCount() {
        return ConversionOrchestrator.getTotalCount();
    }

    /**
     * 获取更新计数
     */
    public static int getUpdatedCount() {
        return ConversionOrchestrator.getUpdatedCount();
    }

    // ===== 配置操作 =====

    /**
     * 禁用增量更新
     */
    public static void disableIncremental() {
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateMode(UpdateMode.DISABLED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    /**
     * 设置TICK模式增量更新
     */
    public static void setIncrementalTick(MinecraftServer server) {
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateMode(UpdateMode.TICK);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    /**
     * 设置TICK模式增量更新并指定间隔
     */
    public static void setIncrementalTick(MinecraftServer server, int interval) {
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateIntervalTicks(interval);
        platform.setIncrementalUpdateMode(UpdateMode.TICK);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    /**
     * 设置SCHEDULED模式增量更新
     */
    public static void setIncrementalScheduled(MinecraftServer server) {
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateMode(UpdateMode.SCHEDULED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    /**
     * 设置SCHEDULED模式并指定小时
     */
    public static void setScheduledTime(MinecraftServer server, int hour) {
        var platform = PlatformManager.getPlatform();
        platform.setScheduledUpdateHour(hour);
        platform.setIncrementalUpdateMode(UpdateMode.SCHEDULED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    /**
     * 设置SCHEDULED模式并指定完整时间
     */
    public static void setScheduledTime(MinecraftServer server, int hour, int minute) {
        var platform = PlatformManager.getPlatform();
        platform.setScheduledUpdateHour(hour);
        platform.setScheduledUpdateMinute(minute);
        platform.setIncrementalUpdateMode(UpdateMode.SCHEDULED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    /**
     * 获取当前配置的增量更新模式
     */
    public static UpdateMode getIncrementalUpdateMode() {
        return PlatformManager.getPlatform().getIncrementalUpdateMode();
    }

    /**
     * 获取当前配置的增量更新间隔
     */
    public static int getIncrementalUpdateIntervalTicks() {
        return PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
    }

    /**
     * 获取当前配置的定时更新小时
     */
    public static int getScheduledUpdateHour() {
        return PlatformManager.getPlatform().getScheduledUpdateHour();
    }

    /**
     * 获取当前配置的定时更新分钟
     */
    public static int getScheduledUpdateMinute() {
        return PlatformManager.getPlatform().getScheduledUpdateMinute();
    }

    /**
     * 从磁盘重新加载服务端配置，并重置维度注册与增量更新调度。
     */
    public static boolean reloadConfig(MinecraftServer server) {
        try {
            PlatformManager.getPlatform().reloadConfig();
            ModLogConfig.applyDebugLogging();
            DimensionRegistry.resetRegistration();
            DimensionConfigParser.invalidateCache();

            // 空闲时重建线程池，使 maxConcurrentRegions 在本会话立即生效
            if (!ConversionOrchestrator.isRunning()) {
                ConversionOrchestrator.shutdownExecutor();
            }

            IncrementalUpdateHandlerLogic handler = IncrementalUpdateHandlerLogic.getInstance();
            handler.stop();
            UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
            if (mode != UpdateMode.DISABLED && server != null) {
                handler.start(server);
            }

            LOGGER.info("Server configuration reloaded");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to reload server configuration", e);
            return false;
        }
    }

    /**
     * 获取友好的维度名称
     */
    public static String getFriendlyDimensionName(ResourceKey<Level> dimension) {
        return DimensionPathMapping.getInstance().getFriendlyName(DimensionApiHelper.getDimId(dimension));
    }

    /**
     * 获取维度ID字符串
     */
    public static String getDimensionId(ResourceKey<Level> dimension) {
        return DimensionApiHelper.getDimId(dimension);
    }
}
