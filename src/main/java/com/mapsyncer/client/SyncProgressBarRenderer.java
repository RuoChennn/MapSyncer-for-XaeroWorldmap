package com.mapsyncer.client;

import net.minecraft.client.gui.GuiGraphics;

public class SyncProgressBarRenderer {

    private static final int BAR_WIDTH = 200;
    private static final int BAR_HEIGHT = 10;
    private static final int TEXT_OFFSET = 2;

    public static void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        if (!SyncProgressTracker.isTracking()) {
            return;
        }

        int x = (screenWidth - BAR_WIDTH) / 2;
        int y = screenHeight - 60;

        float progress = SyncProgressTracker.getProgress();

        guiGraphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xFF000000);
        guiGraphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF404040);

        int filledWidth = (int) (BAR_WIDTH * progress);
        guiGraphics.fill(x, y, x + filledWidth, y + BAR_HEIGHT, 0xFF00AA00);

        String text = String.format("%s (%d/%d)", SyncProgressTracker.getStatus(),
                SyncProgressTracker.getProcessed(), SyncProgressTracker.getTotal());
        int textWidth = guiGraphics.drawString(
                net.minecraft.client.Minecraft.getInstance().font,
                text,
                x + TEXT_OFFSET,
                y + TEXT_OFFSET,
                0xFFFFFFFF);
    }
}
