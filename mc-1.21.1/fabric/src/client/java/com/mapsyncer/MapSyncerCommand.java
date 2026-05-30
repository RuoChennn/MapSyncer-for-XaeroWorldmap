package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.ResourceLocation;

public class MapSyncerCommand {

    public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("mapsyncer")
                        .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; })
                        .then(ClientCommandManager.literal("help")
                                .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; }))
                        .then(ClientCommandManager.literal("sync")
                                .executes(ctx -> MapSyncerCommandLogic.executeSyncCurrentDim())
                                .then(ClientCommandManager.literal("all")
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncAll()))
                                .then(ClientCommandManager.argument("dimension", DimensionArgument.dimension())
                                        .suggests((ctx, builder) -> { MapSyncerCommandLogic.suggestDimensions(builder); return builder.buildFuture(); })
                                        .executes(ctx -> {
                                            ResourceLocation loc = ctx.getArgument("dimension", ResourceLocation.class);
                                            return MapSyncerCommandLogic.executeSyncDimension(loc.toString());
                                        })))
                        .then(ClientCommandManager.literal("clearstate")
                                .requires(source -> false)
                                .executes(ctx -> MapSyncerCommandLogic.clearSyncState()))
        );
    }
}
