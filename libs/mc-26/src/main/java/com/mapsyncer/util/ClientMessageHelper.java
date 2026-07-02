package com.mapsyncer.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 版本适配 — 客户端消息发送 API 差异封装。
 * MC 26.1+ 废弃 {@code displayClientMessage}，拆分为 {@code sendSystemMessage} 和 {@code sendOverlayMessage}。
 */
public final class ClientMessageHelper {

    private ClientMessageHelper() {}

    /** 发送到聊天栏 */
    public static void sendChatMessage(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(msg);
        }
    }

    /** 发送到 action bar 覆盖层 */
    public static void sendOverlayMessage(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(msg);
        }
    }
}
