package com.mapsyncer.server;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * 缓存命令处理器 - 平台无关的命令逻辑
 *
 * 各平台模块的命令类调用此类的静态方法执行实际业务逻辑，
 * 仅负责命令注册和参数解析。
 */
public class CacheCommandHandler {

    /**
     * 显示帮助信息
     */
    public static void showHelp(Consumer<net.minecraft.network.chat.Component> sender) {
        sender.accept(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_dim"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_region"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_force"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.status"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_off"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_tick"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_scheduled"));
    }

    /**
     * 生成所有维度的地图缓存
     */
    public static void generateAll(MinecraftServer server, Runnable onSuccess) {
        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateAll(server);
            if (onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
    }

    /**
     * 生成指定维度的地图缓存
     */
    public static void generateDimension(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateDimension(server, dimensionId);
            if (onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
    }

    /**
     * 强制重新生成指定维度的地图缓存
     */
    public static void generateDimensionForce(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateDimensionForce(server, dimensionId);
            if (onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
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
    public static void generateSingleRegion(MinecraftServer server, ResourceKey<Level> dimension, int x, int z,
                                            Consumer<SingleRegionResult> resultHandler) {
        Thread worker = new Thread(() -> {
            SingleRegionResult result = ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            if (resultHandler != null) {
                resultHandler.accept(result);
            }
        }, "xaero-map-generator");
        worker.start();
    }

    /**
     * 获取生成状态信息
     */
    public static String getGenerationStatus() {
        if (ConversionOrchestrator.isRunning()) {
            return String.format("转换进行中：%d/%d 个区域 - %s",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus());
        }
        return "无转换任务";
    }

    /**
     * 获取增量更新状态信息
     */
    public static String getIncrementalStatus() {
        var platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        IncrementalUpdateHandlerLogic handler = IncrementalUpdateHandlerLogic.getInstance();

        if (mode == UpdateMode.DISABLED || !handler.isRunning()) {
            return "增量更新未启用";
        } else if (mode == UpdateMode.TICK) {
            int interval = platform.getIncrementalUpdateIntervalTicks();
            int remainingTicks = interval - handler.getTickCounter();
            int remainingSeconds = remainingTicks / 20;
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            return String.format("增量更新TICK模式，下次 %d分%d秒后", minutes, seconds);
        } else if (mode == UpdateMode.SCHEDULED) {
            int hour = platform.getScheduledUpdateHour();
            int minute = platform.getScheduledUpdateMinute();
            return String.format("增量更新定时模式，每日 %02d:%02d", hour, minute);
        }
        return "增量更新未启用";
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
     * 获取友好的维度名称
     */
    public static String getFriendlyDimensionName(ResourceKey<Level> dimension) {
        return DimensionPathMapping.getInstance().getFriendlyName(dimension.identifier().getPath());
    }

    /**
     * 获取维度ID字符串
     */
    public static String getDimensionId(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }
}
