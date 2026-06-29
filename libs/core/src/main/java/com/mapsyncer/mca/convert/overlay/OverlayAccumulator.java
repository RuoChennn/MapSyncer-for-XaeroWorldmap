package com.mapsyncer.mca.convert.overlay;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkSectionParser.BlockState;
import com.mapsyncer.mca.convert.io.XaeroBlockStateNbtWriter;
import com.mapsyncer.mca.convert.model.OverlayEntry;

import java.util.ArrayList;
import java.util.List;

public final class OverlayAccumulator {

    public static final int MAX_LAYERS = 10;

    private OverlayAccumulator() {}

    public static void add(List<OverlayEntry> currentList, ArrayList<OverlayEntry> list,
                           BlockState blockState, int y, int opacityToAdd, int light,
                           BlockPropertyLookup blockLookup) {
        if (currentList != list) {
            addSingle(list, blockState, y, opacityToAdd, light, blockLookup);
            return;
        }
        if (list.size() >= MAX_LAYERS) {
            return;
        }
        opacityToAdd = normalizeOpacity(blockState.name(), opacityToAdd, blockLookup);
        OverlayEntry last = list.isEmpty() ? null : list.get(list.size() - 1);
        if (last != null && XaeroBlockStateNbtWriter.PaletteKey.from(last.blockState)
                .equals(XaeroBlockStateNbtWriter.PaletteKey.from(blockState))) {
            last.opacity = Math.min(15, last.opacity + opacityToAdd);
        } else {
            list.add(new OverlayEntry(blockState, y, opacityToAdd, light));
        }
    }

    private static void addSingle(ArrayList<OverlayEntry> list, BlockState blockState, int y,
                                  int opacityToAdd, int light, BlockPropertyLookup blockLookup) {
        if (list.size() >= MAX_LAYERS) {
            return;
        }
        opacityToAdd = normalizeOpacity(blockState.name(), opacityToAdd, blockLookup);
        list.add(new OverlayEntry(blockState, y, opacityToAdd, light));
    }

    private static int normalizeOpacity(String blockName, int opacityToAdd, BlockPropertyLookup blockLookup) {
        if (opacityToAdd > 15) {
            opacityToAdd = 15;
        }
        if (opacityToAdd == 0 && !blockLookup.isWater(blockName)) {
            String lower = blockName.toLowerCase();
            if (lower.contains("seagrass") || lower.contains("kelp") || blockLookup.isTransparent(blockName)) {
                opacityToAdd = 1;
            }
        }
        return opacityToAdd;
    }
}
