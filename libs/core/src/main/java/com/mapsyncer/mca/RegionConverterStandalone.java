package com.mapsyncer.mca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 独立的区域转换器 - 不依赖 Minecraft 库
 *
 * <p>使用自研 MCA 解析器读取 .mca 文件，转换为 Xaero WorldMap 格式。</p>
 *
 * <p>核心功能:</p>
 * <ul>
 *   <li>读取和解析 MCA 区域文件</li>
 *   <li>处理方块状态、生物群系和光照数据</li>
 *   <li>支持地表模式和洞穴模式的扫描</li>
 *   <li>生成符合 Xaero 格式的地图数据</li>
 * </ul>
 *
 * <p>参考 Xaero WorldDataReader 的实现逻辑</p>
 *
 * @see McaReader 用于读取 MCA 文件
 * @see ChunkDataParser 用于解析 Chunk 数据
 * @see ChunkSectionParser 用于解析 Section 数据
 * @see LightMode 光照模式枚举
 * @see DimensionTypeInfo 维度类型信息
 */
public class RegionConverterStandalone {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionConverterStandalone.class);

    private static final String DEFAULT_BLOCK = "minecraft:air";
    private static final String DEFAULT_BIOME = "minecraft:the_void";

    public static final int REGION_SIZE_BLOCKS = 512;
    public static final int CHUNKS_PER_REGION = 32;
    public static final int BLOCKS_PER_TILE_CHUNK = 64;
    public static final int BLOCKS_PER_TILE = 16;
    public static final int TILES_PER_TILE_CHUNK = 4;
    public static final int TILE_CHUNKS_PER_REGION = 8;
    public static final int MAJOR_VERSION = 6;
    public static final int MINOR_VERSION = 8;

    public record ConvertedRegion(int regionX, int regionZ, byte[] xaeroData) {}

    public record CaveModeParams(int caveStart, int caveDepth) {
        public static final CaveModeParams NONE = new CaveModeParams(Integer.MAX_VALUE, 0);

        public static CaveModeParams createDefault(int worldTopY, int defaultDepth) {
            return new CaveModeParams(worldTopY, defaultDepth);
        }
    }

    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY,
                                                  BlockPropertyLookup blockLookup) {
        return convertRegion(mcaPath, regionX, regionZ, minBuildHeight, worldTopY,
                             LightMode.SURFACE, CaveModeParams.NONE, true, blockLookup);
    }

    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams,
                                                  boolean worldHasSkylight,
                                                  BlockPropertyLookup blockLookup) {
        if (!Files.exists(mcaPath)) {
            return null;
        }

        try {
            MapRegionData regionData = readMcaFile(mcaPath, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight, blockLookup);
            if (regionData == null) return null;

            byte[] xaeroData = serializeToXaeroFormat(regionData, minBuildHeight, blockLookup);
            return new ConvertedRegion(regionX, regionZ, xaeroData);
        } catch (IOException e) {
            LOGGER.warn("Failed to convert region ({}, {}): {}", regionX, regionZ, e.getMessage());
            return null;
        }
    }

    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  DimensionTypeInfo dimTypeInfo,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams,
                                                  BlockPropertyLookup blockLookup) {
        return convertRegion(mcaPath, regionX, regionZ,
                             dimTypeInfo.minY(), dimTypeInfo.maxY(),
                             lightMode, caveParams, dimTypeInfo.hasSkylight(), blockLookup);
    }

    static MapRegionData readMcaFile(Path mcaPath, int minBuildHeight, int worldTopY,
                                       LightMode lightMode, CaveModeParams caveParams,
                                       boolean worldHasSkylight,
                                       BlockPropertyLookup blockLookup) throws IOException {
        MapRegionData data = new MapRegionData(minBuildHeight, lightMode);

        try (McaReader reader = new McaReader(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            for (McaReader.ChunkData chunkData : reader.readAllChunks()) {
                ChunkDataParser.ChunkInfo chunkInfo = ChunkDataParser.parseChunk(
                    chunkData.chunkX(), chunkData.chunkZ(), chunkData.nbt(), worldHeightRange
                );

                if (chunkInfo == null) continue;

                processChunk(data, chunkInfo, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight, blockLookup);
            }
        }

        return data;
    }

    private static void processChunk(MapRegionData data, ChunkDataParser.ChunkInfo chunk,
                                       int minBuildHeight, int worldTopY,
                                       LightMode lightMode, CaveModeParams caveParams,
                                       boolean worldHasSkylight,
                                       BlockPropertyLookup blockLookup) {
        int chunkX = chunk.chunkX();
        int chunkZ = chunk.chunkZ();

        data.chunkExists[chunkX][chunkZ] = true;

        int caveStart = caveParams.caveStart();
        int caveDepth = caveParams.caveDepth();
        boolean isCaveMode = caveStart != Integer.MAX_VALUE;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int relX = chunkX * 16 + lx;
                int relZ = chunkZ * 16 + lz;

                if (relX >= REGION_SIZE_BLOCKS || relZ >= REGION_SIZE_BLOCKS) {
                    continue;
                }

                ScanRange scanRange = computeScanRange(chunk, lx, lz, minBuildHeight, worldTopY, isCaveMode, caveStart, caveDepth);

                SurfaceResult surface = scanChunkSections(chunk, data, lx, lz, relX, relZ,
                    scanRange, minBuildHeight, worldTopY, lightMode, isCaveMode, caveStart, worldHasSkylight, blockLookup);

                if (surface != null) {
                    recordPixelData(data, surface, relX, relZ);
                }
            }
        }
    }

    private record ScanRange(int startY, int scanBottomY) {}

    private record SurfaceResult(
        ChunkSectionParser.BlockState topState,
        int topY,
        int highestBlockY,
        String biomeName,
        byte surfaceLight,
        List<OverlayData> overlayList
    ) {}

    private static ScanRange computeScanRange(ChunkDataParser.ChunkInfo chunk, int lx, int lz,
                                               int minBuildHeight, int worldTopY,
                                               boolean isCaveMode, int caveStart, int caveDepth) {
        int heightMapValue = chunk.heightmap()[lx][lz];

        if (isCaveMode) {
            int startY = caveStart;
            int scanBottomY = Math.max(caveStart - caveDepth, minBuildHeight);
            return new ScanRange(startY, scanBottomY);
        } else {
            int startY = ChunkDataParser.getHeightmapStartY(chunk, lx, lz, worldTopY);
            return new ScanRange(startY, minBuildHeight);
        }
    }

    private static SurfaceResult scanChunkSections(ChunkDataParser.ChunkInfo chunk, MapRegionData data,
                                                    int lx, int lz, int relX, int relZ,
                                                    ScanRange scanRange, int minBuildHeight, int worldTopY,
                                                    LightMode lightMode, boolean isCaveMode, int caveStart,
                                                    boolean worldHasSkylight,
                                                    BlockPropertyLookup blockLookup) {
        int heightMapValue = chunk.heightmap()[lx][lz];
        int chunkBottomY = chunk.chunkBottomY();

        ChunkSectionParser.BlockState topState = null;
        int topY = -1;
        int highestBlockY = -1;
        String biomeName = null;
        List<OverlayData> overlayList = new ArrayList<>();
        byte surfaceLight = 0;

        boolean underair = isCaveMode && caveStart == Integer.MIN_VALUE;

        int sectionIndex = 0;
        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.blockPalette().isEmpty()) continue;

            int sectionY = section.sectionY();
            int sectionBaseY = sectionY * 16;
            int sectionTopY = sectionBaseY + 15;
            int sectionBottomY = sectionBaseY;

            if (isCaveMode && sectionTopY > scanRange.startY()) continue;
            if (sectionBottomY < scanRange.scanBottomY()) continue;
            if (sectionTopY < chunkBottomY) continue;

            int effectiveStartY = computeEffectiveStartY(sectionIndex, scanRange.startY(), worldTopY,
                isCaveMode, heightMapValue, chunkBottomY, sectionTopY);

            sectionIndex++;

            SurfaceResult sectionResult;
            if (section.blockPalette().size() == 1 && section.blockData() == null) {
                sectionResult = processSinglePaletteSection(section, lx, lz, relX, relZ,
                    sectionBaseY, effectiveStartY, scanRange.scanBottomY(), chunkBottomY,
                    isCaveMode, underair, worldHasSkylight, lightMode, heightMapValue, overlayList, blockLookup);
            } else {
                sectionResult = processMultiPaletteSection(section, lx, lz, relX, relZ,
                    sectionBaseY, effectiveStartY, scanRange.scanBottomY(), chunkBottomY,
                    isCaveMode, underair, worldHasSkylight, lightMode, heightMapValue, overlayList, blockLookup);
            }

            if (sectionResult != null) {
                underair = updateUnderairState(section, lx, lz, sectionBaseY, effectiveStartY,
                    scanRange.scanBottomY(), chunkBottomY, isCaveMode, underair);
                return sectionResult;
            }

            underair = updateUnderairState(section, lx, lz, sectionBaseY, effectiveStartY,
                scanRange.scanBottomY(), chunkBottomY, isCaveMode, underair);
        }

        return null;
    }

    private static int computeEffectiveStartY(int sectionIndex, int startY, int worldTopY,
                                               boolean isCaveMode, int heightMapValue, int chunkBottomY,
                                               int sectionTopY) {
        int effectiveStartY = startY;
        if (sectionIndex > 0) {
            effectiveStartY = Math.min(startY + 1, worldTopY - 1);
        }

        if (!isCaveMode && heightMapValue < chunkBottomY) {
            effectiveStartY = sectionTopY;
        }

        if (isCaveMode) {
            effectiveStartY = Math.min(effectiveStartY, sectionTopY);
        }

        return effectiveStartY;
    }

    private static SurfaceResult processSinglePaletteSection(ChunkSectionParser.SectionData section,
                                                              int lx, int lz, int relX, int relZ,
                                                              int sectionBaseY, int effectiveStartY,
                                                              int scanBottomY, int chunkBottomY,
                                                              boolean isCaveMode, boolean underair,
                                                              boolean worldHasSkylight, LightMode lightMode,
                                                              int heightMapValue, List<OverlayData> overlayList,
                                                              BlockPropertyLookup blockLookup) {
        ChunkSectionParser.BlockState singleState = section.blockPalette().get(0);

        if (singleState.isAir()) {
            return null;
        }

        if (isCaveMode && !underair) {
            return null;
        }

        int scanStartY = Math.min(effectiveStartY - sectionBaseY, 15);
        if (scanStartY < 0) scanStartY = 15;
        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

        for (int ly = scanStartY; ly >= localScanBottomY; ly--) {
            int worldY = sectionBaseY + ly;
            if (worldY < scanBottomY) break;

            if (blockLookup.isWaterloggedSurface(singleState.name(), singleState.properties())
                && !blockLookup.shouldOverlay(singleState.name())) {
                int opacity = blockLookup.getLightBlock("minecraft:water");
                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                addOverlay(overlayList, "minecraft:water", worldY, opacity, overlayLight, blockLookup);

                byte surfaceLight = overlayLight;
                String biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz, true);
                return new SurfaceResult(singleState, worldY, worldY, biomeName, surfaceLight, overlayList);
            }

            if (blockLookup.shouldOverlay(singleState.name())) {
                int opacity = blockLookup.getLightBlock(singleState.name());
                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                addOverlay(overlayList, singleState.name(), worldY, opacity, overlayLight, blockLookup);
                continue;
            }

            byte surfaceLight = calculateSurfaceLight(section, lx, ly, lz, worldY,
                heightMapValue, overlayList, lightMode, worldHasSkylight, blockLookup);
            String biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz, true);
            return new SurfaceResult(singleState, worldY, worldY, biomeName, surfaceLight, overlayList);
        }

        return null;
    }

    private static SurfaceResult processMultiPaletteSection(ChunkSectionParser.SectionData section,
                                                             int lx, int lz, int relX, int relZ,
                                                             int sectionBaseY, int effectiveStartY,
                                                             int scanBottomY, int chunkBottomY,
                                                             boolean isCaveMode, boolean underair,
                                                             boolean worldHasSkylight, LightMode lightMode,
                                                             int heightMapValue, List<OverlayData> overlayList,
                                                             BlockPropertyLookup blockLookup) {
        int localStartY = 15;
        if (effectiveStartY >= sectionBaseY && effectiveStartY <= sectionBaseY + 15) {
            localStartY = effectiveStartY - sectionBaseY;
        }
        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

        int highestBlockY = -1;

        for (int ly = localStartY; ly >= localScanBottomY; ly--) {
            int worldY = sectionBaseY + ly;
            if (worldY < scanBottomY) break;
            if (worldY < chunkBottomY) break;

            ChunkSectionParser.BlockState state = ChunkSectionParser.getBlockStateAt(section, lx, ly, lz);

            if (state.isAir()) {
                continue;
            }

            if (isCaveMode && !underair) {
                continue;
            }

            if (blockLookup.isWaterloggedSurface(state.name(), state.properties())
                && !blockLookup.shouldOverlay(state.name())) {
                int opacity = blockLookup.getLightBlock("minecraft:water");
                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                addOverlay(overlayList, "minecraft:water", worldY, opacity, overlayLight, blockLookup);

                byte surfaceLight = overlayLight;
                String biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz);
                return new SurfaceResult(state, worldY, highestBlockY < 0 ? worldY : highestBlockY, biomeName, surfaceLight, overlayList);
            }

            if (blockLookup.isTranslucentFluid(state.name())) {
                int opacity = blockLookup.getLightBlock(state.name());
                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                addOverlay(overlayList, state.name(), worldY, opacity, overlayLight, blockLookup);
                if (highestBlockY < 0) highestBlockY = worldY;
                continue;
            }

            // 水logged 透明方块（海草、海带等）：先添加水 overlay，再添加方块 overlay
            // 匹配 Xaero 的 fluid-state-first 处理顺序
            if (state.isWaterlogged() && blockLookup.shouldOverlay(state.name())) {
                int waterOpacity = blockLookup.getLightBlock("minecraft:water");
                byte waterLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                addOverlay(overlayList, "minecraft:water", worldY, waterOpacity, waterLight, blockLookup);
                int opacity = blockLookup.getLightBlock(state.name());
                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                addOverlay(overlayList, state.name(), worldY, opacity, overlayLight, blockLookup);
                if (highestBlockY < 0) highestBlockY = worldY;
                continue;
            }

            if (blockLookup.isInvisible(state.name())) {
                continue;
            }

            if (blockLookup.isTransparent(state.name())) {
                int opacity = blockLookup.getLightBlock(state.name());
                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                addOverlay(overlayList, state.name(), worldY, opacity, overlayLight, blockLookup);
                if (highestBlockY < 0) highestBlockY = worldY;
                continue;
            }

            if (!blockLookup.hasVanillaColor(state.name())) {
                continue;
            }

            byte surfaceLight = calculateSurfaceLight(section, lx, ly, lz, worldY,
                heightMapValue, overlayList, lightMode, worldHasSkylight, blockLookup);
            String biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz);
            return new SurfaceResult(state, worldY, highestBlockY < 0 ? worldY : highestBlockY, biomeName, surfaceLight, overlayList);
        }

        return null;
    }

    private static boolean updateUnderairState(ChunkSectionParser.SectionData section,
                                                int lx, int lz, int sectionBaseY, int effectiveStartY,
                                                int scanBottomY, int chunkBottomY,
                                                boolean isCaveMode, boolean underair) {
        if (!isCaveMode || underair) {
            return underair;
        }

        if (section.blockPalette().size() == 1 && section.blockData() == null) {
            if (section.blockPalette().get(0).isAir()) {
                return true;
            }
            return underair;
        }

        int localStartY = Math.min(effectiveStartY - sectionBaseY, 15);
        if (localStartY < 0) localStartY = 15;
        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

        for (int ly = localStartY; ly >= localScanBottomY; ly--) {
            int worldY = sectionBaseY + ly;
            if (worldY < scanBottomY) break;
            if (worldY < chunkBottomY) break;

            ChunkSectionParser.BlockState state = ChunkSectionParser.getBlockStateAt(section, lx, ly, lz);
            if (state.isAir()) {
                return true;
            }
        }

        return underair;
    }

    private static void recordPixelData(MapRegionData data, SurfaceResult surface,
                                         int relX, int relZ) {
        data.hasData[relX][relZ] = true;
        data.blockNames[relX][relZ] = surface.topState() != null ? surface.topState().name() : "minecraft:air";
        int topBlockYValue = (surface.highestBlockY() >= 0) ? surface.highestBlockY() : surface.topY();
        data.topBlockY[relX][relZ] = topBlockYValue;
        data.heightMap[relX][relZ] = surface.topY();
        data.biomeNames[relX][relZ] = surface.biomeName() != null ? surface.biomeName() : DEFAULT_BIOME;
        data.lightMap[relX][relZ] = surface.surfaceLight();

        if (!surface.overlayList().isEmpty()) {
            data.overlays.put(relX * REGION_SIZE_BLOCKS + relZ, surface.overlayList());
        }
    }

    private static byte calculateSurfaceLight(ChunkSectionParser.SectionData section,
                                                int lx, int ly, int lz, int worldY,
                                                int heightMapValue,
                                                List<OverlayData> overlayList,
                                                LightMode lightMode,
                                                boolean worldHasSkylight,
                                                BlockPropertyLookup blockLookup) {
        byte blockLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
        byte skyLight = ChunkSectionParser.getSkyLight(section, lx, ly, lz);

        boolean hasFluidOverlay = overlayList.stream()
            .anyMatch(o -> blockLookup.isWater(o.blockName));

        boolean hasSkyAccess = worldY >= heightMapValue;

        boolean isGlowing = blockLookup.isGlowing(
            ChunkSectionParser.getBlockStateAt(section, lx, ly, lz).name());

        return lightMode.calculateEffectiveLight(
            blockLight, skyLight, hasSkyAccess, hasFluidOverlay, isGlowing, worldHasSkylight);
    }

    static byte[] serializeToXaeroFormat(MapRegionData data, int minBuildHeight,
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

                                        continue;
                                    }

                                    String blockName = data.blockNames[rx][rz];
                                    if (blockName == null) blockName = DEFAULT_BLOCK;
                                    int height = data.heightMap[rx][rz];
                                    int topY = data.topBlockY[rx][rz];
                                    int topHeight = (topY >= 0) ? topY : height;
                                    String biomeName = data.biomeNames[rx][rz];
                                    if (biomeName == null) biomeName = DEFAULT_BIOME;
                                    int light = data.lightMap[rx][rz];
                                    List<OverlayData> overlays = data.overlays.get(rx * REGION_SIZE_BLOCKS + rz);
                                    boolean hasOverlays = overlays != null && !overlays.isEmpty();
                                    boolean isGrass = blockLookup.isGrassBlock(blockName);
                                    boolean topHeightDifferent = (height != topHeight);

                                    int params = 0;
                                    if (!isGrass) params |= 1;
                                    if (hasOverlays) params |= 2;
                                    params |= light << 8;
                                    params |= encodeHeightToParams(height);
                                    if (biomeName != null) params |= 0x100000;
                                    if (topHeightDifferent) params |= 0x1000000;

                                    if (!isGrass && !blockPalette.containsKey(blockName)) params |= 0x200000;
                                    if (biomeName != null && !biomePalette.containsKey(biomeName)) params |= 0x400000;

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
                                        for (OverlayData overlay : overlays) {
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

    private static void serializeOverlay(OverlayData overlay, DataOutputStream dos,
                                          Map<String, Integer> blockPalette,
                                          BlockPropertyLookup blockLookup) throws IOException {
        boolean isWater = blockLookup.isWater(overlay.blockName);
        int opacity = overlay.opacity;
        int light = overlay.light;

        int overlayParams = 0;
        if (!isWater) overlayParams |= 1;
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

    private static void addOverlay(List<OverlayData> overlayList, String blockName, int y, int opacityToAdd, int light,
                                    BlockPropertyLookup blockLookup) {
        if (opacityToAdd > 15) {
            opacityToAdd = 15;
        }

        if (opacityToAdd == 0 && !blockLookup.isWater(blockName)) {
            String blockId = blockName.toLowerCase();
            if (blockId.contains("seagrass") || blockId.contains("kelp") ||
                blockLookup.isTransparent(blockName)) {
                opacityToAdd = 1;
            }
        }

        OverlayData lastOverlay = overlayList.isEmpty() ? null : overlayList.get(overlayList.size() - 1);
        if (lastOverlay != null && lastOverlay.blockName.equals(blockName)) {
            lastOverlay.opacity = Math.min(15, lastOverlay.opacity + opacityToAdd);
        } else {
            overlayList.add(new OverlayData(blockName, y, opacityToAdd, light));
        }
    }

    static class OverlayData {
        final String blockName;
        final int y;
        int opacity;
        final int light;

        OverlayData(String blockName, int y, int opacity, int light) {
            this.blockName = blockName;
            this.y = y;
            this.opacity = opacity;
            this.light = light;
        }
    }

    static class MapRegionData {
        final String[][] blockNames;
        final int[][] topBlockY;
        final String[][] biomeNames;
        final int[][] heightMap;
        final byte[][] lightMap;
        final boolean[][] hasData;
        final boolean[][] chunkExists;
        final Map<Integer, List<OverlayData>> overlays;
        final int minBuildHeight;
        final LightMode lightMode;

        MapRegionData(int minBuildHeight, LightMode lightMode) {
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
        }
    }
}
