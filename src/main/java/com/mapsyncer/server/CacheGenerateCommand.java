package com.mapsyncer.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.ResourceKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 缓存生成命令 - 注册和处理/mapsyncer命令
 *
 * 提供以下命令：
 * - /mapsyncer help - 显示帮助信息
 * - /mapsyncer generate - 生成所有维度的地图缓存
 * - /mapsyncer generate <dimension> - 生成指定维度的地图缓存
 * - /mapsyncer generate <dimension> <x> <z> - 生成指定区域的地图缓存
 * - /mapsyncer generate <dimension> force - 强制重新生成指定维度
 * - /mapsyncer status - 显示当前生成状态
 * - /mapsyncer incremental off/tick/scheduled/status - 配置增量更新模式
 *
 * 需要管理员权限（permission level 4）才能执行。
 */
public class CacheGenerateCommand {

    /**
     * 注册命令到命令分发器
     *
     * @param dispatcher Brigadier命令分发器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mapsyncer")
                .requires(source -> source.hasPermission(4))
                .executes(CacheGenerateCommand::showHelp)
                .then(Commands.literal("help")
                        .executes(CacheGenerateCommand::showHelp))
                .then(Commands.literal("generate")
                        .executes(CacheGenerateCommand::generateAll)
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(CacheGenerateCommand::generateDimension)
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(CacheGenerateCommand::generateSingleRegion)))
                                .then(Commands.literal("force")
                                        .executes(CacheGenerateCommand::generateDimensionForce))))
                .then(Commands.literal("status")
                        .executes(CacheGenerateCommand::showStatus))
                .then(Commands.literal("incremental")
                        .then(Commands.literal("off")
                                .executes(CacheGenerateCommand::setIncrementalOff))
                        .then(Commands.literal("tick")
                                .executes(CacheGenerateCommand::setIncrementalTick)
                                .then(Commands.argument("interval", IntegerArgumentType.integer(20, 72000))
                                        .executes(CacheGenerateCommand::setIncrementalTickInterval)))
                        .then(Commands.literal("scheduled")
                                .executes(CacheGenerateCommand::setIncrementalScheduled)
                                .then(Commands.argument("hour", IntegerArgumentType.integer(0, 23))
                                        .executes(CacheGenerateCommand::setScheduledTimeDefaultMinute)
                                        .then(Commands.argument("minute", IntegerArgumentType.integer(0, 59))
                                                .executes(CacheGenerateCommand::setScheduledTime))))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_dim"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_region"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_force"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.status"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_off"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_tick"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_scheduled"), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 生成所有维度的地图缓存
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);

        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateAll(server);
            String dimList = String.join(", ", ConversionOrchestrator.getCompletedDimensions());
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.full_complete",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getCompletedDimensions().size(),
                    dimList), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 生成指定维度的地图缓存
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     * @throws CommandSyntaxException 如果维度参数解析失败
     */
    private static int generateDimension(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();
        String dimensionId = dimension.location().toString();
        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_dim", friendlyName), false);

        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateDimension(server, dimensionId);
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.dim_complete",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getUpdatedCount()), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 强制重新生成指定维度的地图缓存
     *
     * 清除维度缓存目录后重新生成所有区域。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     * @throws CommandSyntaxException 如果维度参数解析失败
     */
    private static int generateDimensionForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();
        String dimensionId = dimension.location().toString();
        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_force", friendlyName), false);

        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateDimensionForce(server, dimensionId);
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.force_complete",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getUpdatedCount()), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 生成单个区域的地图缓存
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     * @throws CommandSyntaxException 如果参数解析失败
     */
    private static int generateSingleRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        MinecraftServer server = ctx.getSource().getServer();

        if (ConversionOrchestrator.checkMcaFileExists(server, dimension, x, z) == null) {
            String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_not_found", x, z, friendlyName));
            return 0;
        }

        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.command.generating_region", x, z, friendlyName), false);

        Thread worker = new Thread(() -> {
            SingleRegionResult result = ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            if (result == SingleRegionResult.SUCCESS) {
                ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.region_converted"), false);
            } else if (result == SingleRegionResult.CONVERSION_FAILED) {
                ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_conversion_failed", x, z));
            }
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 显示当前生成状态和增量更新状态（合并为一行）
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        IncrementalUpdateHandler handler = IncrementalUpdateHandler.getInstance();
        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();

        // 构建完整状态消息
        String genStatus;
        String incStatus;

        if (ConversionOrchestrator.isRunning()) {
            genStatus = String.format("转换进行中：%d/%d 个区域 - %s",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus());
        } else {
            genStatus = "无转换任务";
        }

        if (mode == UpdateMode.DISABLED || !handler.isRunning()) {
            incStatus = "增量更新未启用";
        } else if (mode == UpdateMode.TICK) {
            int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
            int remainingTicks = interval - handler.getTickCounter();
            int remainingSeconds = remainingTicks / 20;
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            incStatus = String.format("增量更新TICK模式，下次 %d分%d秒后", minutes, seconds);
        } else if (mode == UpdateMode.SCHEDULED) {
            int hour = ModConfig.SERVER.scheduledUpdateHour.get();
            int minute = ModConfig.SERVER.scheduledUpdateMinute.get();
            incStatus = String.format("增量更新定时模式，每日 %02d:%02d", hour, minute);
        } else {
            incStatus = "增量更新未启用";
        }

        // 合并为一行显示
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.combined", genStatus, incStatus), false);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 禁用增量更新
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalOff(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.DISABLED);
        saveConfig();
        IncrementalUpdateHandler.getInstance().stop();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置TICK模式增量更新
     *
     * 使用配置中默认的tick间隔。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalTick(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.TICK);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_set", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置TICK模式增量更新并指定间隔
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalTickInterval(CommandContext<CommandSourceStack> ctx) {
        int interval = IntegerArgumentType.getInteger(ctx, "interval");
        ModConfig.SERVER.incrementalUpdateIntervalTicks.set(interval);
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.TICK);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_interval", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置SCHEDULED模式增量更新
     *
     * 使用配置中默认的计划时间。
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setIncrementalScheduled(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.SCHEDULED);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int hour = ModConfig.SERVER.scheduledUpdateHour.get();
        int minute = ModConfig.SERVER.scheduledUpdateMinute.get();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置SCHEDULED模式并指定小时（使用默认分钟）
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setScheduledTimeDefaultMinute(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        ModConfig.SERVER.scheduledUpdateHour.set(hour);
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.SCHEDULED);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int minute = ModConfig.SERVER.scheduledUpdateMinute.get();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 设置SCHEDULED模式并指定完整时间
     *
     * @param ctx 命令上下文
     * @return 命令执行结果
     */
    private static int setScheduledTime(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        int minute = IntegerArgumentType.getInteger(ctx, "minute");
        ModConfig.SERVER.scheduledUpdateHour.set(hour);
        ModConfig.SERVER.scheduledUpdateMinute.set(minute);
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.SCHEDULED);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 保存配置文件
     */
    private static void saveConfig() {
        ModConfig.SERVER_SPEC.save();
    }
}