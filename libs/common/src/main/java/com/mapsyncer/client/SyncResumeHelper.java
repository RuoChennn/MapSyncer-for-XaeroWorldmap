package com.mapsyncer.client;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ClientMessageHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 客户端加入服务器时检测未完成同步。
 * 仅在服务端未启用加入自动同步（增量更新关闭）时提示断点续传。
 */
public class SyncResumeHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncResumeHelper.class);

    private static final long POLICY_WAIT_MS = 3_000;
    private static final long POLICY_POLL_MS = 100;

    public static void onPlayerLoggingIn() {
        LOGGER.info("Player logging in to server, checking sync state...");

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.warn("Player is null during LoggingIn event");
            return;
        }

        Thread resumeCheckThread = new Thread(() -> {
            waitForServerPolicy();
            mc.execute(() -> checkInterruptedSync(mc));
        }, "mapsyncer-resume-check");
        resumeCheckThread.setDaemon(true);
        resumeCheckThread.start();
    }

    private static void waitForServerPolicy() {
        long deadline = System.currentTimeMillis() + POLICY_WAIT_MS;
        while (System.currentTimeMillis() < deadline && !AutoSyncManager.isServerPolicyKnown()) {
            try {
                Thread.sleep(POLICY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void checkInterruptedSync(Minecraft mc) {
        if (AutoSyncManager.isJoinAutoSyncEnabled()) {
            LOGGER.debug("Join auto-sync enabled, skip resume prompt");
            return;
        }

        Path serverDir = PlatformManager.getPlatform().getClientXaeroWorldMapDir();
        if (serverDir == null || !serverDir.toFile().exists()) {
            LOGGER.info("Server directory not found, skip sync state check");
            return;
        }

        ClientTimestampCache.resetInstance();
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        if (tsCache == null || !tsCache.cacheFileExists()) {
            return;
        }

        if (!tsCache.needsResume()) {
            LOGGER.debug("No resume needed: state={}", tsCache.getSyncState());
            return;
        }

        String syncCommand = tsCache.getSyncCommand();
        if (mc.player != null && !syncCommand.isEmpty()) {
            showResumePrompt(mc, syncCommand);
        }
    }

    private static void showResumePrompt(Minecraft mc, String command) {
        Component message = ChatUtils.prefix()
                .append(ChatUtils.desc("mapsyncer.sync.interrupted"))
                .append(Component.literal(" "))
                .append(Component.literal(command));
        ClientMessageHelper.sendChatMessage(message);
    }

    public static void clearSyncState() {
        Minecraft mc = Minecraft.getInstance();
        Path serverDir = PlatformManager.getPlatform().getClientXaeroWorldMapDir();
        if (serverDir == null || !serverDir.toFile().exists()) {
            return;
        }

        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        if (tsCache != null) {
            tsCache.clearSyncState();
            if (mc.player != null) {
                ClientMessageHelper.sendChatMessage(ChatUtils.success("mapsyncer.sync.state_cleared"));
            }
        }
    }
}
