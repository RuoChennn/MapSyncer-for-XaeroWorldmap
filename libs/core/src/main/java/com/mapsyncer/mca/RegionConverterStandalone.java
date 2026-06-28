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
            LOGGER.warn("Failed to convert region ({}, {})", regionX, regionZ, e);
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

        try (McaReader reader = McaReader.open(mcaPath.toString())) {
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
        boolean fullCave = caveStart == Integer.MIN_VALUE;
        int[][] heightMap = chunk.heightmap();
        int chunkBottomY = chunk.chunkBottomY();

        // per-pixel 状态数组（chunk 局部复用，结束时 GC）
        boolean[] blockFound = new boolean[256];
        boolean[] underair = new boolean[256];
        @SuppressWarnings("unchecked")
        ArrayList<OverlayData>[] overlayLists = new ArrayList[256];
        int[] topPixelH = new int[256];   // 最高非表面方块 Y（overlay 之上）
        Arrays.fill(topPixelH, -1);

        for (int i = 0; i < 256; i++) {
            underair[i] = fullCave;
        }

        // ----- sections 外层（对齐 Xaero 循环次序）-----
        int sectionIndex = 0;
        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.blockPalette().isEmpty()) continue;

            int sectionY = section.sectionY();
            int sectionBaseY = sectionY * 16;
            int sectionTopY = sectionBaseY + 15;
            int sectionBottomY = sectionBaseY;

            if (sectionTopY < chunkBottomY) continue;

            boolean singlePalette = section.blockPalette().size() == 1 && section.blockData() == null;
            ChunkSectionParser.BlockState singleState = singlePalette
                ? section.blockPalette().get(0) : null;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int relX = chunkX * 16 + lx;
                    int relZ = chunkZ * 16 + lz;
                    if (relX >= REGION_SIZE_BLOCKS || relZ >= REGION_SIZE_BLOCKS) continue;

                    int pos = (lz << 4) | lx;
                    if (blockFound[pos]) continue;

                    int heightMapValue = heightMap[lx][lz];

                    int scanBottomY;
                    int startY;
                    if (isCaveMode) {
                        startY = caveStart;
                        scanBottomY = Math.max(caveStart - caveDepth, minBuildHeight);
                    } else {
                        startY = ChunkDataParser.getHeightmapStartY(chunk, lx, lz, worldTopY);
                        scanBottomY = minBuildHeight;
                    }

                    if (isCaveMode && sectionTopY > startY) continue;
                    if (sectionBottomY < scanBottomY) continue;

                    int effectiveStartY = computeEffectiveStartY(sectionIndex, startY, worldTopY,
                        isCaveMode, heightMapValue, chunkBottomY, sectionTopY);

                    if (!isCaveMode && effectiveStartY < sectionBottomY) continue;

                    if (singlePalette) {
                        if (tryProcessSinglePalettePixel(chunk, section, sectionBaseY,
                                lx, lz, relX, relZ, effectiveStartY, scanBottomY,
                                singleState, heightMapValue, isCaveMode, worldHasSkylight,
                                lightMode, underair, overlayLists, topPixelH, blockFound, data,
                                blockLookup)) continue;
                    } else {
                        tryProcessMultiPalettePixel(chunk, section, sectionBaseY,
                            lx, lz, relX, relZ, effectiveStartY, scanBottomY,
                            chunkBottomY, heightMapValue, isCaveMode, worldHasSkylight,
                            lightMode, underair, overlayLists, topPixelH, blockFound, data,
                            blockLookup);
                    }
                }
            }

            sectionIndex++;
        }
    }

    /**
     * 根据世界Y坐标查找对应的Section（跨section光照查询辅助）
     *
     * @param chunk Chunk数据
     * @param worldY 世界Y坐标
     * @return 包含该Y坐标的Section，如果不存在则返回null
     */
    private static ChunkSectionParser.SectionData findSectionAt(ChunkDataParser.ChunkInfo chunk, int worldY) {
        ChunkSectionParser.SectionData[] lookup = chunk.sectionLookup();
        if (lookup == null) return null;
        int idx = (worldY >> 4) - chunk.minSectionY();
        if (idx >= 0 && idx < lookup.length) return lookup[idx];
        return null;
    }

    /**
     * 跨section读取方块光照（处理ly+1越界到下一个section的情况）
     *
     * @param chunk Chunk数据
     * @param currentSection 当前Section
     * @param lx 局部X (0-15)
     * @param ly 局部Y (0-15，当前section内)
     * @param lz 局部Z (0-15)
     * @param worldY 世界Y坐标（要查询光照的位置）
     * @return 方块光照值 (0-15)，如果无法查询则返回0
     */
    private static byte getBlockLightCrossSection(ChunkDataParser.ChunkInfo chunk,
                                                   ChunkSectionParser.SectionData currentSection,
                                                   int lx, int ly, int lz, int worldY) {
        int sectionY = worldY >> 4;
        if (sectionY == currentSection.sectionY()) {
            int localY = worldY - (sectionY * 16);
            if (localY >= 0 && localY <= 15) {
                return ChunkSectionParser.getBlockLight(currentSection, lx, localY, lz);
            }
        }
        ChunkSectionParser.SectionData targetSection = findSectionAt(chunk, worldY);
        if (targetSection != null) {
            int localY = worldY - (targetSection.sectionY() * 16);
            return ChunkSectionParser.getBlockLight(targetSection, lx, localY, lz);
        }
        return 0;
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

    // ===== per-pixel, per-section 处理（sections-inner）=====

    /**
     * 单 palette section 中处理一个像素列。
     * @return true 表示该像素已找到表面
     */
    private static boolean tryProcessSinglePalettePixel(
            ChunkDataParser.ChunkInfo chunk, ChunkSectionParser.SectionData section,
            int sectionBaseY, int lx, int lz, int relX, int relZ,
            int effectiveStartY, int scanBottomY,
            ChunkSectionParser.BlockState singleState, int heightMapValue,
            boolean isCaveMode, boolean worldHasSkylight, LightMode lightMode,
            boolean[] underair, ArrayList<OverlayData>[] overlayLists,
            int[] topPixelH, boolean[] blockFound, MapRegionData data,
            BlockPropertyLookup blockLookup) {

        int pos = (lz << 4) | lx;
        if (singleState.isAir()) return false;
        if (isCaveMode && !underair[pos]) return false;

        String blockName = singleState.name();
        int flags = blockLookup.getFlags(blockName);

        int localStartY = Math.min(effectiveStartY - sectionBaseY, 15);
        if (localStartY < 0) localStartY = 15;
        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

        for (int ly = localStartY; ly >= localScanBottomY; ly--) {
            int worldY = sectionBaseY + ly;
            if (worldY < scanBottomY) break;

            ArrayList<OverlayData> overlays = overlayLists[pos];

            if ((flags & BlockPropertyLookup.FLAG_WATER_INHERITING) != 0) {
                int opacity = blockLookup.getLightBlock("minecraft:water");
                int aboveWorldY = worldY + 1;
                byte light = calculateSurfaceLight(chunk, section, lx, ly, lz, aboveWorldY,
                    heightMapValue, overlays, lightMode, worldHasSkylight, blockLookup);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    "minecraft:water", worldY, opacity, light, blockLookup);
                String biomeName = getBiomeWithFallback(chunk, section, lx, ly, lz, true);
                recordPixelDirect(data, singleState, worldY, worldY, biomeName, light,
                    overlayLists[pos], relX, relZ);
                blockFound[pos] = true;
                return true;
            }

            if (blockLookup.isWaterloggedSurface(blockName, singleState.properties())
                && (flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) == 0) {
                int opacity = blockLookup.getLightBlock("minecraft:water");
                int aboveWorldY = worldY + 1;
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    "minecraft:water", worldY, opacity, light, blockLookup);
                String biomeName = getBiomeWithFallback(chunk, section, lx, ly, lz, true);
                recordPixelDirect(data, singleState, worldY, worldY, biomeName, light,
                    overlayLists[pos], relX, relZ);
                blockFound[pos] = true;
                return true;
            }

            if ((flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) != 0) {
                int opacity = blockLookup.getLightBlock(blockName);
                int aboveWorldY = worldY + 1;
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    blockName, worldY, opacity, light, blockLookup);
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_INVISIBLE) != 0) continue;

            int aboveWorldY = worldY + 1;
            byte light = calculateSurfaceLight(chunk, section, lx, ly, lz, aboveWorldY,
                heightMapValue, overlays, lightMode, worldHasSkylight, blockLookup);
            String biomeName = getBiomeWithFallback(chunk, section, lx, ly, lz, true);
            recordPixelDirect(data, singleState, worldY, worldY, biomeName, light,
                overlayLists[pos], relX, relZ);
            blockFound[pos] = true;
            return true;
        }

        return false;
    }

    /**
     * 多 palette section 中处理一个像素列。
     */
    private static void tryProcessMultiPalettePixel(
            ChunkDataParser.ChunkInfo chunk, ChunkSectionParser.SectionData section,
            int sectionBaseY, int lx, int lz, int relX, int relZ,
            int effectiveStartY, int scanBottomY, int chunkBottomY,
            int heightMapValue, boolean isCaveMode, boolean worldHasSkylight,
            LightMode lightMode, boolean[] underair, ArrayList<OverlayData>[] overlayLists,
            int[] topPixelH, boolean[] blockFound, MapRegionData data,
            BlockPropertyLookup blockLookup) {

        int pos = (lz << 4) | lx;
        int localStartY = 15;
        if (effectiveStartY >= sectionBaseY && effectiveStartY <= sectionBaseY + 15) {
            localStartY = effectiveStartY - sectionBaseY;
        }
        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

        for (int ly = localStartY; ly >= localScanBottomY; ly--) {
            int worldY = sectionBaseY + ly;
            if (worldY < scanBottomY) break;
            if (worldY < chunkBottomY) break;

            ChunkSectionParser.BlockState state = ChunkSectionParser.getBlockStateAt(section, lx, ly, lz);
            if (state.isAir()) continue;
            if (isCaveMode && !underair[pos]) continue;

            String blockName = state.name();
            int flags = blockLookup.getFlags(blockName);
            ArrayList<OverlayData> overlays = overlayLists[pos];

            if ((flags & BlockPropertyLookup.FLAG_WATER_INHERITING) != 0) {
                int opacity = blockLookup.getLightBlock("minecraft:water");
                int aboveWorldY = worldY + 1;
                byte light = calculateSurfaceLight(chunk, section, lx, ly, lz, aboveWorldY,
                    heightMapValue, overlays, lightMode, worldHasSkylight, blockLookup);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    "minecraft:water", worldY, opacity, light, blockLookup);
                int topBlockY = topPixelH[pos] < 0 ? worldY : topPixelH[pos];
                String biomeName = getBiomeWithFallback(chunk, section, lx, ly, lz, true);
                recordPixelDirect(data, state, worldY, topBlockY, biomeName, light,
                    overlayLists[pos], relX, relZ);
                blockFound[pos] = true;
                return;
            }

            if (blockLookup.isWaterloggedSurface(blockName, state.properties())
                && (flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) == 0) {
                int opacity = blockLookup.getLightBlock("minecraft:water");
                int aboveWorldY = worldY + 1;
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    "minecraft:water", worldY, opacity, light, blockLookup);
                int topBlockY = topPixelH[pos] < 0 ? worldY : topPixelH[pos];
                String biomeName = getBiomeWithFallback(chunk, section, lx, ly, lz, true);
                recordPixelDirect(data, state, worldY, topBlockY, biomeName, light,
                    overlayLists[pos], relX, relZ);
                blockFound[pos] = true;
                return;
            }

            if ((flags & BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID) != 0) {
                int opacity = blockLookup.getLightBlock(blockName);
                int aboveWorldY = worldY + 1;
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    blockName, worldY, opacity, light, blockLookup);
                if (topPixelH[pos] < 0) topPixelH[pos] = worldY;
                continue;
            }

            if (state.isWaterlogged() && (flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) != 0) {
                int waterOpacity = blockLookup.getLightBlock("minecraft:water");
                int aboveWorldY = worldY + 1;
                byte waterLight = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    "minecraft:water", worldY, waterOpacity, waterLight, blockLookup);
                int opacity = blockLookup.getLightBlock(blockName);
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                addOverlayToList(overlays, overlays == null ? overlayLists[pos] : overlays,
                    blockName, worldY, opacity, light, blockLookup);
                if (topPixelH[pos] < 0) topPixelH[pos] = worldY;
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_INVISIBLE) != 0) continue;

            if ((flags & BlockPropertyLookup.FLAG_TRANSPARENT) != 0) {
                int opacity = blockLookup.getLightBlock(blockName);
                int aboveWorldY = worldY + 1;
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                addOverlayToList(overlays, overlays == null ? (overlayLists[pos] = new ArrayList<>()) : overlays,
                    blockName, worldY, opacity, light, blockLookup);
                if (topPixelH[pos] < 0) topPixelH[pos] = worldY;
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_HAS_VANILLA_COLOR) == 0) continue;

            int aboveWorldY = worldY + 1;
            byte light = calculateSurfaceLight(chunk, section, lx, ly, lz, aboveWorldY,
                heightMapValue, overlays, lightMode, worldHasSkylight, blockLookup);
            int topBlockY = topPixelH[pos] < 0 ? worldY : topPixelH[pos];
            String biomeName = getBiomeWithFallback(chunk, section, lx, ly, lz, true);
            recordPixelDirect(data, state, worldY, topBlockY, biomeName, light,
                overlayLists[pos], relX, relZ);
            blockFound[pos] = true;
            return;
        }
    }

    /** 直接写入 MapRegionData（内联原 recordPixelData） */

    /**
     * biome 查找 + 跨 section 回退，对齐 Xaero BiomeManager 噪声插值行为。
     *
     * <p>Xaero 通过 BiomeZoomer 从 4×4×4 网格插值到 1×1 块分辨率，
     * 即使当前 section 缺少 biome 数据也可从相邻 section 推断。
     * 我们无法在服务端进行噪声插值，但可在当前 section 的 biomePalette
     * 为空或返回无效 biome 时搜索同 chunk 内的其他 section 作为回退。</p>
     *
     * <p>回退时根据绝对 Y 计算回退 section 内的局部 ly，确保查找的是
     * 同一绝对高度的 biome，而不是回退 section 内不同 Y 的 biome。</p>
     */
    private static String getBiomeWithFallback(ChunkDataParser.ChunkInfo chunk,
                                                ChunkSectionParser.SectionData section,
                                                int lx, int ly, int lz,
                                                boolean smoothBoundary) {
        // 1. 尝试当前 section（需排除 the_void — 表示该 section 缺少有效 biome 数据）
        if (!section.biomePalette().isEmpty()) {
            String biome = ChunkSectionParser.getBiomeAt(section, lx, ly, lz, smoothBoundary);
            if (biome != null && !biome.equals(DEFAULT_BIOME)) return biome;
        }

        // 2. 回退到同 chunk 其他 section：按绝对 Y 对齐查找
        int absoluteY = section.sectionY() * 16 + ly;
        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s == section || s.biomePalette().isEmpty()) continue;
            int fallbackLy = absoluteY - s.sectionY() * 16;
            if (fallbackLy < 0 || fallbackLy > 15) continue;
            String biome = ChunkSectionParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (biome != null && !biome.equals(DEFAULT_BIOME)) return biome;
        }

        // 3. 绝对 Y 不在回退 section 范围内时，退而用原始 ly（保持旧兼容）
        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s == section || s.biomePalette().isEmpty()) continue;
            String biome = ChunkSectionParser.getBiomeAt(s, lx, ly, lz, smoothBoundary);
            if (biome != null && !biome.equals(DEFAULT_BIOME)) return biome;
        }

        return DEFAULT_BIOME;
    }

    /** 直接写入 MapRegionData（内联原 recordPixelData） */
    private static void recordPixelDirect(MapRegionData data, ChunkSectionParser.BlockState surfaceState,
                                          int topY, int highestBlockY, String biomeName,
                                          byte surfaceLight, List<OverlayData> overlayList,
                                          int relX, int relZ) {
        data.hasData[relX][relZ] = true;
        data.blockNames[relX][relZ] = surfaceState != null ? surfaceState.name() : "minecraft:air";
        data.topBlockY[relX][relZ] = highestBlockY >= 0 ? highestBlockY : topY;
        data.heightMap[relX][relZ] = topY;
        data.biomeNames[relX][relZ] = biomeName != null ? biomeName : DEFAULT_BIOME;
        data.lightMap[relX][relZ] = surfaceLight;
        if (overlayList != null && !overlayList.isEmpty()) {
            data.overlays.put(relX * REGION_SIZE_BLOCKS + relZ, overlayList);
        }
    }

    /** 添加 overlay 到列表（保持同名合并语义，对齐原 addOverlay） */
    private static void addOverlayToList(List<OverlayData> currentList, ArrayList<OverlayData> list,
                                         String blockName, int y, int opacityToAdd, int light,
                                         BlockPropertyLookup blockLookup) {
        if (currentList != list) {
            // 列表首次创建，直接添加
            addOverlaySingle(list, blockName, y, opacityToAdd, light, blockLookup);
            return;
        }
        // 列表已存在，尝试合并
        if (opacityToAdd > 15) opacityToAdd = 15;
        if (opacityToAdd == 0 && !blockLookup.isWater(blockName)) {
            String lower = blockName.toLowerCase();
            if (lower.contains("seagrass") || lower.contains("kelp") || blockLookup.isTransparent(blockName)) {
                opacityToAdd = 1;
            }
        }
        OverlayData last = list.isEmpty() ? null : list.get(list.size() - 1);
        if (last != null && last.blockName.equals(blockName)) {
            last.opacity = Math.min(15, last.opacity + opacityToAdd);
        } else {
            list.add(new OverlayData(blockName, y, opacityToAdd, light));
        }
    }

    private static void addOverlaySingle(ArrayList<OverlayData> list, String blockName, int y,
                                         int opacityToAdd, int light, BlockPropertyLookup blockLookup) {
        if (opacityToAdd > 15) opacityToAdd = 15;
        if (opacityToAdd == 0 && !blockLookup.isWater(blockName)) {
            String lower = blockName.toLowerCase();
            if (lower.contains("seagrass") || lower.contains("kelp") || blockLookup.isTransparent(blockName)) {
                opacityToAdd = 1;
            }
        }
        list.add(new OverlayData(blockName, y, opacityToAdd, light));
    }

    private static byte calculateSurfaceLight(ChunkDataParser.ChunkInfo chunk,
                                                ChunkSectionParser.SectionData currentSection,
                                                int lx, int ly, int lz, int worldY,
                                                int heightMapValue,
                                                List<OverlayData> overlayList,
                                                LightMode lightMode,
                                                boolean worldHasSkylight,
                                                BlockPropertyLookup blockLookup) {
        byte blockLight = getBlockLightCrossSection(chunk, currentSection, lx, ly, lz, worldY);
        byte skyLight = 0;
        ChunkSectionParser.SectionData stateSection = null;
        int worldYSkySectionY = worldY >> 4;
        if (worldYSkySectionY == currentSection.sectionY()) {
            int localY = worldY - (worldYSkySectionY * 16);
            if (localY >= 0 && localY <= 15) {
                skyLight = ChunkSectionParser.getSkyLight(currentSection, lx, localY, lz);
            }
        } else {
            stateSection = findSectionAt(chunk, worldY);
            if (stateSection != null) {
                int localY = worldY - (stateSection.sectionY() * 16);
                skyLight = ChunkSectionParser.getSkyLight(stateSection, lx, localY, lz);
            }
        }

        boolean hasFluidOverlay = false;
        if (overlayList != null) {
            for (OverlayData o : overlayList) {
                if (blockLookup.isWater(o.blockName)) { hasFluidOverlay = true; break; }
            }
        }

        boolean hasSkyAccess = worldY >= heightMapValue;

        if (stateSection == null) stateSection = findSectionAt(chunk, worldY);
        if (stateSection == null) stateSection = currentSection;
        int stateLocalY = worldY - (stateSection.sectionY() * 16);
        if (stateLocalY < 0 || stateLocalY > 15) stateLocalY = ly;
        boolean isGlowing = blockLookup.isGlowing(
            ChunkSectionParser.getBlockStateAt(stateSection, lx, stateLocalY, lz).name());

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
