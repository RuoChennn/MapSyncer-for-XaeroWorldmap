package com.mapsyncer.client;

import com.mapsyncer.util.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 客户端玩家加入事件处理器 - 检测未完成的同步并提示断点续传
 *
 * 功能：
 * - 玩家加入服务器时检测上次同步是否未完成（状态为 in_progress）
 * - 显示可点击的提示信息，让玩家可以继续上次同步
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientJoinHandler.class);

    /**
     * 玩家登录事件处理
     *
     * 检测上次同步是否未完成，如果需要断点续传则显示提示。
     *
     * @param event 玩家登录事件
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 使用异步线程延迟检测，避免阻塞主线程
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 等待1秒让 Xaero 目录初始化
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // 在主线程执行检查（因为涉及 Minecraft API）
            mc.execute(() -> checkInterruptedSync(mc));
        }, "mapsyncer-resume-check").start();
    }

    /**
     * 检测上次同步是否未完成
     *
     * @param mc Minecraft 客户端实例
     */
    private static void checkInterruptedSync(Minecraft mc) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        if (serverDir == null || !serverDir.toFile().exists()) {
            LOGGER.debug("Server directory not found, skip sync state check");
            return;
        }

        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        if (tsCache == null) return;

        // 检查状态文件是否存在（不存在说明从未同步过）
        if (!tsCache.syncStateFileExists()) {
            LOGGER.debug("Sync state file not found, never synced before");
            return;
        }

        String syncState = tsCache.getSyncState();
        String syncCommand = tsCache.getSyncCommand();

        // 检查是否需要断点续传（状态为 in_progress）
        if (tsCache.needsResume()) {
            LOGGER.info("Found unfinished sync: state={}, command={}", syncState, syncCommand);

            if (mc.player != null && !syncCommand.isEmpty()) {
                // 显示可点击的提示信息
                showResumePrompt(mc, syncCommand);
            }
        }
    }

    /**
     * 显示断点续传提示
     *
     * 使用可点击文本让玩家可以一键继续同步。
     *
     * @param mc Minecraft 客户端实例
     * @param command 同步指令
     */
    private static void showResumePrompt(Minecraft mc, String command) {
        if (mc.player == null) return;

        // 创建提示信息
        Component header = ChatUtils.prefix().append(
                ChatUtils.header("mapsyncer.sync.resume_header"));

        Component message = ChatUtils.message("mapsyncer.sync.resume_prompt", "上次同步未完成");

        // 创建可点击的指令按钮
        Component clickButton = Component.literal("[点击继续同步]")
                .withStyle(Style.EMPTY
                        .withColor(0x55FF55) // 绿色
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击执行: " + command))));

        // 创建忽略按钮
        Component ignoreButton = Component.literal("[忽略]")
                .withStyle(Style.EMPTY
                        .withColor(0xFFAA00) // 橙色
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mapsyncer sync clearstate"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("清除同步状态标记"))));

        // 发送消息
        mc.player.displayClientMessage(header, false);
        mc.player.displayClientMessage(message, false);
        mc.player.displayClientMessage(Component.literal("  ")
                .append(clickButton)
                .append(Component.literal("  "))
                .append(ignoreButton), false);
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