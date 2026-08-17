package com.mapsyncer.mca;

public final class HeightmapStorageCheck {

    private HeightmapStorageCheck() {}

    public static void run() {
        assert ChunkDataParser.calculateBitsPerHeight(37, 256) == 9
            : "a 256-high Nether heightmap must use its stored 9-bit width";
        assert ChunkDataParser.calculateBitsPerHeight(32, 255) == 8
            : "an eight-bit heightmap must remain eight bits";
    }
}
