package com.mapsyncer.server;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.SyncAllowedPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.security.LoginModBridge;
import com.mapsyncer.security.PolicySettings;
import com.mapsyncer.security.SyncAuthTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录就绪后向客户端发送 {@link SyncAllowedPayload}。
 */
public final class SyncAuthNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncAuthNotifier.class);

    private static final Map<UUID, Long> joinTimeMs = new ConcurrentHashMap<>();

    private SyncAuthNotifier() {}

    public static void onPlayerJoin(ServerPlayer player) {
        UUID id = player.getUUID();
        joinTimeMs.put(id, System.currentTimeMillis());
        PolicySettings policy = PlatformManager.getPlatform().getPolicySettings();

        if (!policy.deferAutoSyncUntilLogin()) {
            grantSync(player, policy.autoSyncDelayAfterLoginSeconds());
            return;
        }

        if (!LoginModBridge.isAvailable()) {
            grantSync(player, policy.autoSyncDelayAfterLoginSeconds());
            return;
        }

        if (LoginModBridge.isAuthenticated(player)) {
            grantSync(player, policy.autoSyncDelayAfterLoginSeconds());
        }
    }

    public static void onPlayerLeave(UUID playerId) {
        joinTimeMs.remove(playerId);
        SyncAuthTracker.markNotReady(playerId);
    }

    /**
     * 服务端 tick 轮询登录 mod 状态（defer 模式且尚未 grant 时）。
     */
    public static void onServerTick(MinecraftServer server) {
        PolicySettings policy = PlatformManager.getPlatform().getPolicySettings();
        if (!policy.deferAutoSyncUntilLogin() || !LoginModBridge.isAvailable()) {
            return;
        }

        long now = System.currentTimeMillis();
        int timeoutSec = policy.loginWaitTimeoutSeconds();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (SyncAuthTracker.isReady(id)) {
                continue;
            }
            if (LoginModBridge.isAuthenticated(player)) {
                grantSync(player, policy.autoSyncDelayAfterLoginSeconds());
                continue;
            }
            if (timeoutSec > 0) {
                Long joined = joinTimeMs.get(id);
                if (joined != null && now - joined > timeoutSec * 1000L) {
                    LOGGER.warn("Login wait timeout for {}, denying sync until authenticated", player.getName().getString());
                }
            }
        }
    }

    /** 外部 mod 或命令可调用，强制标记玩家已登录。 */
    public static void grantSync(ServerPlayer player, int autoSyncDelaySeconds) {
        SyncAuthTracker.markReady(player.getUUID());
        NetworkManager.sendToPlayer(player, new SyncAllowedPayload(autoSyncDelaySeconds));
        LOGGER.debug("Sync allowed for {} (delay={}s)", player.getName().getString(), autoSyncDelaySeconds);
    }
}
