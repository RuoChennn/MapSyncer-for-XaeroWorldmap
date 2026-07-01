package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkSectionParser;
import com.mapsyncer.mca.convert.model.OverlayEntry;

import java.util.ArrayList;

public final class ColumnScanContext {

    public final boolean[] blockFound = new boolean[256];
    public final boolean[] underair = new boolean[256];
    /** 洞穴模式：扫描起点处尚未进入洞穴内部（参考 Xaero shouldEnterGround） */
    public final boolean[] shouldEnterGround = new boolean[256];
    @SuppressWarnings("unchecked")
    public final ArrayList<OverlayEntry>[] overlayLists = new ArrayList[256];
    public final int[] topPixelH = new int[256];

    public ColumnScanContext(boolean fullCave) {
        for (int i = 0; i < 256; i++) {
            underair[i] = fullCave;
            shouldEnterGround[i] = fullCave;
            topPixelH[i] = -1;
        }
    }

    /** 进入空气区域（Xaero: 遇 air 设 underair=true） */
    void onAir(int pos) {
        underair[pos] = true;
        shouldEnterGround[pos] = false;
    }

    /**
     * 流体触发 underair（Xaero MapWriter: 除非 cave && shouldEnterGround，否则设 underair）
     */
    void onFluid(int pos, boolean isCaveMode) {
        if (!isCaveMode || !shouldEnterGround[pos]) {
            underair[pos] = true;
        }
    }

    /** 洞穴模式：只有 underair 后才可记录实体方块/overlay */
    boolean canProcessCaveBlock(int pos, boolean isCaveMode) {
        return !isCaveMode || underair[pos];
    }

    static boolean hasFluid(ChunkSectionParser.BlockState state, BlockPropertyLookup lookup) {
        if (state.isFluid() || state.isWaterlogged()) {
            return true;
        }
        return (lookup.getFlags(state.name()) & BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID) != 0;
    }

    public static int pos(int lx, int lz) {
        return (lz << 4) | lx;
    }
}
