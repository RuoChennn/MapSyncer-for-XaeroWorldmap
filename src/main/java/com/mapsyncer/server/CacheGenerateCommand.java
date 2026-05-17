package com.mapsyncer.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import net.minecraft.resources.ResourceKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

public class CacheGenerateCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mapsyncer")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("generate")
                        .executes(CacheGenerateCommand::generateAll)
                        .then(Commands.argument("dimension", StringArgumentType.string())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("overworld");
                                    builder.suggest("the_nether");
                                    builder.suggest("the_end");
                                    return builder.buildFuture();
                                })
                                .executes(CacheGenerateCommand::generateDimension))
                        .then(Commands.literal("--region")
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
                                                .executes(CacheGenerateCommand::setScheduledTime))))
                        .then(Commands.literal("status")
                                .executes(CacheGenerateCommand::showIncrementalStatus))));
    }

    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> Component.literal("Starting full map generation..."), false);

        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateAll(server);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    String.format("Map generation completed: %d/%d regions",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount())), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

    private static int generateDimension(CommandContext<CommandSourceStack> ctx) {
        String dimensionId = StringArgumentType.getString(ctx, "dimension");
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Starting map generation for dimension: %s", dimensionId)), false);

        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateDimension(server, dimensionId);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    String.format("Dimension generation completed: %d/%d regions",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount())), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

    private static int generateSingleRegion(CommandContext<CommandSourceStack> ctx) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        MinecraftServer server = ctx.getSource().getServer();
        ResourceKey<Level> dimension = ctx.getSource().getLevel().dimension();

        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Starting single region conversion: (%d, %d)", x, z)), false);

        Thread worker = new Thread(() -> {
            ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            ctx.getSource().sendSuccess(() -> Component.literal("Single region conversion completed"), false);
        }, "xaero-map-generator");
        worker.start();

        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        if (ConversionOrchestrator.isRunning()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    String.format("Conversion in progress: %d/%d regions - %s",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount(),
                            ConversionOrchestrator.getStatus())), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("No conversion in progress"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalOff(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.DISABLED);
        IncrementalUpdateHandler.getInstance().stop();
        ctx.getSource().sendSuccess(() -> Component.literal("Incremental updates disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalTick(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.TICK);
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Incremental updates set to TICK mode (interval: %d ticks = %.1f seconds)",
                        interval, interval / 20.0f)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalTickInterval(CommandContext<CommandSourceStack> ctx) {
        int interval = IntegerArgumentType.getInteger(ctx, "interval");
        ModConfig.SERVER.incrementalUpdateIntervalTicks.set(interval);
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.TICK);
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Incremental updates set to TICK mode with interval %d ticks (%.1f seconds)",
                        interval, interval / 20.0f)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalScheduled(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.SCHEDULED);
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int hour = ModConfig.SERVER.scheduledUpdateHour.get();
        int minute = ModConfig.SERVER.scheduledUpdateMinute.get();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Incremental updates set to SCHEDULED mode (daily at %02d:%02d)", hour, minute)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setScheduledTimeDefaultMinute(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        ModConfig.SERVER.scheduledUpdateHour.set(hour);
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.SCHEDULED);
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int minute = ModConfig.SERVER.scheduledUpdateMinute.get();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Incremental updates set to SCHEDULED mode (daily at %02d:%02d)", hour, minute)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setScheduledTime(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        int minute = IntegerArgumentType.getInteger(ctx, "minute");
        ModConfig.SERVER.scheduledUpdateHour.set(hour);
        ModConfig.SERVER.scheduledUpdateMinute.set(minute);
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.SCHEDULED);
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Incremental updates set to SCHEDULED mode (daily at %02d:%02d)", hour, minute)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showIncrementalStatus(CommandContext<CommandSourceStack> ctx) {
        String status = IncrementalUpdateHandler.getInstance().getStatusInfo();
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("Incremental updates: %s", status)), false);
        return Command.SINGLE_SUCCESS;
    }
}