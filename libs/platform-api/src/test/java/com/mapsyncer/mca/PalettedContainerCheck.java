package com.mapsyncer.mca;

public final class PalettedContainerCheck {

    private PalettedContainerCheck() {}

    public static void run() {
        long[] sixBitStorage = new long[410]; // ceil(4096 / floor(64 / 6))
        assert ChunkSectionParser.calculateBitsPerEntry(17, sixBitStorage) == 6
            : "stored block-state width must win when a palette has shrunk";
        assert ChunkSectionParser.calculateBitsPerEntry(16, new long[256]) == 4
            : "normal four-bit block-state storage must remain unchanged";
    }
}
