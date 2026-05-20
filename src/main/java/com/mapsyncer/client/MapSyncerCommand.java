package com.mapsyncer.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mapsyncer.client.ClientHashManager.ClientMeta;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
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
                                .then(net.minecraft.commands.Commands.argument("dim", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            // 原版维度标准名称
                                            builder.suggest("overworld");
                                            builder.suggest("the_nether");
                                            builder.suggest("the_end");
                                            builder.suggest("all");

                                            Set<String> addedDimensions = new HashSet<>();

                                            // 方法1：从客户端注册表获取已知维度（需要已连接服务器）
                                            Minecraft mc = Minecraft.getInstance();
                                            if (mc.getConnection() != null && mc.level != null) {
                                                RegistryAccess registryAccess = mc.level.registryAccess();
                                                // 从维度注册表获取所有维度
                                                registryAccess.registry(ResourceKey.createRegistryKey(ResourceLocation.parse("dimension")))
                                                    .ifPresent(registry -> {
                                                        registry.stream().forEach(dimType -> {
                                                            ResourceLocation loc = registry.getKey(dimType);
                                                            if (loc != null && !"minecraft".equals(loc.getNamespace())) {
                                                                // Mod 维度：使用完整 ID (namespace:path)
                                                                String suggestion = loc.toString();
                                                                if (!addedDimensions.contains(suggestion)) {
                                                                    builder.suggest(suggestion);
                                                                    addedDimensions.add(suggestion);
                                                                }
                                                            }
                                                        });
                                                    });
                                            }

                                            // 方法2：扫描 Xaero 目录列出已有维度数据
                                            Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
                                            if (baseDir != null) {
                                                try (Stream<Path> dirs = Files.list(baseDir)) {
                                                    dirs.filter(p -> Files.isDirectory(p))
                                                        .filter(p -> !p.getFileName().toString().startsWith("mw$"))
                                                        .forEach(p -> {
                                                            String dimName = p.getFileName().toString();
                                                            // 将 Xaero 目录名转换为维度建议
                                                            String suggestion = xaeroDirToDimensionSuggestion(dimName);
                                                            if (suggestion != null && !suggestion.isEmpty() && !addedDimensions.contains(suggestion)) {
                                                                builder.suggest(suggestion);
                                                                addedDimensions.add(suggestion);
                                                            }
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
     * 将 Xaero 的维度目录名转换为维度建议名称
     *
     * 统一维度指令格式：
     * - 原版维度：overworld, the_nether, the_end（简化名称）
     * - Mod 维度：namespace:path（完整 ID，如 twilightforest:twilight_forest）
     *
     * Xaero 目录名格式：
     * - 原版：null, DIM-1, DIM1
     * - Mod 新格式：namespace$path（如 twilightforest$twilight_forest）
     * - Mod 传统格式：DIM{id}（如 DIM7）
     */
    private static String xaeroDirToDimensionSuggestion(String dirName) {
        // 原版维度：转换为简化名称
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";

        // Mod 新格式：namespace$path → namespace:path
        if (dirName.contains("$")) {
            return dirName.replace('$', ':');
        }

        // Mod 传统格式：DIM{id} → 保持原样（无法确定 namespace）
        // 用户需要手动输入完整维度 ID
        if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
            // 返回空字符串，不在建议中显示（避免混淆）
            return "";
        }

        // 未知格式，返回原样
        return dirName;
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

        // 获取同步时间戳缓存，用于判断是否首次同步
        ClientTimestampCache tsCache = baseDir != null && baseDir.toFile().exists()
                ? ClientTimestampCache.getInstance(baseDir) : null;

        // dimension 可能是标准名称（如 twilight_forest）或完整 ID（如 twilightforest:twilight_forest）
        // 需要从缓存反向查找正确的 Xaero 格式维度名
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim;

        if (dimension.equals("all")) {
            xaeroDim = null;  // 同步所有维度
        } else {
            // 尝试多种方式获取正确的 Xaero 格式
            xaeroDim = resolveCorrectXaeroDim(dimension, dimMapping, tsCache, baseDir);
            LOGGER.info("Resolved xaeroDim for '{}': {}", dimension, xaeroDim);
        }

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
                // 直接使用已解析的 xaeroDim 作为目录名（不再调用 getDimensionDir）
                Path targetDir = baseDir.resolve(xaeroDim);
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

    /**
     * 标准化维度名称
     *
     * 统一维度指令格式：
     * - 原版维度：overworld, the_nether, the_end（简化名称）
     * - Mod 维度：namespace:path（完整 ID）
     *
     * 输入支持：
     * - 简化名称：overworld, nether, end 等
     * - 完整 ID：minecraft:overworld, twilightforest:twilight_forest 等
     * - 别名：null（主世界），dim-1（地狱），dim1（末地）
     */
    private static String normalizeDimension(String dim) {
        if (dim == null || dim.isEmpty()) {
            return "overworld";
        }

        String lower = dim.toLowerCase();

        // 特殊命令
        if ("all".equals(lower) || "*".equals(lower)) {
            return "all";
        }

        // 原版维度：支持多种输入格式，统一转换为简化名称
        switch (lower) {
            case "overworld", "minecraft:overworld", "null":
                return "overworld";
            case "nether", "the_nether", "minecraft:the_nether", "dim-1":
                return "the_nether";
            case "end", "the_end", "minecraft:the_end", "dim1":
                return "the_end";
        }

        // Mod 维度：
        // - 如果包含冒号，说明已经是完整 ID 格式（namespace:path），直接返回
        // - 如果不包含冒号，保持原样（用户输入的可能只是 path 部分）
        // 注意：DimensionPathMapping.toServerDimension 对于没有 namespace 的名字无法正确转换
        return lower;
    }

    /**
     * 从 ResourceLocation 格式标准化维度名称
     *
     * 输入格式：minecraft:overworld, twilightforest:twilight_forest
     * 输出格式：overworld, the_nether, the_end（原版）或 namespace:path（Mod）
     */
    private static String normalizeDimensionFromResource(String resourceLocation) {
        if (resourceLocation == null || resourceLocation.isEmpty()) {
            return "overworld";
        }

        // 提取 namespace 和 path
        String namespace;
        String path;
        if (resourceLocation.contains(":")) {
            String[] parts = resourceLocation.split(":");
            namespace = parts[0];
            path = parts.length > 1 ? parts[1] : "";
        } else {
            namespace = "minecraft";
            path = resourceLocation;
        }

        // 原版维度：返回简化名称
        if ("minecraft".equals(namespace)) {
            switch (path) {
                case "overworld":
                    return "overworld";
                case "the_nether":
                    return "the_nether";
                case "the_end":
                    return "the_end";
            }
        }

        // Mod 维度：返回完整 ID
        return namespace + ":" + path;
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

    /**
     * 解析正确的 Xaero 格式维度名
     *
     * 统一维度指令格式：
     * - 原版维度：overworld, the_nether, the_end（简化名称）
     * - Mod 维度：namespace:path（完整 ID）
     *
     * 输入已经是标准格式，直接转换为 Xaero 格式：
     * - overworld → null
     * - the_nether → DIM-1
     * - the_end → DIM1
     * - twilightforest:twilight_forest → twilightforest$twilight_forest
     *
     * 如果输入缺少 namespace（只有 path 部分），尝试从缓存/目录反向查找
     */
    private static String resolveCorrectXaeroDim(String dimension, DimensionPathMapping dimMapping,
                                                   ClientTimestampCache tsCache, Path baseDir) {
        // 原版维度直接处理
        switch (dimension) {
            case "overworld":
                return "null";
            case "the_nether":
                return "DIM-1";
            case "the_end":
                return "DIM1";
        }

        // 如果是完整维度 ID（包含冒号），直接转换
        if (dimension.contains(":")) {
            return dimMapping.toXaeroDimension(dimension);
        }

        // 输入只有 path 部分（缺少 namespace），尝试从缓存/目录反向查找
        // 这种情况发生在用户直接输入目录名或旧格式的维度名

        // 尝试方法1：从缓存反向查找
        if (tsCache != null) {
            String fromCache = findXaeroDimFromCache(dimension, tsCache);
            if (fromCache != null) {
                LOGGER.debug("Found xaeroDim from cache: {} -> {}", dimension, fromCache);
                return fromCache;
            }
        }

        // 尝试方法2：从目录结构查找
        if (baseDir != null && baseDir.toFile().exists()) {
            String fromDir = findXaeroDimFromDirectory(dimension, baseDir);
            if (fromDir != null) {
                LOGGER.debug("Found xaeroDim from directory: {} -> {}", dimension, fromDir);
                return fromDir;
            }
        }

        // 无法确定，返回原始值（可能导致同步失败，但会提示用户）
        LOGGER.warn("Could not resolve correct Xaero dimension for: {}, please use full dimension ID (namespace:path)", dimension);
        return dimension;
    }

    /**
     * 从缓存键中反向查找 Xaero 格式维度名
     *
     * 缓存键格式：xaeroDim/regionX_regionZ 或 xaeroDim/caves/layer/regionX_regionZ
     * xaeroDim 可能是：null, DIM-1, DIM1, twilightforest$twilight_forest 等
     *
     * 需要匹配包含 dimension path 部分的 xaeroDim
     */
    private static String findXaeroDimFromCache(String dimension, ClientTimestampCache tsCache) {
        // dimension 可能是 twilight_forest，需要找到包含它的缓存键
        // xaeroDim 格式可能是 namespace$path（如 twilightforest$twilight_forest）
        for (String key : tsCache.getAll().keySet()) {
            // key 格式：xaeroDim/regionX_regionZ
            int slashIndex = key.indexOf('/');
            if (slashIndex > 0) {
                String xaeroDim = key.substring(0, slashIndex);
                // 检查 xaeroDim 是否包含 dimension
                // xaeroDim 格式：namespace$path，dimension 可能是 path 部分
                if (xaeroDim.contains("$")) {
                    String pathPart = xaeroDim.substring(xaeroDim.indexOf('$') + 1);
                    if (pathPart.equals(dimension)) {
                        return xaeroDim;
                    }
                } else if (xaeroDim.equals(dimension)) {
                    return xaeroDim;
                }
            }
        }
        return null;
    }

    /**
     * 从目录结构查找 Xaero 格式维度名
     *
     * 目录结构：Multiplayer_<server>/<xaeroDim>/mw$<worldId>/...
     * 扫描 baseDir 下的维度目录，找到包含 dimension 的目录名
     */
    private static String findXaeroDimFromDirectory(String dimension, Path baseDir) {
        try (Stream<Path> dirs = Files.list(baseDir)) {
            for (Path dir : dirs.toList()) {
                if (!Files.isDirectory(dir)) continue;
                String dirName = dir.getFileName().toString();
                // 跳过 mw$ 目录
                if (dirName.startsWith("mw$")) continue;

                // 检查目录名是否包含 dimension
                // xaeroDim 格式：null, DIM-1, DIM1, namespace$path
                if (dirName.contains("$")) {
                    String pathPart = dirName.substring(dirName.indexOf('$') + 1);
                    if (pathPart.equals(dimension)) {
                        return dirName;
                    }
                } else if (dirName.equals(dimension)) {
                    return dirName;
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to scan directory for xaeroDim: {}", e.getMessage());
        }
        return null;
    }
}