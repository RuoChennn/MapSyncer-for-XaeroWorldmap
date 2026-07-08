package com.mapsyncer.mixin;

import com.mapsyncer.client.PreDisconnectHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 暂停菜单 Mixin（Forge 1.21.11）：拦截正常断开连接动作。
 *
 * <p>1.21.11 的断开逻辑收编进合成方法 {@code lambda$createPauseMenu$7}，内部调用
 * {@link Minecraft#disconnectFromWorld(net.minecraft.network.chat.Component)}。本 Mixin
 * 注入该合成方法的 HEAD。</p>
 */
@Mixin(PauseScreen.class)
public class PauseScreenMixin {

    @Inject(method = {"lambda$createPauseMenu$7", "m_414157_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void mapsyncer$interceptDisconnect(CallbackInfo ci) {
        Runnable originalDisconnect = () ->
                Minecraft.getInstance().disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        if (PreDisconnectHooks.tryStart(originalDisconnect)) {
            ci.cancel();
        }
    }
}
