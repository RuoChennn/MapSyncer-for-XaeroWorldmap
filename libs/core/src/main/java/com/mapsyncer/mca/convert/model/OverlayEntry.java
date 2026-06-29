package com.mapsyncer.mca.convert.model;

import com.mapsyncer.mca.ChunkSectionParser.BlockState;

public class OverlayEntry {
    public final BlockState blockState;
    public final int y;
    public int opacity;
    public final int light;

    public OverlayEntry(BlockState blockState, int y, int opacity, int light) {
        this.blockState = blockState;
        this.y = y;
        this.opacity = opacity;
        this.light = light;
    }

    public String blockName() {
        return blockState.name();
    }
}
