package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.Identifier;

/**
 * 客户端命令注册 - Fabric 26.x 版本
 *
 * 使用 {@code /mapsyncer} 前缀，维度参数使用原版 {@link DimensionArgument}，
 * 支持 namespace:path 格式且 Tab 补全正常工作。
 */
public class MapSyncerCommand {

    public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommands.literal("mapsyncer")
                        .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; })
                        .then(ClientCommands.literal("help")
                                .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; }))
                        .then(ClientCommands.literal("sync")
                                .executes(ctx -> MapSyncerCommandLogic.executeSyncCurrentDim())
                                .then(ClientCommands.literal("all")
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncAll(false)))
                                .then(ClientCommands.argument("dimension", DimensionArgument.dimension())
                                        .suggests((ctx, builder) -> { MapSyncerCommandLogic.suggestDimensions(builder); return builder.buildFuture(); })
                                        .executes(ctx -> {
                                            Identifier loc = ctx.getArgument("dimension", Identifier.class);
                                            return MapSyncerCommandLogic.executeSyncDimension(loc.toString());
                                        })))
                        .then(ClientCommands.literal("autosync")
                                .executes(ctx -> MapSyncerCommandLogic.executeAutoSyncStatus())
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> MapSyncerCommandLogic.setClientAutoSync(true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> MapSyncerCommandLogic.setClientAutoSync(false))))
        );
    }
}
