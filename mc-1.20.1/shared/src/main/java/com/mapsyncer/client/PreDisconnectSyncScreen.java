package com.mapsyncer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 退出前贡献同步等待界面。
 *
 * <p>当玩家在多人服务器正常点击断开连接，且满足退出前贡献同步前提条件时，
 * 暂停菜单 Mixin 会打开此界面替代立即断开。界面会：</p>
 * <ul>
 *   <li>每 tick 检查 {@link PreDisconnectContributionManager#isTimedOut()}，
 *       超时则跳过等待并立即断开。</li>
 *   <li>显示当前贡献同步状态文字。</li>
 *   <li>提供“跳过并退出”按钮：立即执行原始断开动作（不取消服务端已入队的会话）。</li>
 *   <li>提供“返回游戏”按钮：取消本地等待，回到游戏；若服务端已入队贡献会话，该会话可能继续。</li>
 * </ul>
 *
 * <p>界面不暂停服务端 tick，玩家实体仍受环境影响。Esc 键被禁用，
 * 避免玩家通过 Esc 绕过等待而未执行断开。</p>
 *
 * <p>注意：1.20.1 的 {@code Screen#renderBackground(GuiGraphics)} 为单参数签名，
 * 与 1.21+ 的四参数版本不同，因此本类不与其他版本共享源码。</p>
 */
public class PreDisconnectSyncScreen extends Screen {

    /** 状态文字 Y 轴偏移（相对屏幕中心）。 */
    private static final int STATUS_Y_OFFSET = -20;
    /** 按钮宽度。 */
    private static final int BUTTON_WIDTH = 200;
    /** 按钮高度。 */
    private static final int BUTTON_HEIGHT = 20;
    /** 按钮垂直间距。 */
    private static final int BUTTON_SPACING = 24;

    private Component statusComponent;

    public PreDisconnectSyncScreen() {
        super(Component.translatable("mapsyncer.predisconnect.title"));
        this.statusComponent = Component.translatable(PreDisconnectContributionManager.getStatusKey());
    }

    @Override
    protected void init() {
        // 状态文字初始化为当前管理器状态。
        statusComponent = Component.translatable(PreDisconnectContributionManager.getStatusKey());

        int centerX = this.width / 2;
        int baseY = this.height / 2;

        // 跳过并退出：立即执行原始断开动作。
        addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.predisconnect.skip"),
                button -> PreDisconnectContributionManager.skipAndDisconnect()
        ).bounds(centerX - BUTTON_WIDTH / 2, baseY + BUTTON_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // 返回游戏：取消等待，回到游戏；已入队的服务端贡献会话可能继续。
        addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.predisconnect.return_to_game"),
                button -> {
                    PreDisconnectContributionManager.cancel();
                    Minecraft.getInstance().setScreen(null);
                }
        ).bounds(centerX - BUTTON_WIDTH / 2, baseY + BUTTON_SPACING * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void tick() {
        // 每帧同步状态文字，反映贡献同步进度。
        statusComponent = Component.translatable(PreDisconnectContributionManager.getStatusKey());

        if (PreDisconnectContributionManager.isTimedOut()) {
            PreDisconnectContributionManager.skipAndDisconnect();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.20.1 的 renderBackground 仅接收 GuiGraphics 单参数。
        renderBackground(graphics);

        // 标题。
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // 状态文字。
        graphics.drawCenteredString(this.font, statusComponent, this.width / 2, this.height / 2 + STATUS_Y_OFFSET, 0xCCCCCC);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // 禁用 Esc 关闭：避免玩家通过 Esc 绕过等待而未执行断开动作。
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        // 不暂停服务端 tick，玩家实体仍受环境影响。
        return false;
    }
}
