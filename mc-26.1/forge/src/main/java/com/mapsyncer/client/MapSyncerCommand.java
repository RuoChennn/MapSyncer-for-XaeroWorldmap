package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
public class MapSyncerCommand {

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                net.minecraft.commands.Commands.literal("mapsyncer")
                        .executes(ctx -> { MapSyncerCommandLogic.showHelp(ctx.getSource().hasPermission(4)); return Command.SINGLE_SUCCESS; })
                        .then(net.minecraft.commands.Commands.literal("help")
                                .executes(ctx -> { MapSyncerCommandLogic.showHelp(ctx.getSource().hasPermission(4)); return Command.SINGLE_SUCCESS; }))
                        .then(net.minecraft.commands.Commands.literal("sync")
                                .executes(ctx -> MapSyncerCommandLogic.executeSyncCurrentDim())
                                .then(net.minecraft.commands.Commands.literal("all")
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncAll()))
                                .then(net.minecraft.commands.Commands.argument("dimension", StringArgumentType.string())
                                        .suggests((ctx, builder) -> {
                                            MapSyncerCommandLogic.suggestDimensions(builder);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncDimension(
                                                StringArgumentType.getString(ctx, "dimension")))))
                        .then(net.minecraft.commands.Commands.literal("clearstate")
                                .requires(source -> false)
                                .executes(ctx -> MapSyncerCommandLogic.clearSyncState()))
        );
    }
}
