package com.mapsyncer.client;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 客户端玩家加入事件处理器 - 检测未完成的同步并提示断点续传
 *
 * 核心逻辑在公共模块，平台特定的事件注册由各平台子类/调用方处理。
 */
public class SyncResumeHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncResumeHelper.class);

    /**
     * 玩家登录服务器时调用（由平台特定事件处理器调用）
     */
    public static void onPlayerLoggingIn() {
        LOGGER.info("Player logging in to server, checking sync state...");

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.warn("Player is null during LoggingIn event");
            return;
        }

        // 异步延迟检测 + 自动探针，避免阻塞主线程
        Thread resumeCheckThread = new Thread(() -> {
            try {
                Thread.sleep(2000); // 等待2秒让 Xaero 目录和网络通道初始化
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            mc.execute(() -> {
                checkInterruptedSync(mc);
                // 发送自动探针：告知服务端此客户端已安装 MapSyncer
                sendAutoProbe(mc);
            });
        }, "mapsyncer-resume-check");
        resumeCheckThread.setDaemon(true);
        resumeCheckThread.start();
    }

    /**
     * 检测上次同步是否未完成
     */
    private static void checkInterruptedSync(Minecraft mc) {
        Path serverDir = PlatformManager.getPlatform().getClientXaeroWorldMapDir();
        if (serverDir == null || !serverDir.toFile().exists()) {
            LOGGER.info("Server directory not found, skip sync state check");
            return;
        }
        LOGGER.info("Checking sync state in: {}", serverDir);

        // 重置实例以重新加载缓存文件
        ClientTimestampCache.resetInstance();
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        if (tsCache == null) {
            LOGGER.warn("Failed to get ClientTimestampCache instance");
            return;
        }

        // 检查缓存文件是否存在（不存在说明从未同步过）
        if (!tsCache.cacheFileExists()) {
            LOGGER.debug("Cache file not found, never synced before");
            return;
        }

        String syncState = tsCache.getSyncState();
        String syncCommand = tsCache.getSyncCommand();
        LOGGER.debug("Loaded sync state: {}, command: {}", syncState, syncCommand);

        // 检查是否需要断点续传（状态为 in_progress）
        if (tsCache.needsResume()) {
            LOGGER.debug("Found unfinished sync, showing prompt");

            if (mc.player != null && !syncCommand.isEmpty()) {
                showResumePrompt(mc, syncCommand);
            } else {
                LOGGER.warn("Cannot show prompt: player={}, command={}", mc.player != null, syncCommand);
            }
        } else {
            LOGGER.debug("No resume needed: state={}", syncState);
        }
    }

    /**
     * 显示断点续传提示
     */
    private static void showResumePrompt(Minecraft mc, String command) {
        if (mc.player == null) return;

        Component clickButton = Component.literal("[点击继续同步]")
                .withStyle(Style.EMPTY
                        .withColor(0x55FF55)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击执行: " + command))));

        Component ignoreButton = Component.literal("[忽略]")
                .withStyle(Style.EMPTY
                        .withColor(0xFFAA00)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mapsyncer clearstate"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("清除同步状态标记"))));

        Component message = ChatUtils.prefix()
                .append(Component.literal("上次同步未完成")
                        .withStyle(Style.EMPTY.withColor(0xFFAA00)))
                .append(Component.literal(","))
                .append(clickButton)
                .append(Component.literal("或"))
                .append(ignoreButton);

        mc.player.displayClientMessage(message, false);
    }

    /**
     * 发送自动探针 SyncRequest，使服务端确认此客户端安装了 MapSyncer。
     * 未安装 mod 的原版客户端不会发送此请求，服务端从而可以区分。
     */
    private static void sendAutoProbe(Minecraft mc) {
        try {
            MapSyncerCommandLogic.sendSyncRequest(mc, "all", true);
            LOGGER.debug("Auto-probe SyncRequest sent");
        } catch (Exception e) {
            LOGGER.warn("Auto-probe failed: {}", e.getMessage());
        }
    }

    /**
     * 清除同步状态（用户主动忽略断点续传提示时调用）
     */
    public static void clearSyncState() {
        Minecraft mc = Minecraft.getInstance();
        Path serverDir = PlatformManager.getPlatform().getClientXaeroWorldMapDir();
        if (serverDir == null || !serverDir.toFile().exists()) return;

        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        if (tsCache != null) {
            tsCache.clearSyncState();
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.success("mapsyncer.sync.state_cleared"), false);
            }
        }
    }
}
