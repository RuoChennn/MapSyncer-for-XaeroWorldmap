package com.mapsyncer.mixin;

import com.mapsyncer.client.PreDisconnectHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 暂停菜单 Mixin：拦截正常断开连接动作（1.21.11 版本）。
 *
 * <p>1.21.11 的 PauseScreen 不再有独立的 {@code onDisconnect()} 命名方法，真正的断开逻辑
 * 收编进合成方法 {@code lambda$createPauseMenu$7}，其内部仅调用
 * {@link Minecraft#disconnectFromWorld(Component)}。本 Mixin 注入该合成方法的 HEAD。</p>
 *
 * <p>在多人服务器且满足退出前贡献同步前提条件时，取消原始断开，打开等待界面并启动贡献流程。
 * 单人世界由 {@link PreDisconnectHooks#tryStart} 通过 {@link Minecraft#isLocalServer()} 判断放行。</p>
 */
@Mixin(PauseScreen.class)
public class PauseScreenMixin {

    @Inject(method = "lambda$createPauseMenu$7", at = @At("HEAD"), cancellable = true)
    private void mapsyncer$interceptDisconnect(CallbackInfo ci) {
        Runnable originalDisconnect = () ->
                Minecraft.getInstance().disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
        if (PreDisconnectHooks.tryStart(originalDisconnect)) {
            ci.cancel();
        }
    }
}
