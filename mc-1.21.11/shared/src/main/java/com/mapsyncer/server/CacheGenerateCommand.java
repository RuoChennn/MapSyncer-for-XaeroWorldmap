package com.mapsyncer.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mapsyncer.security.PermissionGates;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 缓存生成命令 - 注册和处理本地图缓存命令。
 *
 * 提供以下命令：
 * - help - 显示帮助信息
 * - generate - 生成所有维度的地图缓存
 * - generate <dimension> - 生成指定维度的地图缓存
 * - generate <dimension> --force - 强制重新生成指定维度
 * - generate <dimension> <x> <z> - 生成指定区域的地图缓存
 * - status - 显示当前生成状态
 * - incremental off/tick/scheduled - 配置增量更新模式
 *
 * 维度参数使用原版 {@link DimensionArgument}，支持 namespace:path 且可安全序列化。
 *
 * 需要管理员权限（permission level 4）才能执行。
 */
public class CacheGenerateCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String prefix) {
        dispatcher.register(Commands.literal(prefix)
                .requires(PermissionGates::canAdmin)
                .executes(CacheGenerateCommand::showHelp)
                .then(Commands.literal("help")
                        .executes(CacheGenerateCommand::showHelp))
                .then(Commands.literal("generate")
                        .executes(CacheGenerateCommand::generateAll)
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(CacheGenerateCommand::generateDimension)
                                .then(Commands.literal("--force")
                                        .executes(CacheGenerateCommand::generateDimensionForce))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(CacheGenerateCommand::generateSingleRegion)))))
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
        CacheCommandHandler.showHelp(component -> ctx.getSource().sendSuccess(() -> component, false));
        return Command.SINGLE_SUCCESS;
    }

    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (!CacheCommandHandler.generateAll(server, () -> {
            String dimList = String.join(", ", CacheCommandHandler.getCompletedDimensions());
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.full_complete",
                    CacheCommandHandler.getProcessedCount(),
                    CacheCommandHandler.getTotalCount(),
                    CacheCommandHandler.getCompletedDimensions().size(),
                    dimList), false);
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateDimension(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        String dimensionId = CacheCommandHandler.getDimensionId(dimension);
        String friendlyName = CacheCommandHandler.getFriendlyDimensionName(dimension);

        if (!CacheCommandHandler.generateDimension(ctx.getSource().getServer(), dimensionId, () -> {
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.dim_complete",
                    CacheCommandHandler.getProcessedCount(),
                    CacheCommandHandler.getTotalCount(),
                    CacheCommandHandler.getUpdatedCount()), false);
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_dim", friendlyName), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateDimensionForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        String dimensionId = CacheCommandHandler.getDimensionId(dimension);
        String friendlyName = CacheCommandHandler.getFriendlyDimensionName(dimension);

        if (!CacheCommandHandler.generateDimensionForce(ctx.getSource().getServer(), dimensionId, () -> {
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.force_complete",
                    CacheCommandHandler.getProcessedCount(),
                    CacheCommandHandler.getTotalCount(),
                    CacheCommandHandler.getUpdatedCount()), false);
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_force", friendlyName), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateSingleRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();

        if (!CacheCommandHandler.checkRegionExists(server, dimension, x, z)) {
            String friendlyName = CacheCommandHandler.getFriendlyDimensionName(dimension);
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_not_found", x, z, friendlyName));
            return 0;
        }

        String friendlyName = CacheCommandHandler.getFriendlyDimensionName(dimension);

        if (!CacheCommandHandler.generateSingleRegion(server, dimension, x, z, result -> {
            if (result == SingleRegionResult.SUCCESS) {
                ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.region_converted"), false);
            } else if (result == SingleRegionResult.CONVERSION_FAILED) {
                ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_conversion_failed", x, z));
            }
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.command.generating_region", x, z, friendlyName), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        String genStatus = CacheCommandHandler.getGenerationStatus();
        String incStatus = CacheCommandHandler.getIncrementalStatus();

        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.combined", genStatus, incStatus), false);

        List<DimensionCacheStats> cacheStats = CacheCommandHandler.getCacheStats();
        if (!cacheStats.isEmpty()) {
            int totalDims = cacheStats.size();
            int totalRegions = cacheStats.stream().mapToInt(DimensionCacheStats::regionCount).sum();
            long totalSize = cacheStats.stream().mapToLong(DimensionCacheStats::sizeBytes).sum();

            StringBuilder dims = new StringBuilder();
            for (DimensionCacheStats stat : cacheStats) {
                if (dims.length() > 0) dims.append("\n");
                dims.append(String.format("  %s: %d regions, %.2f MB",
                        stat.dimension(), stat.regionCount(), stat.sizeMB()));
            }

            ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.cache_detail",
                    totalDims, totalRegions, String.format("%.2f", totalSize / (1024.0 * 1024.0)),
                    dims.toString()), false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalOff(CommandContext<CommandSourceStack> ctx) {
        CacheCommandHandler.disableIncremental();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalTick(CommandContext<CommandSourceStack> ctx) {
        CacheCommandHandler.setIncrementalTick(ctx.getSource().getServer());
        int interval = CacheCommandHandler.getIncrementalUpdateIntervalTicks();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_set", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalTickInterval(CommandContext<CommandSourceStack> ctx) {
        int interval = IntegerArgumentType.getInteger(ctx, "interval");
        CacheCommandHandler.setIncrementalTick(ctx.getSource().getServer(), interval);
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_interval", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalScheduled(CommandContext<CommandSourceStack> ctx) {
        CacheCommandHandler.setIncrementalScheduled(ctx.getSource().getServer());
        int hour = CacheCommandHandler.getScheduledUpdateHour();
        int minute = CacheCommandHandler.getScheduledUpdateMinute();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setScheduledTimeDefaultMinute(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        CacheCommandHandler.setScheduledTime(ctx.getSource().getServer(), hour);
        int minute = CacheCommandHandler.getScheduledUpdateMinute();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setScheduledTime(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        int minute = IntegerArgumentType.getInteger(ctx, "minute");
        CacheCommandHandler.setScheduledTime(ctx.getSource().getServer(), hour, minute);
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }
}
