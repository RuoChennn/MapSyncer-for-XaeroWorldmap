package com.mapsyncer.mixin;

import com.mapsyncer.client.PreDisconnectHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 暂停菜单 Mixin（Forge 1.21.1）：拦截正常断开连接动作。
 *
 * <p>注入 {@link PauseScreen#onDisconnect()}，在多人服务器且满足退出前贡献同步前提条件时，
 * 取消原始断开，打开等待界面并启动贡献流程。</p>
 */
@Mixin(PauseScreen.class)
public class PauseScreenMixin {

    @Unique
    private static boolean mapsyncer$runningOriginalDisconnect;

    @Shadow
    private void onDisconnect() {
        throw new AssertionError();
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void mapsyncer$interceptDisconnect(CallbackInfo ci) {
        if (mapsyncer$runningOriginalDisconnect) {
            return;
        }
        Runnable originalDisconnect = () -> {
            mapsyncer$runningOriginalDisconnect = true;
            try {
                this.onDisconnect();
            } finally {
                mapsyncer$runningOriginalDisconnect = false;
            }
        };
        if (PreDisconnectHooks.tryStart(originalDisconnect)) {
            ci.cancel();
        }
    }
}
