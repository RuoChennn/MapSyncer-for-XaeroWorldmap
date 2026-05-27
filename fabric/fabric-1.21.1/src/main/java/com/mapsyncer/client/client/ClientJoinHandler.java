package com.mapsyncer.client;

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
 * 注意：Fabric 版本的事件注册在 MapSyncerClient 中使用 ClientPlayConnectionEvents。
 */
public class ClientJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientJoinHandler.class);

    /**
     * 玩家登录服务器事件处理（客户端）
     *
     * 由 MapSyncerClient 通过 ClientPlayConnectionEvents.JOIN 调用。
     */
    public static void onPlayerLoggingIn() {
        LOGGER.info("Player logging in to server, checking sync state...");

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.warn("Player is null during LoggingIn event");
            return;
        }

        // 使用异步线程延迟检测，避免阻塞主线程
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 等待1秒让 Xaero 目录初始化
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // 在主线程执行检查
            mc.execute(() -> checkInterruptedSync(mc));
        }, "mapsyncer-resume-check").start();
    }

    /**
     * 检测上次同步是否未完成
     */
    private static void checkInterruptedSync(Minecraft mc) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
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

        if (!tsCache.cacheFileExists()) {
            LOGGER.info("Cache file not found, never synced before");
            return;
        }

        String syncState = tsCache.getSyncState();
        String syncCommand = tsCache.getSyncCommand();
        LOGGER.info("Loaded sync state: {}, command: {}", syncState, syncCommand);

        if (tsCache.needsResume()) {
            LOGGER.info("Found unfinished sync, showing prompt");

            if (mc.player != null && !syncCommand.isEmpty()) {
                showResumePrompt(mc, syncCommand);
            } else {
                LOGGER.warn("Cannot show prompt: player={}, command={}", mc.player != null, syncCommand);
            }
        } else {
            LOGGER.info("No resume needed: state={}", syncState);
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
     * 清除同步状态（用户主动忽略断点续传提示时调用）
     */
    public static void clearSyncState() {
        Minecraft mc = Minecraft.getInstance();
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
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