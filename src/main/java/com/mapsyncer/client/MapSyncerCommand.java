package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mapsyncer.client.ClientHashManager.ClientMeta;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
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

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class MapSyncerCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommand.class);

    private static MutableComponent prefix() {
        return Component.translatable("mapsyncer.prefix").withStyle(style -> style.withColor(0xFFE55E));
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                net.minecraft.commands.Commands.literal("mapsyncer")
                        .executes(MapSyncerCommand::showHelp)
                        .then(net.minecraft.commands.Commands.literal("help")
                                .executes(MapSyncerCommand::showHelp))
                        .then(net.minecraft.commands.Commands.literal("sync")
                                .executes(MapSyncerCommand::executeSyncCurrentDim)
                                .then(net.minecraft.commands.Commands.literal("all")
                                        .executes(MapSyncerCommand::executeSyncAll))
                                .then(net.minecraft.commands.Commands.argument("dimension", StringArgumentType.greedyString())
                                        .suggests(MapSyncerCommand::suggestDimensions)
                                        .executes(MapSyncerCommand::executeSyncDimension)))
        );
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        mc.player.displayClientMessage(prefix(), false);
        mc.player.displayClientMessage(Component.literal("用法:").withStyle(s -> s.withColor(0xFFFFFF)), false);
        mc.player.displayClientMessage(Component.literal("  /mapsyncer sync - 同步当前维度").withStyle(s -> s.withColor(0xAAAAAA)), false);
        mc.player.displayClientMessage(Component.literal("  /mapsyncer sync <维度> - 同步指定维度").withStyle(s -> s.withColor(0xAAAAAA)), false);
        mc.player.displayClientMessage(Component.literal("  /mapsyncer sync all - 同步所有维度").withStyle(s -> s.withColor(0xAAAAAA)), false);
        mc.player.displayClientMessage(Component.literal("维度名称: overworld, the_nether, the_end 或完整ID如 twilightforest:twilight_forest").withStyle(s -> s.withColor(0xFFFF55)), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 维度名称建议
     */
    private static CompletableFuture<Suggestions> suggestDimensions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // 原版维度简化名称
        builder.suggest("overworld");
        builder.suggest("the_nether");
        builder.suggest("the_end");
        builder.suggest("all");

        Set<String> added = new HashSet<>();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null) {
            // 当前维度
            ResourceKey<Level> currentDim = level.dimension();
            ResourceLocation currentLoc = currentDim.location();
            if (!"minecraft".equals(currentLoc.getNamespace())) {
                String suggestion = currentLoc.toString();
                builder.suggest(suggestion);
                added.add(suggestion);
            }

            // 从 DIMENSION_TYPE 注册表推断 Mod 维度
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

            // 从 LEVEL_STEM 注册表获取维度模板
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

        // 扫描 Xaero 目录
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

    /**
     * Xaero 目录名转换为维度 ID
     */
    private static String xaeroDirToDimensionId(String dirName) {
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";
        if (dirName.contains("$")) return dirName.replace('$', ':');
        if (dirName.startsWith("DIM")) return "";
        return dirName;
    }

    /**
     * 同步指定维度（字符串参数）
     */
    private static int executeSyncDimension(CommandContext<CommandSourceStack> context) {
        String dimInput = StringArgumentType.getString(context, "dimension");

        // 特殊处理 all
        if ("all".equalsIgnoreCase(dimInput)) {
            return executeSyncAll(context);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        // 解析维度 ID
        String dimensionId = resolveDimensionId(dimInput, mc.level);

        mc.player.displayClientMessage(
                prefix().append(Component.literal("开始同步维度: " + dimensionId).withStyle(s -> s.withColor(0xFFFFFF))),
                false);

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 同步所有维度
     */
    private static int executeSyncAll(CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        mc.player.displayClientMessage(
                prefix().append(Component.literal("开始同步所有维度...").withStyle(s -> s.withColor(0xFFFFFF))),
                false);

        sendSyncRequest(mc, "all", true);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 同步当前维度
     */
    private static int executeSyncCurrentDim(CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        ResourceKey<Level> currentDim = mc.level.dimension();
        String dimensionId = currentDim.location().toString();

        mc.player.displayClientMessage(
                prefix().append(Component.literal("同步当前维度: " + dimensionId).withStyle(s -> s.withColor(0xFFFFFF))),
                false);

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 解析用户输入的维度名称为完整维度 ID
     */
    private static String resolveDimensionId(String input, ClientLevel level) {
        // 简化名称映射
        switch (input.toLowerCase()) {
            case "overworld": return "minecraft:overworld";
            case "nether": case "the_nether": return "minecraft:the_nether";
            case "end": case "the_end": return "minecraft:the_end";
        }

        // 已是完整 ID
        if (input.contains(":")) return input;

        // 尝试从注册表查找 namespace
        // 输入只有 path 部分，需要推断 namespace
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

        // 默认添加 minecraft 前缀
        return "minecraft:" + input;
    }

    
    /**
     * 发送同步请求
     */
    private static void sendSyncRequest(Minecraft mc, String dimensionId, boolean syncAll) {
        Map<String, ClientMeta> metaMap;

        // 直接获取服务器目录（Multiplayer_<serverIP>）
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

        if (metaMap.isEmpty()) {
            mc.player.displayClientMessage(
                    prefix().append(Component.literal("没有需要同步的区域").withStyle(s -> s.withColor(0xFFFFFF))),
                    false);
        } else {
            mc.player.displayClientMessage(
                    prefix().append(Component.literal("检查 " + metaMap.size() + " 个区域...").withStyle(s -> s.withColor(0xFFFFFF))),
                    false);
        }

        PacketDistributor.sendToServer(new PacketHandler.SyncRequestPayload(metaMap));
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