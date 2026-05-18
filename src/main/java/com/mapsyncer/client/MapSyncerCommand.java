package com.mapsyncer.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mapsyncer.client.ClientHashManager.ClientMeta;
import com.mapsyncer.network.PacketHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class MapSyncerCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommand.class);

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                net.minecraft.commands.Commands.literal("mapsyncer")
                        .then(net.minecraft.commands.Commands.literal("sync")
                                .then(net.minecraft.commands.Commands.argument("dim", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            builder.suggest("overworld");
                                            builder.suggest("nether");
                                            builder.suggest("end");
                                            builder.suggest("all");
                                            return builder.buildFuture();
                                        })
                                        .executes(MapSyncerCommand::executeSync))
                                .executes(MapSyncerCommand::executeSyncCurrentDim))
        );
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
                    Component.literal("无效的维度: " + dim + "。请使用: overworld, nether, end 或 all"),
                    false);
            return 0;
        }

        // 获取地图目录
        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
        if (baseDir == null) {
            mc.player.displayClientMessage(
                    Component.literal("无法确定地图目录。您是否已连接到服务器？"),
                    false);
            return 0;
        }

        // 加载缓存并发送同步请求
        mc.player.displayClientMessage(
                Component.literal("开始同步地图，维度: " + normalizedDim),
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
                Component.literal("开始同步当前维度地图: " + normalizedDim),
                false);

        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
        if (baseDir == null) {
            mc.player.displayClientMessage(
                    Component.literal("无法确定地图目录"),
                    false);
            return 0;
        }

        sendSyncRequest(mc, baseDir, normalizedDim);

        return 1;
    }

    private static void sendSyncRequest(Minecraft mc, Path baseDir, String dimension) {
        // 根据维度获取目标目录
        Path targetDir = getDimensionDir(baseDir, dimension);

        // 计算本地文件的元数据（时间戳+哈希）
        Map<String, ClientMeta> metaMap;
        if (dimension.equals("all")) {
            // 同步所有维度
            metaMap = ClientHashManager.computeMetaForSync(baseDir);
        } else {
            Path mwDir = targetDir != null ? findMwDir(targetDir) : null;
            if (mwDir != null) {
                metaMap = ClientHashManager.computeMetaForSync(mwDir);
            } else {
                metaMap = ClientHashManager.computeMetaForSync(baseDir);
            }
        }

        LOGGER.info("Sending sync request with {} region metadata", metaMap.size());

        if (metaMap.isEmpty()) {
            mc.player.displayClientMessage(
                    Component.literal("未找到现有区域，请求服务器发送全部数据..."),
                    false);
        } else {
            mc.player.displayClientMessage(
                    Component.literal(String.format("正在检查 %d 个区域的更新（哈希+时间戳）...", metaMap.size())),
                    false);
        }

        // 发送同步请求（包含时间戳和哈希）
        PacketDistributor.sendToServer(new PacketHandler.SyncRequestPayload(metaMap));

        // 开始进度追踪
        SyncProgressTracker.startTracking();
    }

    private static String normalizeDimension(String dim) {
        switch (dim.toLowerCase()) {
            case "overworld", "minecraft:overworld", "null":
                return "overworld";
            case "nether", "the_nether", "minecraft:the_nether", "dim-1":
                return "nether";
            case "end", "the_end", "minecraft:the_end", "dim1":
                return "end";
            case "all", "*":
                return "all";
            default:
                return null;
        }
    }

    private static String normalizeDimensionFromResource(String resourceLocation) {
        if (resourceLocation.contains("overworld")) {
            return "overworld";
        } else if (resourceLocation.contains("the_nether")) {
            return "nether";
        } else if (resourceLocation.contains("the_end")) {
            return "end";
        }
        return resourceLocation;
    }

    private static Path getDimensionDir(Path baseDir, String dimension) {
        if (dimension.equals("all")) {
            return baseDir;
        }

        String xaeroDim;
        switch (dimension) {
            case "overworld":
                xaeroDim = "null";
                break;
            case "nether":
                xaeroDim = "DIM-1";
                break;
            case "end":
                xaeroDim = "DIM1";
                break;
            default:
                xaeroDim = dimension;
        }

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