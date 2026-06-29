package com.mapsyncer.mca.convert.io;

import com.mapsyncer.mca.BlockPropertyLookup;
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

            Map<String, Integer> blockPalette = new LinkedHashMap<>();
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
                                        writeEmptyPixel(dos, minBuildHeight, blockPalette);
                                        continue;
                                    }

                                    writePixel(dos, data, rx, rz, blockPalette, biomePalette, blockLookup);
                                }
                            }

                            dos.writeByte(1);
                            dos.writeInt(Integer.MAX_VALUE);
                            dos.writeByte(0);
                        }
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    private static void writeEmptyPixel(DataOutputStream dos, int minBuildHeight,
                                         Map<String, Integer> blockPalette) throws IOException {
        String emptyBlockName = "minecraft:air";
        int emptyHeight = minBuildHeight;
        int emptyParams = 0;

        emptyParams |= 1;
        emptyParams |= 0 << 8;
        emptyParams |= encodeHeightToParams(emptyHeight);

        if (!blockPalette.containsKey(emptyBlockName)) {
            emptyParams |= 0x200000;
        }

        dos.writeInt(emptyParams);

        if (!blockPalette.containsKey(emptyBlockName)) {
            writeBlockStateNbt(emptyBlockName, dos);
            blockPalette.put(emptyBlockName, blockPalette.size());
        } else {
            dos.writeInt(blockPalette.get(emptyBlockName));
        }
    }

    private static void writePixel(DataOutputStream dos, MapRegionData data, int rx, int rz,
                                    Map<String, Integer> blockPalette,
                                    Map<String, Integer> biomePalette,
                                    BlockPropertyLookup blockLookup) throws IOException {
        String blockName = data.blockNames[rx][rz];
        if (blockName == null) {
            blockName = DEFAULT_BLOCK;
        }
        int height = data.heightMap[rx][rz];
        int topY = data.topBlockY[rx][rz];
        int topHeight = (topY >= 0) ? topY : height;
        String biomeName = data.biomeNames[rx][rz];
        if (biomeName == null) {
            biomeName = DEFAULT_BIOME;
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

        if (!isGrass && !blockPalette.containsKey(blockName)) {
            params |= 0x200000;
        }
        if (biomeName != null && !biomePalette.containsKey(biomeName)) {
            params |= 0x400000;
        }

        dos.writeInt(params);

        if (!isGrass) {
            if (blockPalette.containsKey(blockName)) {
                dos.writeInt(blockPalette.get(blockName));
            } else {
                writeBlockStateNbt(blockName, dos);
                blockPalette.put(blockName, blockPalette.size());
            }
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

        if (biomeName != null) {
            if (biomePalette.containsKey(biomeName)) {
                dos.writeInt(biomePalette.get(biomeName));
            } else {
                dos.writeUTF(biomeName);
                biomePalette.put(biomeName, biomePalette.size());
            }
        }
    }

    private static int encodeHeightToParams(int height) {
        return (height & 0xFF) << 12 | ((height >> 8) & 0xF) << 25;
    }

    private static void writeBlockStateNbt(String blockName, DataOutputStream dos) throws IOException {
        ByteArrayOutputStream nbtBaos = new ByteArrayOutputStream();
        try (DataOutputStream nbtDos = new DataOutputStream(nbtBaos)) {
            nbtDos.writeByte(10);
            nbtDos.writeShort(0);
            nbtDos.writeByte(8);
            nbtDos.writeUTF("Name");
            nbtDos.writeUTF(blockName);
            nbtDos.writeByte(0);
        }
        dos.write(nbtBaos.toByteArray());
    }

    private static void serializeOverlay(OverlayEntry overlay, DataOutputStream dos,
                                          Map<String, Integer> blockPalette,
                                          BlockPropertyLookup blockLookup) throws IOException {
        boolean isWater = blockLookup.isWater(overlay.blockName);
        int opacity = overlay.opacity;
        int light = overlay.light;

        int overlayParams = 0;
        if (!isWater) {
            overlayParams |= 1;
        }
        overlayParams |= light << 4;
        overlayParams |= opacity << 11;
        if (!isWater && !blockPalette.containsKey(overlay.blockName)) {
            overlayParams |= 0x400;
        }

        dos.writeInt(overlayParams);

        if (!isWater) {
            if (blockPalette.containsKey(overlay.blockName)) {
                dos.writeInt(blockPalette.get(overlay.blockName));
            } else {
                writeBlockStateNbt(overlay.blockName, dos);
                blockPalette.put(overlay.blockName, blockPalette.size());
            }
        }
    }
}
