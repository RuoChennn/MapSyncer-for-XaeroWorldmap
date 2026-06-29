package com.mapsyncer.mca.convert.model;

public class OverlayEntry {
    public final String blockName;
    public final int y;
    public int opacity;
    public final int light;

    public OverlayEntry(String blockName, int y, int opacity, int light) {
        this.blockName = blockName;
        this.y = y;
        this.opacity = opacity;
        this.light = light;
    }
}
