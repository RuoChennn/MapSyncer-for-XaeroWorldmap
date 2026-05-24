package com.mapsyncer.mca;

import com.mapsyncer.server.BlockPropertyResolver;
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

    /**
     * SLF4J 日志记录器
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionConverterStandalone.class);

    /**
     * 默认方块名称（用于空白像素）
     *
     * <p>用于没有数据的空白像素（如末地虚空区域）</p>
     */
    private static final String DEFAULT_BLOCK = "minecraft:air";

    /**
     * 默认生物群系名称（用于虚空区域）
     *
     * <p>使用 air + the_void 以正确显示虚空区域为深紫色</p>
     */
    private static final String DEFAULT_BIOME = "minecraft:the_void";

    /**
     * 区域大小（块数）- 512x512 块
     */
    public static final int REGION_SIZE_BLOCKS = 512;

    /**
     * 每个区域的区块数量 - 32x32 区块
     */
    public static final int CHUNKS_PER_REGION = 32;

    /**
     * 每个 Tile Chunk 的块数 - 64x64 块
     */
    public static final int BLOCKS_PER_TILE_CHUNK = 64;

    /**
     * 每个 Tile 的块数 - 16x16 块（对应一个 Minecraft 区块）
     */
    public static final int BLOCKS_PER_TILE = 16;

    /**
     * 每个 Tile Chunk 的 Tile 数量 - 4x4 Tile
     */
    public static final int TILES_PER_TILE_CHUNK = 4;

    /**
     * 每个区域的 Tile Chunk 数量 - 8x8 Tile Chunk
     */
    public static final int TILE_CHUNKS_PER_REGION = 8;

    /**
     * Xaero 格式主版本号
     */
    public static final int MAJOR_VERSION = 6;

    /**
     * Xaero 格式次版本号
     */
    public static final int MINOR_VERSION = 8;

    /**
     * 转换后的区域数据记录
     *
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @param xaeroData Xaero 格式的地图数据字节数组
     */
    public record ConvertedRegion(int regionX, int regionZ, byte[] xaeroData) {}

    /**
     * 洞穴模式参数记录
     *
     * <p>用于洞穴模式下的深度检测和光照计算</p>
     *
     * @param caveStart 洞穴开始高度（世界Y坐标）
     * @param caveDepth 洞穴深度（从 caveStart 向下的范围）
     */
    public record CaveModeParams(
        int caveStart,      // 洞穴开始高度（世界Y坐标）
        int caveDepth       // 洞穴深度（从 caveStart 向下的范围）
    ) {
        /**
         * 无洞穴模式参数（默认值）
         *
         * <p>caveStart = Integer.MAX_VALUE 表示不使用洞穴模式</p>
         */
        public static final CaveModeParams NONE = new CaveModeParams(Integer.MAX_VALUE, 0);

        /**
         * 创建默认洞穴参数
         *
         * @param worldTopY 世界顶部高度
         * @param defaultDepth 默认深度（通常为63，用于下界等）
         * @return CaveModeParams 实例
         */
        public static CaveModeParams createDefault(int worldTopY, int defaultDepth) {
            return new CaveModeParams(worldTopY, defaultDepth);
        }
    }

    /**
     * 转换单个区域文件（默认地表模式）
     *
     * <p>使用默认的地表模式参数转换区域文件</p>
     *
     * @param mcaPath .mca 文件路径
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @param minBuildHeight 世界最低建筑高度（通常是 -64）
     * @param worldTopY 世界最高高度（通常是 320）
     * @return ConvertedRegion 对象，如果文件不存在或转换失败则返回 null
     */
    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY) {
        return convertRegion(mcaPath, regionX, regionZ, minBuildHeight, worldTopY,
                             LightMode.SURFACE, CaveModeParams.NONE, true);
    }

    /**
     * 转换单个区域文件（完整参数）
     *
     * <p>参考 Xaero WorldDataReader.java 第186行:</p>
     * <p>worldHasSkylight = serverWorld.dimensionType().hasSkyLight()</p>
     *
     * @param mcaPath .mca 文件路径
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @param minBuildHeight 世界最低建筑高度（通常是 -64）
     * @param worldTopY 世界最高高度（通常是 320）
     * @param lightMode 光照模式（SURFACE 或 CAVE）
     * @param caveParams 洞穴模式参数（仅洞穴模式使用）
     * @param worldHasSkylight 维度是否有天空光照（末地为 false）
     * @return ConvertedRegion 对象，如果转换失败则返回 null
     */
    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams,
                                                  boolean worldHasSkylight) {
        if (!Files.exists(mcaPath)) {
            return null;
        }

        try {
            MapRegionData regionData = readMcaFile(mcaPath, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight);
            if (regionData == null) return null;

            byte[] xaeroData = serializeToXaeroFormat(regionData, minBuildHeight);
            return new ConvertedRegion(regionX, regionZ, xaeroData);
        } catch (IOException e) {
            LOGGER.warn("Failed to convert region ({}, {}): {}", regionX, regionZ, e.getMessage());
            return null;
        }
    }

    /**
     * 转换单个区域文件（使用 DimensionTypeInfo）
     *
     * <p>DimensionTypeInfo 包含维度的所有核心属性:</p>
     * <ul>
     *   <li>minY: 最低建筑高度</li>
     *   <li>height: 维度总高度（maxY = minY + height）</li>
     *   <li>hasSkylight: 是否有天空光照</li>
     *   <li>hasCeiling: 是否有顶棚</li>
     * </ul>
     *
     * @param mcaPath .mca 文件路径
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @param dimTypeInfo 维度类型信息
     * @param lightMode 光照模式（SURFACE 或 CAVE）
     * @param caveParams 洞穴模式参数（仅洞穴模式使用）
     * @return ConvertedRegion 对象
     */
    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  DimensionTypeInfo dimTypeInfo,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams) {
        return convertRegion(mcaPath, regionX, regionZ,
                             dimTypeInfo.minY(), dimTypeInfo.maxY(),
                             lightMode, caveParams, dimTypeInfo.hasSkylight());
    }

    /**
     * 使用独立 MCA 解析器读取区域文件（默认地表模式）
     *
     * <p>遍历所有区块，解析方块和光照数据</p>
     *
     * @param mcaPath MCA 文件路径
     * @param minBuildHeight 世界最低建筑高度
     * @param worldTopY 世界最高高度
     * @return MapRegionData 区域数据对象
     * @throws IOException 如果读取失败
     */
    static MapRegionData readMcaFile(Path mcaPath, int minBuildHeight, int worldTopY) throws IOException {
        return readMcaFile(mcaPath, minBuildHeight, worldTopY, LightMode.SURFACE, CaveModeParams.NONE, true);
    }

    /**
     * 使用独立 MCA 解析器读取区域文件（完整参数）
     *
     * <p>支持地表模式和洞穴模式的数据读取</p>
     *
     * @param mcaPath MCA 文件路径
     * @param minBuildHeight 世界最低建筑高度
     * @param worldTopY 世界最高高度
     * @param lightMode 光照模式
     * @param caveParams 洞穴模式参数
     * @param worldHasSkylight 维度是否有天空光照
     * @return MapRegionData 区域数据对象
     * @throws IOException 如果读取失败
     */
    static MapRegionData readMcaFile(Path mcaPath, int minBuildHeight, int worldTopY,
                                       LightMode lightMode, CaveModeParams caveParams,
                                       boolean worldHasSkylight) throws IOException {
        MapRegionData data = new MapRegionData(minBuildHeight, lightMode);

        try (McaReader reader = new McaReader(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            for (McaReader.ChunkData chunkData : reader.readAllChunks()) {
                ChunkDataParser.ChunkInfo chunkInfo = ChunkDataParser.parseChunk(
                    chunkData.chunkX(), chunkData.chunkZ(), chunkData.nbt(), worldHeightRange
                );

                if (chunkInfo == null) continue;

                processChunk(data, chunkInfo, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight);
            }
        }

        return data;
    }

    /**
     * 处理单个 Chunk 的数据（默认地表模式）
     *
     * <p>扫描区块内的所有方块，提取表面数据</p>
     *
     * @param data 区域数据对象
     * @param chunk Chunk 信息
     * @param minBuildHeight 世界最低建筑高度
     * @param worldTopY 世界最高高度
     */
    private static void processChunk(MapRegionData data, ChunkDataParser.ChunkInfo chunk,
                                       int minBuildHeight, int worldTopY) {
        processChunk(data, chunk, minBuildHeight, worldTopY, LightMode.SURFACE, CaveModeParams.NONE, true);
    }

    /**
     * 处理单个 Chunk 的数据（完整参数）
     *
     * <p>光照计算逻辑:</p>
     *
     * <p>地表模式 (SURFACE):</p>
     * <ul>
     *   <li>只使用 BlockLight</li>
     *   <li>SkyLight 完全忽略</li>
     *   <li>所有区域使用方块光照值</li>
     * </ul>
     *
     * <p>洞穴模式 (CAVE):</p>
     * <ul>
     *   <li>同时使用 BlockLight 和 SkyLight</li>
     *   <li>露天区域（高于高度图）：SkyLight = 15（仅当 worldHasSkylight=true）</li>
     *   <li>末地维度：worldHasSkylight=false，不使用 SkyLight = 15</li>
     *   <li>水下区域：使用 BlockLight</li>
     *   <li>其他地下区域：取 max(BlockLight, SkyLight)</li>
     * </ul>
     *
     * @param data 区域数据对象
     * @param chunk Chunk 信息
     * @param minBuildHeight 世界最低建筑高度
     * @param worldTopY 世界最高高度
     * @param lightMode 光照模式
     * @param caveParams 洞穴模式参数
     * @param worldHasSkylight 维度是否有天空光照
     */
    private static void processChunk(MapRegionData data, ChunkDataParser.ChunkInfo chunk,
                                       int minBuildHeight, int worldTopY,
                                       LightMode lightMode, CaveModeParams caveParams,
                                       boolean worldHasSkylight) {
        int chunkX = chunk.chunkX();
        int chunkZ = chunk.chunkZ();

        // 标记该区块已存在（区分区块未生成和区块内虚空区域）
        data.chunkExists[chunkX][chunkZ] = true;

        // 洞穴模式参数
        int caveStart = caveParams.caveStart();
        int caveDepth = caveParams.caveDepth();

        // 洞穴模式判断
        boolean isCaveMode = caveStart != Integer.MAX_VALUE;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int relX = chunkX * 16 + lx;
                int relZ = chunkZ * 16 + lz;

                // 边界检查
                if (relX >= REGION_SIZE_BLOCKS || relZ >= REGION_SIZE_BLOCKS) {
                    continue;  // 越界跳过
                }

                // 计算扫描范围
                // 地表模式：使用高度图作为起始高度
                // 洞穴模式：使用固定的 caveStart 和 caveDepth
                int startY;
                int scanBottomY;
                int heightMapValue = chunk.heightmap()[lx][lz];
                int chunkBottomY = chunk.chunkBottomY();

                if (isCaveMode) {
                    // 洞穴模式：从 caveStart 向下扫描到 caveStart - caveDepth
                    startY = caveStart;
                    scanBottomY = Math.max(caveStart - caveDepth, minBuildHeight);
                } else {
                    // 地表模式：从高度图向下扫描
                    startY = ChunkDataParser.getHeightmapStartY(chunk, lx, lz, worldTopY);
                    scanBottomY = minBuildHeight;
                }

                ChunkSectionParser.BlockState topState = null;
                int topY = -1;
                int highestBlockY = -1;
                String biomeName = null;
                List<OverlayData> overlayList = new ArrayList<>();
                byte surfaceLight = 0;

                // 洞穴模式状态追踪（参考 Xaero WorldDataReader.java:346-351, 571-596）
                // underair: 是否已进入洞穴内部的空气区域
                // 全洞穴模式（caveStart == Integer.MIN_VALUE）初始化为 true，表示从底部开始扫描
                // 普通洞穴模式初始化为 false，需要等待进入空气区域后才开始记录方块
                boolean underair = isCaveMode && caveStart == Integer.MIN_VALUE;

                // 从最高 section 向下扫描
                // 参考 Xaero WorldDataReader: 按 sectionY 从高到低排序
                int sectionIndex = 0;  // 用于追踪当前 section 位置
                for (ChunkSectionParser.SectionData section : chunk.sections()) {
                    if (section.blockPalette().isEmpty()) continue;

                    int sectionY = section.sectionY();
                    int sectionBaseY = sectionY * 16;
                    int sectionTopY = sectionBaseY + 15;
                    int sectionBottomY = sectionBaseY;

                    // 洞穴模式：跳过高于 caveStart 的 section
                    if (isCaveMode && sectionTopY > startY) continue;

                    // 跳过低于扫描底部的 section
                    if (sectionBottomY < scanBottomY) continue;

                    if (sectionTopY < chunkBottomY) continue;

                    // 计算扫描起始高度
                    // 参考 Xaero WorldDataReader.java:425
                    // 地表模式：startHeight = heightMapValue + 3 (或 sectionBasedHeight)
                    // 洞穴模式：startHeight = caveStart
                    // 如果不是第一个 section，额外 +1 (i > 0 && ++startHeight)
                    int effectiveStartY = startY;
                    if (sectionIndex > 0) {
                        effectiveStartY = Math.min(startY + 1, worldTopY - 1);
                    }

                    // 地表模式：如果高度图值低于 chunkBottomY，使用 section 顶部
                    if (!isCaveMode && heightMapValue < chunkBottomY) {
                        effectiveStartY = sectionTopY;
                    }

                    // 洞穴模式：确保起始高度不超过 section 顶部
                    if (isCaveMode) {
                        effectiveStartY = Math.min(effectiveStartY, sectionTopY);
                    }

                    sectionIndex++;

                    // 单方块 palette section - 需要逐层扫描确定实际高度
                    if (section.blockPalette().size() == 1 && section.blockData() == null) {
                        ChunkSectionParser.BlockState singleState = section.blockPalette().get(0);

                        // 洞穴模式：整个 section 都是空气时，标记已进入洞穴内部
                        if (singleState.isAir()) {
                            if (isCaveMode) {
                                underair = true;
                            }
                            continue;
                        }

                        // 洞穴模式：还没进入洞穴内部，跳过此 section
                        if (isCaveMode && !underair) {
                            continue;
                        }

                        // 从该 section 的最高层向下扫描
                        int scanStartY = Math.min(effectiveStartY - sectionBaseY, 15);
                        if (scanStartY < 0) scanStartY = 15;

                        // 洞穴模式：计算 section 内的扫描底部
                        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

                        for (int ly = scanStartY; ly >= localScanBottomY; ly--) {
                            int worldY = sectionBaseY + ly;

                            // 洞穴模式：低于扫描底部时停止
                            if (worldY < scanBottomY) break;

                            // 检查含水方块（方块本身作为表面 + 同层水overlay）
                            // 含水方块需要添加水 overlay 来表示水覆盖效果
                            // opacity 使用水的 lightBlock 值，与 Xaero 一致
                            if (BlockPropertyResolver.isWaterloggedSurface(singleState.name(), singleState.properties())) {
                                topState = singleState;
                                topY = worldY;
                                data.heightMap[relX][relZ] = topY;

                                // 含水方块添加同层水 overlay（使用水的 lightBlock）
                                int opacity = BlockPropertyResolver.getLightBlock("minecraft:water");
                                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                                addOverlay(overlayList, "minecraft:water", worldY, opacity, overlayLight);
                                if (highestBlockY < 0) highestBlockY = worldY;

                                surfaceLight = overlayLight;
                                biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz, true);
                                break;
                            }

                            boolean shouldOverlay = BlockPropertyResolver.shouldOverlay(singleState.name());

                            if (shouldOverlay) {
                                // 使用 lightBlock 作为 opacity（Xaero 方式）
                                int opacity = BlockPropertyResolver.getLightBlock(singleState.name());
                                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                                addOverlay(overlayList, singleState.name(), worldY, opacity, overlayLight);
                                if (highestBlockY < 0) highestBlockY = worldY;
                                continue;  // 继续向下找表面
                            }

                            // 非透明方块 = 表面
                            topState = singleState;
                            topY = worldY;
                            if (highestBlockY < 0) highestBlockY = worldY;
                            data.heightMap[relX][relZ] = topY;

                            // 计算光照（使用光照模式）
                            surfaceLight = calculateSurfaceLight(section, lx, ly, lz, worldY,
                                heightMapValue, overlayList, lightMode, worldHasSkylight);

                            biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz, true);
                            break;
                        }

                        if (topState != null) break;  // 找到表面后跳出 section 循环
                        continue;  // 继续下一个 section
                    }

                    // 多方块 palette - 需要从位数组读取
                    // 确定在 section 内的起始局部 Y
                    int localStartY = 15;
                    if (effectiveStartY >= sectionBaseY && effectiveStartY <= sectionTopY) {
                        localStartY = effectiveStartY - sectionBaseY;
                    }

                    // 洞穴模式：计算 section 内的扫描底部
                    int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

                    // 从 localStartY 向下扫描
                    for (int ly = localStartY; ly >= localScanBottomY; ly--) {
                        int worldY = sectionBaseY + ly;

                        // 低于扫描底部时停止
                        if (worldY < scanBottomY) break;
                        if (worldY < chunkBottomY) break;

                        ChunkSectionParser.BlockState state = ChunkSectionParser.getBlockStateAt(section, lx, ly, lz);

                        // 洞穴模式核心逻辑：必须先进入空气才能记录方块（参考 Xaero WorldDataReader.java:571-596）
                        if (state.isAir()) {
                            if (isCaveMode) {
                                underair = true;  // 进入洞穴内部空气区域
                            }
                            continue;
                        }

                        // 洞穴模式：还没进入洞穴内部空气区域，跳过此方块
                        if (isCaveMode && !underair) {
                            continue;
                        }

                        // Step 1: 检查含水方块（方块本身作为表面 + 同层水overlay）
                        // 含水方块需要添加水 overlay 来表示水覆盖效果
                        // opacity 使用水的 lightBlock 值，与 Xaero 一致
                        if (BlockPropertyResolver.isWaterloggedSurface(state.name(), state.properties())) {
                            topState = state;
                            topY = worldY;
                            data.heightMap[relX][relZ] = topY;

                            // 含水方块添加同层水 overlay（使用水的 lightBlock）
                            int opacity = BlockPropertyResolver.getLightBlock("minecraft:water");
                            byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                            addOverlay(overlayList, "minecraft:water", worldY, opacity, overlayLight);
                            if (highestBlockY < 0) highestBlockY = worldY;

                            surfaceLight = overlayLight;
                            biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz);
                            break;
                        }

                        // Step 2: 检查流体（纯水作为 overlay，继续向下找表面）
                        // opacity 使用 lightBlock 值，与 Xaero 一致
                        if (BlockPropertyResolver.isTranslucentFluid(state.name())) {
                            int opacity = BlockPropertyResolver.getLightBlock(state.name());
                            byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                            addOverlay(overlayList, state.name(), worldY, opacity, overlayLight);
                            if (highestBlockY < 0) highestBlockY = worldY;
                            continue;  // 继续向下扫描找表面
                        }

                        // Step 3: 检查隐形方块（跳过）
                        if (BlockPropertyResolver.isInvisible(state.name())) {
                            continue;
                        }

                        // Step 4: 检查透明方块（作为 overlay）
                        // 参考 Xaero: overlayBuilder.build(state, state.getLightBlock(...), light, ...)
                        if (BlockPropertyResolver.isTransparent(state.name())) {
                            int opacity = BlockPropertyResolver.getLightBlock(state.name());
                            byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                            addOverlay(overlayList, state.name(), worldY, opacity, overlayLight);
                            if (highestBlockY < 0) highestBlockY = worldY;
                            continue;
                        }

                        // Step 5: 检查是否有地图颜色
                        if (!BlockPropertyResolver.hasVanillaColor(state.name())) {
                            continue;
                        }

                        // 找到可见的实体方块 = 表面
                        topState = state;
                        topY = worldY;
                        data.heightMap[relX][relZ] = topY;

                        // 计算光照（使用光照模式）
                        surfaceLight = calculateSurfaceLight(section, lx, ly, lz, worldY,
                            heightMapValue, overlayList, lightMode, worldHasSkylight);

                        biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz);
                        break;
                    }

                    if (topState != null) break;
                }

                // 记录像素数据
                if (topState != null || !overlayList.isEmpty()) {
                    data.hasData[relX][relZ] = true;
                    data.blockNames[relX][relZ] = topState != null ? topState.name() : "minecraft:air";
                    int topBlockYValue = (highestBlockY >= 0) ? highestBlockY : topY;
                    data.topBlockY[relX][relZ] = topBlockYValue;
                    // 参考 Xaero: biomeName 为 null 时使用 THE_VOID（虚空区域的深紫色）
                    data.biomeNames[relX][relZ] = biomeName != null ? biomeName : DEFAULT_BIOME;
                    data.lightMap[relX][relZ] = surfaceLight;
                    if (!overlayList.isEmpty()) {
                        data.overlays.put(relX * REGION_SIZE_BLOCKS + relZ, overlayList);
                    }
                }
            }
        }
    }

    /**
     * 计算表面有效光照值
     *
     * <p>参考 Xaero WorldDataReader.java 第557-559行:</p>
     * <ul>
     *   <li>cave && dataLight < 15 && worldHasSkylight 时才更新 skyLightLevels</li>
     *   <li>末地维度 worldHasSkylight=false，不会使用 SkyLight</li>
     * </ul>
     *
     * @param section Section 数据
     * @param lx 局部 X 坐标
     * @param ly 局部 Y 坐标
     * @param lz 局部 Z 坐标
     * @param worldY 世界 Y 坐标
     * @param heightMapValue 高度图值
     * @param overlayList overlay 列表
     * @param lightMode 光照模式
     * @param worldHasSkylight 维度是否有天空光照
     * @return 有效光照值 (0-15)
     */
    private static byte calculateSurfaceLight(ChunkSectionParser.SectionData section,
                                                int lx, int ly, int lz, int worldY,
                                                int heightMapValue,
                                                List<OverlayData> overlayList,
                                                LightMode lightMode,
                                                boolean worldHasSkylight) {
        byte blockLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
        byte skyLight = ChunkSectionParser.getSkyLight(section, lx, ly, lz);

        // 检查是否有流体 overlay（水/熔岩）
        boolean hasFluidOverlay = overlayList.stream()
            .anyMatch(o -> BlockPropertyResolver.isWater(o.blockName));

        // 检查是否有天空访问（位置高于高度图）
        boolean hasSkyAccess = worldY >= heightMapValue;

        // 发光方块检测
        boolean isGlowing = BlockPropertyResolver.isGlowing(
            ChunkSectionParser.getBlockStateAt(section, lx, ly, lz).name());

        return lightMode.calculateEffectiveLight(
            blockLight, skyLight, hasSkyAccess, hasFluidOverlay, isGlowing, worldHasSkylight);
    }

    /**
     * 序列化为 Xaero 格式
     *
     * <p>重要区分:</p>
     * <ul>
     *   <li>区块存在但像素为虚空区域 → 写入 AIR + void（渲染深紫色）</li>
     *   <li>区块不存在（尚未生成） → 写入空 Tile（tileMarker = -1），客户端跳过渲染</li>
     * </ul>
     *
     * <p>坐标映射:</p>
     * <ul>
     *   <li>一个 Tile 对应一个 Minecraft 区块（都是 16x16 块）</li>
     *   <li>chunkX = tileChunkO * 4 + tileI</li>
     *   <li>chunkZ = tileChunkP * 4 + tileJ</li>
     * </ul>
     *
     * @param data 区域数据对象
     * @param minBuildHeight 世界最低建筑高度
     * @return Xaero 格式的字节数组
     * @throws IOException 如果序列化失败
     */
    static byte[] serializeToXaeroFormat(MapRegionData data, int minBuildHeight) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // Version header
            dos.writeByte(0xFF);
            dos.writeInt((MAJOR_VERSION << 16) | MINOR_VERSION);

            Map<String, Integer> blockPalette = new LinkedHashMap<>();
            Map<String, Integer> biomePalette = new LinkedHashMap<>();

            // 8x8 TileChunks
            for (int tileChunkO = 0; tileChunkO < TILE_CHUNKS_PER_REGION; tileChunkO++) {
                for (int tileChunkP = 0; tileChunkP < TILE_CHUNKS_PER_REGION; tileChunkP++) {
                    dos.writeByte((tileChunkO << 4) | tileChunkP);

                    // 4x4 Tiles
                    for (int tileI = 0; tileI < TILES_PER_TILE_CHUNK; tileI++) {
                        for (int tileJ = 0; tileJ < TILES_PER_TILE_CHUNK; tileJ++) {
                            // 计算该 Tile 对应的区块坐标
                            // 一个 Tile 是 16x16 块，正好对应一个 Minecraft 区块
                            int chunkX = tileChunkO * 4 + tileI;
                            int chunkZ = tileChunkP * 4 + tileJ;

                            // 计算像素基础坐标（用于访问像素数据）
                            int baseX = chunkX * 16;  // 区块起始 X
                            int baseZ = chunkZ * 16;  // 区块起始 Z

                            // 检查该区块是否存在
                            if (!data.chunkExists[chunkX][chunkZ]) {
                                // 区块不存在（尚未生成）：写入空 Tile 标记
                                // 参考 Xaero 格式：空 Tile 用 tileMarker = -1 表示
                                dos.writeInt(-1);
                                continue;
                            }

                            // 区块存在：写入 Tile 数据（16x16 块 = 16x16 像素）
                            // 第一个像素的 params 作为 tileMarker（不能是 -1）
                            for (int bx = 0; bx < BLOCKS_PER_TILE; bx++) {
                                for (int bz = 0; bz < BLOCKS_PER_TILE; bz++) {
                                    int rx = baseX + bx;
                                    int rz = baseZ + bz;

                                    if (!data.hasData[rx][rz]) {
                                        // 区块存在但像素为虚空：写入 AIR 方块 + null biome
                                        // 参考 Xaero prepareForWriting：state=AIR, biome=null, height=defaultHeight
                                        String emptyBlockName = "minecraft:air";
                                        int emptyHeight = minBuildHeight;
                                        int emptyParams = 0;

                                        emptyParams |= 1;  // 非 grass
                                        emptyParams |= 0 << 8;  // light = 0
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

                                    // 正常像素数据
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
                                    boolean isGrass = BlockPropertyResolver.isGrassBlock(blockName);
                                    boolean topHeightDifferent = (height != topHeight);

                                    // Build params
                                    int params = 0;
                                    if (!isGrass) params |= 1;
                                    if (hasOverlays) params |= 2;
                                    params |= light << 8;
                                    params |= encodeHeightToParams(height);
                                    if (biomeName != null) params |= 0x100000;
                                    if (topHeightDifferent) params |= 0x1000000;

                                    // Mark new palette entries
                                    if (!isGrass && !blockPalette.containsKey(blockName)) params |= 0x200000;
                                    if (biomeName != null && !biomePalette.containsKey(biomeName)) params |= 0x400000;

                                    dos.writeInt(params);

                                    // BlockState data
                                    if (!isGrass) {
                                        if (blockPalette.containsKey(blockName)) {
                                            dos.writeInt(blockPalette.get(blockName));
                                        } else {
                                            writeBlockStateNbt(blockName, dos);
                                            blockPalette.put(blockName, blockPalette.size());
                                        }
                                    }

                                    // TopHeight
                                    if (topHeightDifferent) {
                                        dos.writeByte(topHeight & 0xFF);
                                    }

                                    // Overlay data
                                    if (hasOverlays) {
                                        dos.writeByte(overlays.size());
                                        for (OverlayData overlay : overlays) {
                                            serializeOverlay(overlay, dos, blockPalette);
                                        }
                                    }

                                    // Biome data
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

                            // Tile footer
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

    /**
     * 将高度编码到 params 参数中
     *
     * @param height 高度值
     * @return 编码后的 params 值
     */
    private static int encodeHeightToParams(int height) {
        return (height & 0xFF) << 12 | ((height >> 8) & 0xF) << 25;
    }

    /**
     * 将方块状态 NBT 写入到输出流
     *
     * @param blockName 方块名称
     * @param dos 数据输出流
     * @throws IOException 如果写入失败
     */
    private static void writeBlockStateNbt(String blockName, DataOutputStream dos) throws IOException {
        ByteArrayOutputStream nbtBaos = new ByteArrayOutputStream();
        try (DataOutputStream nbtDos = new DataOutputStream(nbtBaos)) {
            nbtDos.writeByte(10);  // TAG_Compound
            nbtDos.writeShort(0);  // empty name
            nbtDos.writeByte(8);   // TAG_String
            nbtDos.writeUTF("Name");
            nbtDos.writeUTF(blockName);
            nbtDos.writeByte(0);   // TAG_End
        }
        dos.write(nbtBaos.toByteArray());
    }

    /**
     * 序列化 overlay 数据到输出流
     *
     * @param overlay Overlay 数据
     * @param dos 数据输出流
     * @param blockPalette 方块调色板
     * @throws IOException 如果写入失败
     */
    private static void serializeOverlay(OverlayData overlay, DataOutputStream dos,
                                          Map<String, Integer> blockPalette) throws IOException {
        boolean isWater = BlockPropertyResolver.isWater(overlay.blockName);
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

    // ========== 数据结构 ==========

    /**
     * 添加 overlay 到列表（实现 Xaero 的累加逻辑）
     *
     * 参考 Xaero OverlayBuilder.build():
     * - 相同方块类型：increaseOpacity(lightBlock)
     * - 不同方块类型：创建新 overlay 层
     *
     * 重要修复：对于 lightBlock=0 的透明方块（海草、海带等），
     * 设置最小 opacity=1，确保颜色能够正确显示。
     * Xaero 客户端从纹理获取颜色时不完全依赖 opacity，
     * 但服务端生成的数据需要正确的 opacity 才能渲染。
     *
     * @param overlayList overlay 列表
     * @param blockName 方块名称
     * @param y Y 坐标
     * @param opacityToAdd 要添加的 opacity 值（lightBlock）
     * @param light 光照值
     */
    private static void addOverlay(List<OverlayData> overlayList, String blockName, int y, int opacityToAdd, int light) {
        // 限制单个添加值最大为 15
        if (opacityToAdd > 15) {
            opacityToAdd = 15;
        }

        // 关键修复：透明植物类方块（海草、海带等）的 lightBlock=0，
        // 导致 opacity=0，颜色无法显示。设置最小 opacity=1。
        // 这些方块是 TransparentBlock 类，有颜色但 lightBlock=0。
        if (opacityToAdd == 0 && !BlockPropertyResolver.isWater(blockName)) {
            // 检查是否是水生植物或透明植物
            String blockId = blockName.toLowerCase();
            if (blockId.contains("seagrass") || blockId.contains("kelp") ||
                BlockPropertyResolver.isTransparent(blockName)) {
                opacityToAdd = 1;  // 最小 opacity，确保颜色可见
            }
        }

        // 检查最后一个 overlay 是否是相同方块类型
        OverlayData lastOverlay = overlayList.isEmpty() ? null : overlayList.get(overlayList.size() - 1);
        if (lastOverlay != null && lastOverlay.blockName.equals(blockName)) {
            // 相同方块类型：累加 opacity（参考 Overlay.increaseOpacity）
            lastOverlay.opacity = Math.min(15, lastOverlay.opacity + opacityToAdd);
        } else {
            // 不同方块类型：创建新 overlay 层
            overlayList.add(new OverlayData(blockName, y, opacityToAdd, light));
        }
    }

    /**
     * Overlay 数据结构
     *
     * <p>存储透明方块覆盖层的信息</p>
     */
    static class OverlayData {
        /**
         * 方块名称
         */
        final String blockName;

        /**
         * Y 坐标
         */
        final int y;

        /**
         * 不透明度（可修改，用于累加）
         */
        int opacity;

        /**
         * 光照值
         */
        final int light;

        /**
         * 构造 Overlay 数据
         *
         * @param blockName 方块名称
         * @param y Y 坐标
         * @param opacity 不透明度
         * @param light 光照值
         */
        OverlayData(String blockName, int y, int opacity, int light) {
            this.blockName = blockName;
            this.y = y;
            this.opacity = opacity;
            this.light = light;
        }
    }

    /**
     * 区域地图数据结构
     *
     * <p>存储解析后的所有区域数据</p>
     */
    static class MapRegionData {
        /**
         * 方块名称数组 (512x512)
         */
        final String[][] blockNames;

        /**
         * 最高方块 Y 坐标数组 (512x512)
         */
        final int[][] topBlockY;

        /**
         * 生物群系名称数组 (512x512)
         */
        final String[][] biomeNames;

        /**
         * 高度图数组 (512x512)
         */
        final int[][] heightMap;

        /**
         * 光照图数组 (512x512)
         */
        final byte[][] lightMap;

        /**
         * 数据存在标记数组 (512x512)
         */
        final boolean[][] hasData;

        /**
         * 区块存在标记数组 (32x32)
         */
        final boolean[][] chunkExists;

        /**
         * Overlay 数据稀疏存储（Map替代二维数组，节省内存）
         *
         * <p>key: pixelIndex = x * REGION_SIZE_BLOCKS + z</p>
         * <p>value: 该像素的overlay列表</p>
         *
         * <p>大部分像素没有overlay，使用HashMap可节省约5.5MB/区域的内存开销</p>
         */
        final Map<Integer, List<OverlayData>> overlays;

        /**
         * 世界最低建筑高度
         */
        final int minBuildHeight;

        /**
         * 光照模式（用于调试/统计）
         */
        final LightMode lightMode;

        /**
         * 构造区域数据对象
         *
         * @param minBuildHeight 世界最低建筑高度
         * @param lightMode 光照模式
         */
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
            chunkExists = new boolean[CHUNKS_PER_REGION][CHUNKS_PER_REGION];  // 32x32 区块存在性追踪
            overlays = new HashMap<>();  // 稀疏存储，预设初始容量减少扩容开销
        }
    }
}