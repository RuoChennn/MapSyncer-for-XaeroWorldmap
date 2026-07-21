package com.mapsyncer.client;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.server.CacheCommandHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ClientMessageHelper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import java.util.stream.Stream;

/**
 * 地图同步命令业务逻辑。
 * 包含所有平台共享的命令处理逻辑，平台特定的命令注册由各平台薄包装器处理。
 */
public class MapSyncerCommandLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommandLogic.class);

    /**
     * 显示命令帮助信息。
     *
     * @param hasServerPermission 是否有服务端权限（OP4+）
     */
    public static void showHelp(boolean hasServerPermission) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.displayClientMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.command.help_header")), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_dim"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_all"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_autosync"), false);
        mc.player.displayClientMessage(ChatUtils.header("mapsyncer.command.help_dimension_note"), false);

        if (hasServerPermission) {
            CacheCommandHandler.showHelp(ClientMessageHelper::sendChatMessage);
        }
    }

    /**
     * 提供维度名称建议（namespace:path 格式）。
     */
    public static void suggestDimensions(SuggestionsBuilder builder) {
        builder.suggest("minecraft:overworld");
        builder.suggest("minecraft:the_nether");
        builder.suggest("minecraft:the_end");
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
                LOGGER.warn("Failed to scan Xaero directory", e);
            }
        }
    }

    /**
     * 将 Xaero 目录名转换为维度 ID。
     */
    public static String xaeroDirToDimensionId(String dirName) {
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";
        if (dirName.contains("$")) return dirName.replace('$', ':');
        if (dirName.startsWith("DIM")) return "";
        return dirName;
    }

    /**
     * 同步指定维度。
     */
    public static int executeSyncDimension(String dimInput) {
        if ("all".equalsIgnoreCase(dimInput)) {
            return executeSyncAll(false);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = resolveDimensionId(dimInput, mc.level);
        sendSyncRequest(mc, dimensionId, false, false);

        return 1;
    }

    /**
     * 同步所有维度。
     */
    public static int executeSyncAll(boolean silent) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        sendSyncRequest(mc, "all", true, silent);
        return 1;
    }

    /**
     * 同步当前维度。
     */
    public static int executeSyncCurrentDim() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        ResourceKey<Level> currentDim = mc.level.dimension();
        String dimensionId = currentDim.location().toString();
        sendSyncRequest(mc, dimensionId, false, false);

        return 1;
    }

    /**
     * 解析用户输入的维度名称为完整维度 ID。
     */
    public static String resolveDimensionId(String input, ClientLevel level) {
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

    /**
     * 发送同步请求到服务端。
     */
    public static void sendSyncRequest(Minecraft mc, String dimensionId, boolean syncAll, boolean silent) {
        if (MapPacketHandler.isSyncInProgress() || ClientHashManager.isComputingMeta()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.in_progress"), false);
            }
            return;
        }

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();

        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim = syncAll ? null : dimMapping.toXaeroDimension(dimensionId);

        Path scanDir = null;
        Map<String, ClientMeta> immediateMeta = null;

        if (syncAll) {
            if (serverDir != null && tsCache != null && tsCache.cacheFileExists()) {
                scanDir = serverDir;
            } else {
                immediateMeta = new java.util.HashMap<>();
                LOGGER.debug("First sync all, sending empty request");
            }
        } else if (tsCache != null && tsCache.cacheFileExists() && tsCache.hasDimensionSynced(xaeroDim)) {
            Path dimDir = serverDir.resolve(xaeroDim);
            Path mwDir = findMwDir(dimDir);
            if (mwDir != null) {
                scanDir = mwDir;
            } else {
                immediateMeta = new java.util.HashMap<>();
                immediateMeta.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                LOGGER.warn("Dimension {} has cache but no mw$ dir", dimensionId);
            }
        } else {
            immediateMeta = new java.util.HashMap<>();
            immediateMeta.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
            LOGGER.debug("First sync for {}", dimensionId);
        }

        if (immediateMeta != null) {
            dispatchSyncRequest(mc, dimensionId, syncAll, serverDir, tsCache, xaeroDim, immediateMeta, silent);
            return;
        }

        ClientHashManager.computeMetaForSyncAsync(scanDir, result ->
                mc.execute(() -> {
                    if (mc.player == null) {
                        return;
                    }
                    if (!result.isSuccess()) {
                        if (result.failedFiles() > 0) {
                            mc.player.displayClientMessage(
                                    ChatUtils.error("mapsyncer.sync.hash_scan_partial", result.failedFiles()), false);
                        } else {
                            mc.player.displayClientMessage(
                                    ChatUtils.error("mapsyncer.sync.hash_scan_failed"), false);
                        }
                        return;
                    }
                    LOGGER.debug("Sync hash scan complete: {} entries", result.meta().size());
                    dispatchSyncRequest(mc, dimensionId, syncAll, serverDir, tsCache, xaeroDim, result.meta(), silent);
                }));
    }

    private static void dispatchSyncRequest(Minecraft mc, String dimensionId, boolean syncAll,
            Path serverDir, ClientTimestampCache tsCache, String xaeroDim,
            Map<String, ClientMeta> metaMap, boolean silent) {
        if (MapPacketHandler.isSyncInProgress()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.in_progress"), false);
            }
            return;
        }

        LOGGER.debug("Sending sync request with {} entries (serverDir={})", metaMap.size(), serverDir);

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

        SyncRequestPayload[] parts = SyncRequestPayload.split(metaMap, syncAll,
                syncAll ? "" : (xaeroDim != null ? xaeroDim : ""), silent);
        for (SyncRequestPayload part : parts) {
            NetworkManager.sendToServer(part);
        }
        SyncProgressTracker.startTracking();
    }

    /**
     * 在维度目录下查找 mw$worldId 目录。
     */
    public static Path findMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) return null;
        try (var dirs = Files.list(dimDir)) {
            return dirs.filter(p -> p.getFileName().toString().startsWith("mw$"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 显示客户端自动同步开关状态 */
    public static int executeAutoSyncStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        boolean enabled = PlatformManager.getPlatform().isClientAutoSyncEnabled();
        mc.player.displayClientMessage(
                ChatUtils.prefix().append(ChatUtils.desc(
                        enabled ? "mapsyncer.autosync.client.enabled" : "mapsyncer.autosync.client.disabled")),
                false);
        return 1;
    }

    /** 设置客户端自动同步开关并持久化 */
    public static int setClientAutoSync(boolean enabled) {
        PlatformManager.getPlatform().setClientAutoSyncEnabled(enabled);
        if (!enabled) {
            AutoSyncManager.stopPeriodicSync();
        }
        return executeAutoSyncStatus();
    }
}
