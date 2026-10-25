package com.mapsyncer.client;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.XaeroHostMapRefresh;
import com.mapsyncer.platform.XaeroReflectionHelper;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;

/** 房主客户端独立链路：不调用生成、同步、哈希扫描或客机的重载队列。 */
public final class IntegratedHostMapUpdates {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntegratedHostMapUpdates.class);
    private static HostMapFileWatcher watcher;
    private static XaeroHostMapRefresh.Context current;
    private static Object serverIdentity;
    private static Object loadingRegion;
    private static boolean failed;
    private static int ticks;
    private IntegratedHostMapUpdates() {}

    public static void onClientTick() {
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null || client.getConnection() == null) {
            reset();
            return;
        }
        boolean enabled = PlatformManager.getPlatform().isIntegratedHostSilentUpdateEnabled();
        if (!enabled) { reset(); return; }
        if (serverIdentity != server) { reset(); serverIdentity = server; }
        if (failed || ++ticks % 5 != 0) return;
        try {
            if (!XaeroReflectionHelper.initialize()) return;
            var context = XaeroHostMapRefresh.context(XaeroReflectionHelper.getMapProcessor());
            if (context == null) { closeWatcher(); return; }
            // 切换世界时 Xaero 暂留的旧会话不能对新存档进行刷新。
            var layout = com.mapsyncer.server.ServerMapCache.snapshot();
            if (!layout.sharedXaero()) { closeWatcher(); return; }
            {
                Object mapWorld = XaeroReflectionHelper.getMapWorld();
                if (mapWorld == null) { closeWatcher(); return; }
                var root = (java.nio.file.Path) Class.forName("xaero.map.file.MapSaveLoad")
                        .getMethod("getRootFolder", String.class).invoke(null, context.worldId());
                if (root == null || !root.toAbsolutePath().normalize().equals(layout.root())) {
                    closeWatcher(); return;
                }
            }
            if (!context.equals(current)) {
                closeWatcher();
                current = context;
                watcher = new HostMapFileWatcher(context.directory(), context.worldSave());
                LOGGER.debug("Host silent map updates watching {}", context.directory());
            }
            if (watcher.failure() != null) throw watcher.failure();
            if (loadingRegion != null) {
                if (XaeroHostMapRefresh.reloadPending(loadingRegion)) return;
                loadingRegion = null;
            }
            int distance = XaeroHostMapRefresh.writingDistance(context);
            int playerChunkX = client.player.getBlockX() >> 4;
            int playerChunkZ = client.player.getBlockZ() >> 4;
            // 浏览其他维度时，房主当前维度的近处绘制范围不适用于所浏览的地图。
            String playingDimension = com.mapsyncer.util.DimensionPathMapping.getInstance().toXaeroDimension(
                    com.mapsyncer.util.DimensionApiHelper.getDimId(client.level.dimension()));
            String viewedDimension = ((java.nio.file.Path) context.dimension().getClass()
                    .getMethod("getMainFolderPath").invoke(context.dimension())).getFileName().toString();
            for (int i = 0; i < 8; i++) {
                var change = watcher.poll();
                if (change == null) break;
                if (playingDimension.equals(viewedDimension) && !HostMapUpdatePolicy.outsideWritingRange(
                        change.x(), change.z(), playerChunkX, playerChunkZ, distance)) {
                    watcher.retry(change);
                    continue;
                }
                var result = XaeroHostMapRefresh.invalidate(context, change.file(), change.x(), change.z(), change.contentChanged());
                if (!result.accepted()) watcher.retry(change);
                else {
                    loadingRegion = result.loadingRegion();
                    LOGGER.debug("Host silently invalidated region ({}, {}) layer={}", change.x(), change.z(), context.layer());
                    if (loadingRegion != null) break;
                }
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            closeWatcher();
            failed = true;
            LOGGER.warn("Host silent map updates disabled for this session: {}", e.toString());
        }
    }

    private static void closeWatcher() {
        if (watcher != null) watcher.close();
        watcher = null;
        current = null;
        loadingRegion = null;
    }

    public static void reset() {
        closeWatcher();
        serverIdentity = null;
        failed = false;
        ticks = 0;
    }
}
