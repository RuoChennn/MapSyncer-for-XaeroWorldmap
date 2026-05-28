package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 地图同步命令处理器 - Fabric 1.20.1 版本
 *
 * 注意：Fabric 版本使用 ClientCommandRegistrationCallback 注册命令，
 * 实际注册在 MapSyncerClient 中完成。
 */
public class MapSyncerCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommand.class);

    /**
     * 注册客户端命令
     */
    public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("mapsyncer")
                        .executes(MapSyncerCommand::showHelp)
                        .then(ClientCommandManager.literal("help")
                                .executes(MapSyncerCommand::showHelp))
                        .then(ClientCommandManager.literal("sync")
                                .executes(MapSyncerCommand::executeSyncCurrentDim)
                                .then(ClientCommandManager.literal("all")
                                        .executes(MapSyncerCommand::executeSyncAll))
                                .then(ClientCommandManager.argument("dimension", StringArgumentType.greedyString())
                                        .suggests(MapSyncerCommand::suggestDimensions)
                                        .executes(MapSyncerCommand::executeSyncDimension)))
                        .then(ClientCommandManager.literal("clearstate")
                                .requires(source -> false)
                                .executes(MapSyncerCommand::clearSyncState))
        );
    }

    private static int showHelp(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        mc.player.displayClientMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.command.help_header")), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_dim"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_all"), false);
        mc.player.displayClientMessage(ChatUtils.header("mapsyncer.command.help_dimension_note"), false);

        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggestDimensions(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        builder.suggest("overworld");
        builder.suggest("the_nether");
        builder.suggest("the_end");
        builder.suggest("all");

        Set<String> added = new HashSet<>();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null) {
            ResourceKey<Level> currentDim = level.dimension();
            ResourceLocation currentLoc = currentDim.location();
            if (!"minecraft".equals(currentLoc.getNamespace())) {
                String suggestion = currentLoc.toString();
                builder.suggest(suggestion);
                added.add(suggestion);
            }

            level.registryAccess().registry(Registries.DIMENSION_TYPE).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    ResourceLocation loc = key.location();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;

                    String path = loc.getPath();
                    String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                    String suggestion = namespace + ":" + dimPath;
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });

            level.registryAccess().registry(Registries.LEVEL_STEM).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    ResourceLocation loc = key.location();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;
                    String suggestion = loc.toString();
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });
        }

        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
        if (baseDir != null) {
            try (Stream<Path> dirs = Files.list(baseDir)) {
                dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("mw$"))
                    .forEach(p -> {
                        String dirName = p.getFileName().toString();
                        String suggestion = xaeroDirToDimensionId(dirName);
                        if (suggestion != null && !suggestion.isEmpty() && !added.contains(suggestion)) {
                            builder.suggest(suggestion);
                            added.add(suggestion);
                        }
                    });
            } catch (IOException e) {
                LOGGER.debug("Failed to scan Xaero directory", e);
            }
        }

        return builder.buildFuture();
    }

    private static String xaeroDirToDimensionId(String dirName) {
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";
        if (dirName.contains("$")) return dirName.replace('$', ':');
        if (dirName.startsWith("DIM")) return "";
        return dirName;
    }

    private static int executeSyncDimension(CommandContext<FabricClientCommandSource> context) {
        String dimInput = StringArgumentType.getString(context, "dimension");

        if ("all".equalsIgnoreCase(dimInput)) {
            return executeSyncAll(context);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = resolveDimensionId(dimInput, mc.level);

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    private static int executeSyncAll(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        sendSyncRequest(mc, "all", true);

        return Command.SINGLE_SUCCESS;
    }

    private static int executeSyncCurrentDim(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        ResourceKey<Level> currentDim = mc.level.dimension();
        String dimensionId = currentDim.location().toString();

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    private static int clearSyncState(CommandContext<FabricClientCommandSource> context) {
        SyncResumeHelper.clearSyncState();
        return Command.SINGLE_SUCCESS;
    }

    private static String resolveDimensionId(String input, ClientLevel level) {
        switch (input.toLowerCase()) {
            case "overworld": return "minecraft:overworld";
            case "nether": case "the_nether": return "minecraft:the_nether";
            case "end": case "the_end": return "minecraft:the_end";
        }

        if (input.contains(":")) return input;

        var optRegistry = level.registryAccess().registry(Registries.DIMENSION_TYPE);
        if (optRegistry.isPresent()) {
            var registry = optRegistry.get();
            for (var key : registry.registryKeySet()) {
                ResourceLocation loc = key.location();
                if ("minecraft".equals(loc.getNamespace())) continue;
                String path = loc.getPath();
                String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                if (dimPath.equals(input) || path.equals(input)) {
                    return loc.getNamespace() + ":" + dimPath;
                }
            }
        }

        return "minecraft:" + input;
    }

    private static void sendSyncRequest(Minecraft mc, String dimensionId, boolean syncAll) {
        Map<String, ClientMeta> metaMap;

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();

        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim = syncAll ? null : dimMapping.toXaeroDimension(dimensionId);

        if (syncAll) {
            if (serverDir != null && tsCache != null && tsCache.cacheFileExists()) {
                metaMap = ClientHashManager.computeMetaForSync(serverDir);
                LOGGER.info("Sync all: {} cached entries", metaMap.size());
            } else {
                metaMap = new java.util.HashMap<>();
                LOGGER.info("First sync all, sending empty request");
            }
        } else {
            if (tsCache != null && tsCache.cacheFileExists() && tsCache.hasDimensionSynced(xaeroDim)) {
                Path dimDir = serverDir.resolve(xaeroDim);
                Path mwDir = findMwDir(dimDir);
                if (mwDir != null) {
                    metaMap = ClientHashManager.computeMetaForSync(mwDir);
                    LOGGER.info("Dimension {} previously synced, {} entries", dimensionId, metaMap.size());
                } else {
                    metaMap = new java.util.HashMap<>();
                    metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                    LOGGER.warn("Dimension {} has cache but no mw$ dir", dimensionId);
                }
            } else {
                metaMap = new java.util.HashMap<>();
                metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                LOGGER.info("First sync for {}", dimensionId);
            }
        }

        LOGGER.info("Sending sync request with {} entries (serverDir={})", metaMap.size(), serverDir);

        if (tsCache != null) {
            Set<String> dimensions = new HashSet<>();
            if (syncAll) {
                dimensions.add("all");
            } else {
                dimensions.add(xaeroDim);
            }
            String command = syncAll ? "/mapsyncer sync all" : "/mapsyncer sync " + dimensionId;
            tsCache.markSyncStart(dimensions, command);
        }

        NetworkManager.sendToServer(new SyncRequestPayload(metaMap));
        SyncProgressTracker.startTracking();
    }

    private static Path findMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) return null;
        try {
            return Files.list(dimDir)
                    .filter(p -> p.getFileName().toString().startsWith("mw$"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}