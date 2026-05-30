package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * 客户端命令注册。
 *
 * <p>使用 {@code /mapsyncer} 前缀。服务端命令通过 {@code /mapsyncerserver} 执行。</p>
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
                                .then(ClientCommandManager.literal("all")
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncAll()))
                                .then(ClientCommandManager.argument("dimension", StringArgumentType.word())
                                        .suggests((ctx, builder) -> { MapSyncerCommandLogic.suggestDimensions(builder); return builder.buildFuture(); })
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncDimension(StringArgumentType.getString(ctx, "dimension")))))
                        .then(ClientCommandManager.literal("clearstate")
                                .requires(source -> false)
                                .executes(ctx -> MapSyncerCommandLogic.clearSyncState()))
        );
    }
}
