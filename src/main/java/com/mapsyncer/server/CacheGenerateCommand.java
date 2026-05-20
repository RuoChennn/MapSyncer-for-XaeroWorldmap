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

public class CacheGenerateCommand {

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
                                                .executes(CacheGenerateCommand::setScheduledTime))))
                        .then(Commands.literal("status")
                                .executes(CacheGenerateCommand::showIncrementalStatus))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_dim"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_region"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_force"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.status"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.incremental_header")), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_off"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_tick"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_scheduled"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_status"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);

        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateAll(server);
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.full_complete",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount()), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

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
                    ConversionOrchestrator.getTotalCount()), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

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
                    ConversionOrchestrator.getTotalCount()), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

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

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        if (ConversionOrchestrator.isRunning()) {
            ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.in_progress",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus()), false);
        } else {
            ctx.getSource().sendSuccess(() -> ChatUtils.prefix().append(ChatUtils.desc("mapsyncer.generate.no_progress")), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalOff(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.DISABLED);
        saveConfig();
        IncrementalUpdateHandler.getInstance().stop();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalTick(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.TICK);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_set", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalTickInterval(CommandContext<CommandSourceStack> ctx) {
        int interval = IntegerArgumentType.getInteger(ctx, "interval");
        ModConfig.SERVER.incrementalUpdateIntervalTicks.set(interval);
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.TICK);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_interval", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalScheduled(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.SCHEDULED);
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int hour = ModConfig.SERVER.scheduledUpdateHour.get();
        int minute = ModConfig.SERVER.scheduledUpdateMinute.get();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

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

    private static void saveConfig() {
        ModConfig.SERVER_SPEC.save();
    }

    private static int showIncrementalStatus(CommandContext<CommandSourceStack> ctx) {
        String status = IncrementalUpdateHandler.getInstance().getStatusInfo();
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.incremental_status", status), false);
        return Command.SINGLE_SUCCESS;
    }
}