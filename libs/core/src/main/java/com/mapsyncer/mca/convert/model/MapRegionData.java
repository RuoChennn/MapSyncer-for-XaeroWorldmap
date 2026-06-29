package com.mapsyncer.mca.convert.model;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.LightMode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mapsyncer.mca.convert.model.ConvertConstants.CHUNKS_PER_REGION;
import static com.mapsyncer.mca.convert.model.ConvertConstants.REGION_SIZE_BLOCKS;

public class MapRegionData {
    public final String[][] blockNames;
    public final int[][] topBlockY;
    public final String[][] biomeNames;
    public final int[][] heightMap;
    public final byte[][] lightMap;
    public final boolean[][] hasData;
    public final boolean[][] chunkExists;
    public final Map<Integer, List<OverlayEntry>> overlays;
    public final int minBuildHeight;
    public final LightMode lightMode;
    public final ChunkDataParser.ChunkInfo[][] chunkGrid;

    public MapRegionData(int minBuildHeight, LightMode lightMode) {
        this.minBuildHeight = minBuildHeight;
        this.lightMode = lightMode;
        blockNames = new String[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        topBlockY = new int[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        for (int x = 0; x < REGION_SIZE_BLOCKS; x++) {
            Arrays.fill(topBlockY[x], -1);
        }
        biomeNames = new String[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        heightMap = new int[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        for (int x = 0; x < REGION_SIZE_BLOCKS; x++) {
            Arrays.fill(heightMap[x], minBuildHeight);
        }
        lightMap = new byte[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        hasData = new boolean[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        chunkExists = new boolean[CHUNKS_PER_REGION][CHUNKS_PER_REGION];
        overlays = new HashMap<>();
        chunkGrid = new ChunkDataParser.ChunkInfo[CHUNKS_PER_REGION][CHUNKS_PER_REGION];
    }
}
