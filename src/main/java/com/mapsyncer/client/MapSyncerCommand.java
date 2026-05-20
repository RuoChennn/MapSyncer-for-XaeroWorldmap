package com.mapsyncer.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mapsyncer.client.ClientHashManager.ClientMeta;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import java.util.Map;
import java.util.stream.Stream;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class MapSyncerCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommand.class);

    /**
     * 创建带颜色的前缀组件
     */
    private static MutableComponent prefix() {
        return Component.translatable("mapsyncer.prefix").withStyle(style -> style.withColor(0xFFE55E)); // 黄色
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
                                .then(net.minecraft.commands.Commands.argument("dim", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            // 原版维度标准名称
                                            builder.suggest("overworld");
                                            builder.suggest("the_nether");
                                            builder.suggest("the_end");
                                            builder.suggest("all");

                                            // 扫描 Xaero 目录列出已有维度数据
                                            Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
                                            if (baseDir != null) {
                                                try (Stream<Path> dirs = Files.list(baseDir)) {
                                                    dirs.filter(p -> Files.isDirectory(p))
                                                        .filter(p -> !p.getFileName().toString().startsWith("mw$"))
                                                        .forEach(p -> {
                                                            String dimName = p.getFileName().toString();
                                                            // 将 Xaero 目录名转换为维度建议
                                                            String suggestion = xaeroDirToDimensionSuggestion(dimName);
                                                            builder.suggest(suggestion);
                                                        });
                                                } catch (IOException e) {
                                                    LOGGER.debug("Failed to scan Xaero directory for dimensions", e);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(MapSyncerCommand::executeSync))
                                .executes(MapSyncerCommand::executeSyncCurrentDim))
        );
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        mc.player.displayClientMessage(prefix(), false);
        mc.player.displayClientMessage(Component.translatable("mapsyncer.help.client.header").withStyle(s -> s.withColor(0xFFFFFF)), false);
        mc.player.displayClientMessage(Component.translatable("mapsyncer.help.client.sync").withStyle(s -> s.withColor(0xAAAAAA)), false);
        mc.player.displayClientMessage(Component.translatable("mapsyncer.help.client.sync_dim").withStyle(s -> s.withColor(0xAAAAAA)), false);
        mc.player.displayClientMessage(Component.translatable("mapsyncer.help.client.sync_all").withStyle(s -> s.withColor(0xAAAAAA)), false);
        mc.player.displayClientMessage(Component.translatable("mapsyncer.help.client.note").withStyle(s -> s.withColor(0xFFFF55)), false);
        return 1;
    }

    /**
     * 将 Xaero 的维度目录名转换为维度建议名称（使用标准名称）
     */
    private static String xaeroDirToDimensionSuggestion(String dirName) {
        // 使用统一映射获取服务端维度名（已经是标准名称）
        String serverDim = DimensionPathMapping.getInstance().toServerDimension(dirName);
        return serverDim;
    }

    private static int executeSync(CommandContext<CommandSourceStack> context) {
        String dim = StringArgumentType.getString(context, "dim");
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            return 0;
        }

        // 验证维度参数
        String normalizedDim = normalizeDimension(dim);
        if (normalizedDim == null) {
            mc.player.displayClientMessage(
                    prefix().append(Component.translatable("mapsyncer.command.invalid_dimension", dim).withStyle(style -> style.withColor(0xFF5555))),
                    false);
            return 0;
        }

        // 获取地图目录（可能为 null，但仍然可以发送请求）
        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();

        // 加载缓存并发送同步请求
        mc.player.displayClientMessage(
                prefix().append(Component.translatable("mapsyncer.command.sync_dimension", normalizedDim).withStyle(style -> style.withColor(0xFFFFFF))),
                false);

        sendSyncRequest(mc, baseDir, normalizedDim);

        return 1;
    }

    private static int executeSyncCurrentDim(CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null) {
            return 0;
        }

        // 获取当前维度
        String currentDim = mc.level.dimension().location().toString();
        String normalizedDim = normalizeDimensionFromResource(currentDim);

        mc.player.displayClientMessage(
                prefix().append(Component.translatable("mapsyncer.command.sync_current", normalizedDim).withStyle(style -> style.withColor(0xFFFFFF))),
                false);

        // 获取地图目录（可能为 null，但仍然可以发送请求）
        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();

        sendSyncRequest(mc, baseDir, normalizedDim);

        return 1;
    }

    private static void sendSyncRequest(Minecraft mc, Path baseDir, String dimension) {
        // 计算本地文件的元数据（时间戳+哈希）
        Map<String, ClientMeta> metaMap;

        // dimension 已是标准名称，转换为 Xaero 格式（服务端缓存目录使用 Xaero 格式）
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim;
        switch (dimension) {
            case "overworld":
                xaeroDim = "null";
                break;
            case "the_nether":
                xaeroDim = "DIM-1";
                break;
            case "the_end":
                xaeroDim = "DIM1";
                break;
            default:
                xaeroDim = dimMapping.toXaeroDimension(dimension);
        }

        // 获取同步时间戳缓存，用于判断是否首次同步
        ClientTimestampCache tsCache = baseDir != null ? ClientTimestampCache.getInstance(baseDir) : null;

        if (dimension.equals("all")) {
            // 同步所有维度
            if (baseDir != null && baseDir.toFile().exists() && tsCache != null && tsCache.cacheFileExists()) {
                // 已同步过，计算哈希
                metaMap = ClientHashManager.computeMetaForSync(baseDir);
                LOGGER.info("Sync all dimensions: found {} cached entries", metaMap.size());
            } else {
                // 首次同步，发送空元数据请求全部数据
                metaMap = new java.util.HashMap<>();
                LOGGER.info("First time sync for all dimensions, sending empty request");
            }
        } else {
            // 单维度同步
            if (tsCache != null && tsCache.cacheFileExists() && tsCache.hasDimensionSynced(xaeroDim)) {
                // 该维度已同步过，计算哈希
                Path targetDir = getDimensionDir(baseDir, dimension);
                Path mwDir = findMwDir(targetDir);
                if (mwDir != null) {
                    metaMap = ClientHashManager.computeMetaForSync(mwDir);
                    LOGGER.info("Dimension {} (xaero: {}) previously synced, found {} cached entries",
                            dimension, xaeroDim, metaMap.size());
                } else {
                    // 缓存文件存在但没有 mw$ 目录（异常情况）
                    metaMap = new java.util.HashMap<>();
                    metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                    LOGGER.warn("Dimension {} has sync cache but no mw$ directory, sending placeholder", dimension);
                }
            } else {
                // 首次同步该维度，发送 placeholder
                metaMap = new java.util.HashMap<>();
                metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                LOGGER.info("First time sync for dimension {} (xaero: {}), sending placeholder", dimension, xaeroDim);
            }
        }

        LOGGER.info("Sending sync request with {} region metadata", metaMap.size());

        if (metaMap.isEmpty()) {
            mc.player.displayClientMessage(
                    prefix().append(Component.translatable("mapsyncer.command.no_regions").withStyle(style -> style.withColor(0xFFFFFF))),
                    false);
        } else {
            mc.player.displayClientMessage(
                    prefix().append(Component.translatable("mapsyncer.command.checking_regions", metaMap.size()).withStyle(style -> style.withColor(0xFFFFFF))),
                    false);
        }

        // 发送同步请求（包含时间戳和哈希）
        PacketDistributor.sendToServer(new PacketHandler.SyncRequestPayload(metaMap));

        // 开始进度追踪
        SyncProgressTracker.startTracking();
    }

    private static String normalizeDimension(String dim) {
        String lower = dim.toLowerCase();
        // 原版维度：支持多种输入格式，统一转换为标准名称
        switch (lower) {
            case "overworld", "minecraft:overworld", "null":
                return "overworld";
            case "nether", "the_nether", "minecraft:the_nether", "dim-1":
                return "the_nether";
            case "end", "the_end", "minecraft:the_end", "dim1":
                return "the_end";
            case "all", "*":
                return "all";
        }

        // Mod 维度：使用统一映射转换
        return DimensionPathMapping.getInstance().toServerDimension(dim);
    }

    private static String normalizeDimensionFromResource(String resourceLocation) {
        // 使用统一映射转换（已经是标准名称）
        return DimensionPathMapping.getInstance().toServerDimension(resourceLocation);
    }

    private static Path getDimensionDir(Path baseDir, String dimension) {
        if (dimension.equals("all")) {
            return baseDir;
        }

        // dimension 已经是标准名称，直接使用统一映射获取 Xaero 目录名
        String xaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(dimension);
        return baseDir.resolve(xaeroDim);
    }

    private static Path findMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) {
            return null;
        }

        // 查找 mw$ 目录
        try {
            return java.nio.file.Files.list(dimDir)
                    .filter(p -> p.getFileName().toString().startsWith("mw$"))
                    .findFirst()
                    .orElse(null);
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to find mw$ directory", e);
            return null;
        }
    }
}