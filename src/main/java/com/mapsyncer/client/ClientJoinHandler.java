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
 * 客户端玩家加入事件处理器 - Fabric 版本
 *
 * 检测未完成的同步并提示断点续传。
 */
public class ClientJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientJoinHandler.class);

    /**
     * 玩家登录服务器事件处理（客户端）- 由 MapSyncerClient 注册调用
     */
    public static void onPlayerLoggingIn(Minecraft client) {
        LOGGER.info("Player logging in to server, checking sync state...");

        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            client.execute(() -> checkInterruptedSync(client));
        }, "mapsyncer-resume-check").start();
    }

    private static void checkInterruptedSync(Minecraft mc) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        if (serverDir == null || !serverDir.toFile().exists()) {
            LOGGER.info("Server directory not found, skip sync state check");
            return;
        }
        LOGGER.info("Checking sync state in: {}", serverDir);

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
            }
        } else {
            LOGGER.info("No resume needed: state={}", syncState);
        }
    }

    private static void showResumePrompt(Minecraft mc, String command) {
        if (mc.player == null) return;

        Component clickButton = Component.literal("[点击继续同步]")
                .withStyle(Style.EMPTY
                        .withColor(0x55FF55)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("点击执行: " + command))));

        Component ignoreButton = Component.literal("[忽略]")
                .withStyle(Style.EMPTY
                        .withColor(0xFFAA00)
                        .withClickEvent(new ClickEvent.RunCommand("/mapsyncer clearstate"))
                        .withHoverEvent(new HoverEvent.ShowText(
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
