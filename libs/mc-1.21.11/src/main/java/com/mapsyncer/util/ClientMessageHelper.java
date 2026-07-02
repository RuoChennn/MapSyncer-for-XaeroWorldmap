package com.mapsyncer.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 版本适配 — 客户端消息发送 API 差异封装。
 * MC 1.20.1~1.21.11 使用 {@code displayClientMessage(Component, boolean)}。
 */
public final class ClientMessageHelper {

    private ClientMessageHelper() {}

    /** 发送到聊天栏 (overlay=false) */
    public static void sendChatMessage(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(msg, false);
        }
    }

    /** 发送到 action bar 覆盖层 (overlay=true) */
    public static void sendOverlayMessage(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(msg, true);
        }
    }
}
