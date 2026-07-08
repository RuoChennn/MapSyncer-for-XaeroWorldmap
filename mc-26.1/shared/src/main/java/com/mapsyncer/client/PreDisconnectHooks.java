package com.mapsyncer.client;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * 退出前同步的暂停菜单入口。
 *
 * <p>各加载器的 {@code PauseScreenMixin} 在拦截到正常断开动作时调用 {@link #tryStart(Runnable)}。
 * 本类负责判断是否满足退出前贡献同步前提条件：仅在多人服务器、服务端已安装 MapSyncer、
 * 客户端处于 BIDIRECTIONAL 模式、且当前无同步或贡献进行时才打开等待界面并启动贡献流程。</p>
 *
 * <p>单人世界（本地集成服务器）的“返回主菜单”不会调用本类，由 Mixin 侧根据
 * {@code Minecraft.isLocalServer()} 判断后直接放行。</p>
 */
public final class PreDisconnectHooks {

    private PreDisconnectHooks() {
    }

    /**
     * 尝试启动退出前贡献同步。
     *
     * @param originalDisconnectAction 原始的断开动作（由 Mixin 提供，贡献完成/跳过/超时后执行）
     * @return {@code true} 表示已接管断开流程并打开了等待界面，Mixin 应取消原始动作；
     *         {@code false} 表示未接管，Mixin 应放行原始动作
     */
    public static boolean tryStart(Runnable originalDisconnectAction) {
        Minecraft mc = Minecraft.getInstance();
        // 仅多人服务器才考虑退出前同步；单人世界返回主菜单不触发。
        if (mc.isLocalServer()) {
            return false;
        }
        if (mc.getConnection() == null) {
            return false;
        }
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        if (!PreDisconnectContributionManager.canStart() || serverDir == null) {
            return false;
        }
        mc.setScreen(new PreDisconnectSyncScreen());
        PreDisconnectContributionManager.start(serverDir, originalDisconnectAction);
        return true;
    }
}
