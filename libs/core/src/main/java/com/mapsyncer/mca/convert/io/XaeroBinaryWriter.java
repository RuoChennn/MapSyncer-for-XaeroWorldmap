package com.mapsyncer.mca.convert.io;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkSectionParser.BlockState;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.convert.io.XaeroBlockStateNbtWriter.PaletteKey;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.model.OverlayEntry;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mapsyncer.mca.convert.model.ConvertConstants.BLOCKS_PER_TILE;
import static com.mapsyncer.mca.convert.model.ConvertConstants.DEFAULT_BIOME;
import static com.mapsyncer.mca.convert.model.ConvertConstants.DEFAULT_BLOCK;
import static com.mapsyncer.mca.convert.model.ConvertConstants.MAJOR_VERSION;
import static com.mapsyncer.mca.convert.model.ConvertConstants.MINOR_VERSION;
import static com.mapsyncer.mca.convert.model.ConvertConstants.REGION_SIZE_BLOCKS;
import static com.mapsyncer.mca.convert.model.ConvertConstants.TILE_CHUNKS_PER_REGION;
import static com.mapsyncer.mca.convert.model.ConvertConstants.TILES_PER_TILE_CHUNK;

public final class XaeroBinaryWriter {

    private XaeroBinaryWriter() {}

    public static byte[] serialize(MapRegionData data, int minBuildHeight,
                                    BlockPropertyLookup blockLookup) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(0xFF);
            dos.writeInt((MAJOR_VERSION << 16) | MINOR_VERSION);

            Map<PaletteKey, Integer> blockPalette = new LinkedHashMap<>();
            Map<String, Integer> biomePalette = new LinkedHashMap<>();

            for (int tileChunkO = 0; tileChunkO < TILE_CHUNKS_PER_REGION; tileChunkO++) {
                for (int tileChunkP = 0; tileChunkP < TILE_CHUNKS_PER_REGION; tileChunkP++) {
                    dos.writeByte((tileChunkO << 4) | tileChunkP);

                    for (int tileI = 0; tileI < TILES_PER_TILE_CHUNK; tileI++) {
                        for (int tileJ = 0; tileJ < TILES_PER_TILE_CHUNK; tileJ++) {
                            int chunkX = tileChunkO * 4 + tileI;
                            int chunkZ = tileChunkP * 4 + tileJ;

                            int baseX = chunkX * 16;
                            int baseZ = chunkZ * 16;

                            if (!data.chunkExists[chunkX][chunkZ]) {
                                dos.writeInt(-1);
                                continue;
                            }

                            for (int bx = 0; bx < BLOCKS_PER_TILE; bx++) {
                                for (int bz = 0; bz < BLOCKS_PER_TILE; bz++) {
                                    int rx = baseX + bx;
                                    int rz = baseZ + bz;

                                    if (!data.hasData[rx][rz]) {
                                        if (data.lightMode == LightMode.CAVE) {
                                            writeCaveEmptyPixel(dos, data, rx, rz, minBuildHeight,
                                                blockPalette, biomePalette);
                                        } else {
                                            writeEmptyPixel(dos, data, rx, rz, minBuildHeight,
                                                blockPalette, biomePalette);
                                        }
                                        continue;
                                    }

                                    writePixel(dos, data, rx, rz, blockPalette, biomePalette, blockLookup);
                                }
                            }

                            dos.writeByte(1);
                            dos.writeInt(data.caveParams.caveStart());
                            dos.writeByte(data.caveParams.caveDepth() & 0xFF);
                        }
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    private static void writeCaveEmptyPixel(DataOutputStream dos, MapRegionData data, int rx, int rz,
                                            int minBuildHeight,
                                            Map<PaletteKey, Integer> blockPalette,
                                            Map<String, Integer> biomePalette) throws IOException {
        BlockState air = XaeroBlockStateNbtWriter.AIR;
        PaletteKey paletteKey = PaletteKey.from(air);
        int emptyHeight = minBuildHeight;
        String biomeName = data.biomeNames[rx][rz];
        if (biomeName == null || biomeName.equals(DEFAULT_BIOME)) {
            biomeName = null;
        }
        int emptyParams = 1;
        emptyParams |= encodeHeightToParams(emptyHeight);
        if (biomeName != null) {
            emptyParams |= 0x100000;
        }
        if (!blockPalette.containsKey(paletteKey)) {
            emptyParams |= 0x200000;
        }
        if (biomeName != null && !biomePalette.containsKey(biomeName)) {
            emptyParams |= 0x400000;
        }
        dos.writeInt(emptyParams);
        writeBlockStateRef(dos, air, blockPalette);
        writeBiomeRef(dos, biomeName, biomePalette);
    }

    private static void writeEmptyPixel(DataOutputStream dos, MapRegionData data, int rx, int rz,
                                         int minBuildHeight,
                                         Map<PaletteKey, Integer> blockPalette,
                                         Map<String, Integer> biomePalette) throws IOException {
        BlockState air = XaeroBlockStateNbtWriter.AIR;
        PaletteKey paletteKey = PaletteKey.from(air);
        int emptyHeight = data.heightMap[rx][rz];
        String biomeName = data.biomeNames[rx][rz];
        if (biomeName == null || biomeName.equals(DEFAULT_BIOME)) {
            biomeName = null;
        }
        int emptyParams = 0;

        emptyParams |= 1;
        emptyParams |= 15 << 8;
        emptyParams |= encodeHeightToParams(emptyHeight);
        if (biomeName != null) {
            emptyParams |= 0x100000;
        }

        if (!blockPalette.containsKey(paletteKey)) {
            emptyParams |= 0x200000;
        }
        if (biomeName != null && !biomePalette.containsKey(biomeName)) {
            emptyParams |= 0x400000;
        }

        dos.writeInt(emptyParams);
        writeBlockStateRef(dos, air, blockPalette);
        writeBiomeRef(dos, biomeName, biomePalette);
    }

    private static void writeBiomeRef(DataOutputStream dos, String biomeName,
                                      Map<String, Integer> biomePalette) throws IOException {
        if (biomeName == null) {
            return;
        }
        if (biomePalette.containsKey(biomeName)) {
            dos.writeInt(biomePalette.get(biomeName));
        } else {
            dos.writeUTF(biomeName);
            biomePalette.put(biomeName, biomePalette.size());
        }
    }

    private static void writePixel(DataOutputStream dos, MapRegionData data, int rx, int rz,
                                    Map<PaletteKey, Integer> blockPalette,
                                    Map<String, Integer> biomePalette,
                                    BlockPropertyLookup blockLookup) throws IOException {
        BlockState blockState = data.blockStates[rx][rz];
        if (blockState == null) {
            blockState = new BlockState(DEFAULT_BLOCK, Map.of());
        }
        String blockName = blockState.name();
        PaletteKey paletteKey = PaletteKey.from(blockState);

        int height = data.heightMap[rx][rz];
        int topY = data.topBlockY[rx][rz];
        int topHeight = (topY >= 0) ? topY : height;
        String biomeName = data.biomeNames[rx][rz];
        if (biomeName == null || biomeName.equals(DEFAULT_BIOME)) {
            biomeName = null;
        }
        int light = data.lightMap[rx][rz];
        List<OverlayEntry> overlays = data.overlays.get(rx * REGION_SIZE_BLOCKS + rz);
        boolean hasOverlays = overlays != null && !overlays.isEmpty();
        boolean isGrass = blockLookup.isGrassBlock(blockName);
        boolean topHeightDifferent = (height != topHeight);

        int params = 0;
        if (!isGrass) {
            params |= 1;
        }
        if (hasOverlays) {
            params |= 2;
        }
        params |= light << 8;
        params |= encodeHeightToParams(height);
        if (biomeName != null) {
            params |= 0x100000;
        }
        if (topHeightDifferent) {
            params |= 0x1000000;
        }

        if (!isGrass && !blockPalette.containsKey(paletteKey)) {
            params |= 0x200000;
        }
        if (biomeName != null && !biomePalette.containsKey(biomeName)) {
            params |= 0x400000;
        }

        dos.writeInt(params);

        if (!isGrass) {
            writeBlockStateRef(dos, blockState, blockPalette);
        }

        if (topHeightDifferent) {
            dos.writeByte(topHeight & 0xFF);
        }

        if (hasOverlays) {
            dos.writeByte(overlays.size());
            for (OverlayEntry overlay : overlays) {
                serializeOverlay(overlay, dos, blockPalette, blockLookup);
            }
        }

        writeBiomeRef(dos, biomeName, biomePalette);
    }

    private static void writeBlockStateRef(DataOutputStream dos, BlockState blockState,
                                          Map<PaletteKey, Integer> blockPalette) throws IOException {
        PaletteKey paletteKey = PaletteKey.from(blockState);
        if (blockPalette.containsKey(paletteKey)) {
            dos.writeInt(blockPalette.get(paletteKey));
        } else {
            XaeroBlockStateNbtWriter.writeBlockState(blockState, dos);
            blockPalette.put(paletteKey, blockPalette.size());
        }
    }

    private static int encodeHeightToParams(int height) {
        return (height & 0xFF) << 12 | ((height >> 8) & 0xF) << 25;
    }

    private static void serializeOverlay(OverlayEntry overlay, DataOutputStream dos,
                                          Map<PaletteKey, Integer> blockPalette,
                                          BlockPropertyLookup blockLookup) throws IOException {
        BlockState blockState = overlay.blockState;
        boolean isWater = blockLookup.isWater(blockState.name());
        int opacity = overlay.opacity;
        int light = overlay.light;
        PaletteKey paletteKey = PaletteKey.from(blockState);

        int overlayParams = 0;
        if (!isWater) {
            overlayParams |= 1;
        }
        overlayParams |= light << 4;
        overlayParams |= opacity << 11;
        if (!isWater && !blockPalette.containsKey(paletteKey)) {
            overlayParams |= 0x400;
        }

        dos.writeInt(overlayParams);

        if (!isWater) {
            writeBlockStateRef(dos, blockState, blockPalette);
        }
    }
}
