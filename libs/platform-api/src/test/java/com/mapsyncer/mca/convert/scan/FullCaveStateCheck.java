package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkSectionParser.BlockState;

import java.util.Map;

public final class FullCaveStateCheck {

    private static final BlockPropertyLookup LOOKUP = new BlockPropertyLookup() {
        @Override public int getFlags(String name) {
            return name.equals("minecraft:netherrack") ? FLAG_HAS_VANILLA_COLOR : 0;
        }
        @Override public boolean isWater(String name) { return false; }
        @Override public boolean isTransparent(String name) { return false; }
        @Override public boolean isInvisible(String name) { return false; }
        @Override public boolean shouldOverlay(String name) { return false; }
        @Override public boolean hasVanillaColor(String name) { return true; }
        @Override public boolean isGrassBlock(String name) { return false; }
        @Override public boolean isGlowing(String name) { return false; }
        @Override public boolean isTranslucentFluid(String name) { return false; }
        @Override public boolean isWaterloggedSurface(String name, Map<String, String> properties) { return false; }
        @Override public boolean isWaterInheriting(String name) { return false; }
        @Override public int getLightBlock(String name) { return 15; }
    };

    private FullCaveStateCheck() {}

    public static void run() {
        int pos = 0;
        BlockState netherrack = new BlockState("minecraft:netherrack", Map.of());
        ColumnScanContext context = new ColumnScanContext(true);

        context.onAir(pos);
        assert context.shouldEnterGround[pos] : "air above the roof must not disable full-cave ground entry";
        assert !context.canProcessCaveBlock(pos, true, netherrack, LOOKUP)
            : "the Nether roof must not become the full-cave map surface";
        assert !context.underair[pos] && !context.shouldEnterGround[pos]
            : "full-cave scan must enter the roof before looking for a cave";

        context.onAir(pos);
        assert context.canProcessCaveBlock(pos, true, netherrack, LOOKUP)
            : "the first solid below cave air must be rendered";
    }
}
