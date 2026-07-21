package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
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
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncAll(false)))
                                .then(net.minecraft.commands.Commands.argument("dimension", DimensionArgument.dimension())
                                        .suggests((ctx, builder) -> { MapSyncerCommandLogic.suggestDimensions(builder); return builder.buildFuture(); })
                                        .executes(ctx -> {
                                            ResourceLocation loc = ctx.getArgument("dimension", ResourceLocation.class);
                                            return MapSyncerCommandLogic.executeSyncDimension(loc.toString());
                                        })))
                        
                        .then(net.minecraft.commands.Commands.literal("autosync")
                                .executes(ctx -> MapSyncerCommandLogic.executeAutoSyncStatus())
                                .then(net.minecraft.commands.Commands.literal("on")
                                        .executes(ctx -> MapSyncerCommandLogic.setClientAutoSync(true)))
                                .then(net.minecraft.commands.Commands.literal("off")
                                        .executes(ctx -> MapSyncerCommandLogic.setClientAutoSync(false))))
        );
    }
}
