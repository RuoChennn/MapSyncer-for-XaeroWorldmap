package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.convert.model.OverlayEntry;

import java.util.ArrayList;

public final class ColumnScanContext {

    public final boolean[] blockFound = new boolean[256];
    public final boolean[] underair = new boolean[256];
    @SuppressWarnings("unchecked")
    public final ArrayList<OverlayEntry>[] overlayLists = new ArrayList[256];
    public final int[] topPixelH = new int[256];

    public ColumnScanContext(boolean fullCave) {
        for (int i = 0; i < 256; i++) {
            underair[i] = fullCave;
            topPixelH[i] = -1;
        }
    }

    public static int pos(int lx, int lz) {
        return (lz << 4) | lx;
    }
}
