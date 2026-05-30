package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * 客户端命令注册。
 *
 * <p>使用 {@code /mapsyncer} 前缀。维度名支持短名（overworld）和完整 namespace:path 格式。
 */
public class MapSyncerCommand {

    public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("mapsyncer")
                        .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; })
                        .then(ClientCommandManager.literal("help")
                                .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; }))
                        .then(ClientCommandManager.literal("sync")
                                .executes(ctx -> MapSyncerCommandLogic.executeSyncCurrentDim())
                                .then(ClientCommandManager.argument("dimension", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> { MapSyncerCommandLogic.suggestDimensions(builder); return builder.buildFuture(); })
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncDimension(StringArgumentType.getString(ctx, "dimension")))))
                        .then(ClientCommandManager.literal("clearstate")
                                .requires(source -> false)
                                .executes(ctx -> MapSyncerCommandLogic.clearSyncState()))
        );
    }
}
