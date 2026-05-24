package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk数据解析器
 *
 * <p>解析完整的chunk NBT数据，包括:</p>
 * <ul>
 *   <li>高度图数据（用于地表模式扫描）</li>
 *   <li>区块状态验证（只处理已生成地形的区块）</li>
 *   <li>所有Section的方块和生物群系数据</li>
 *   <li>光照数据的查询和计算</li>
 * </ul>
 *
 * <p>支持1.18+格式（flat root）和旧格式（nested under "Level"）</p>
 *
 * @see ChunkSectionParser 用于解析单个Section的数据
 * @see ChunkInfo Chunk数据信息记录
 * @see LightStats 光照统计信息记录
 */
public class ChunkDataParser {

    /**
     * 可接受的区块状态集合
     *
     * <p>参考 Xaero WorldDataReader: 接受 >= FEATURES 状态的区块</p>
     *
     * <p>ChunkStatus 顺序:</p>
     * <pre>empty -> structure_starts -> structure_references -> biomes -> noise -> surface -> features -> light -> spawn -> heightmaps -> full</pre>
     *
     * <p>Xaero 在 WorldDataReader.java 第333-340行:</p>
     * <ul>
     *   <li>chunkStatusIndex < BIOMES.getIndex() → return false（跳过）</li>
     *   <li>handleChunkBiomes() 处理生物群系数据</li>
     *   <li>chunkStatusIndex < FEATURES.getIndex() → return false（跳过）</li>
     * </ul>
     *
     * <p>所以 Xaero 接受 >= FEATURES 的状态，包括:</p>
     * <ul>features, light, spawn, heightmaps, full</ul>
     */
    private static final Set<String> ACCEPTABLE_STATUSES = Set.of(
        "minecraft:features",
        "minecraft:light",
        "minecraft:spawn",
        "minecraft:heightmaps",
        "minecraft:full",
        // 不带命名空间的简写
        "features",
        "light",
        "spawn",
        "heightmaps",
        "full"
    );

    /**
     * 判断区块状态是否应该跳过
     *
     * <p>参考 Xaero WorldDataReader: 接受 >= FEATURES 状态的区块</p>
     *
     * @param status 区块状态字符串
     * @return true 表示跳过该区块，false 表示保留该区块
     */
    private static boolean shouldSkipChunk(String status) {
        if (status == null || status.isEmpty()) {
            return true;  // 无状态的区块跳过
        }

        // 处理带命名空间和不带命名空间的状态
        String normalizedStatus = status.contains(":") ? status : "minecraft:" + status;

        // 接受 >= FEATURES 状态的区块
        return !ACCEPTABLE_STATUSES.contains(normalizedStatus);
    }

    /**
     * Chunk数据信息记录
     *
     * <p>存储解析后的完整Chunk数据</p>
     *
     * @param chunkX Chunk的局部坐标 (0-31)
     * @param chunkZ Chunk的局部坐标 (0-31)
     * @param yPos Chunk底部Y坐标参数（yPos * 16 = chunkBottomY）
     * @param chunkBottomY Chunk底部世界Y坐标
     * @param status Chunk状态字符串（如 "minecraft:full"）
     * @param heightmap 高度图数组（16x16，存储世界绝对Y坐标）
     * @param sections 所有Section数据列表（按Y坐标从高到低排序）
     */
    public record ChunkInfo(
        int chunkX,                 // Chunk的局部坐标 (0-31)
        int chunkZ,
        int yPos,                   // Chunk底部Y坐标 (yPos * 16)
        int chunkBottomY,           // Chunk底部世界Y坐标
        String status,              // Chunk状态 ("minecraft:full", ...)
        int[][] heightmap,          // 高度图 (16x16, 世界绝对Y坐标)
        List<ChunkSectionParser.SectionData> sections
    ) {}

    /**
     * 解析chunk NBT数据
     *
     * <p>支持1.18+格式（flat root）和旧格式（nested under "Level"）</p>
     *
     * <p>处理流程:</p>
     * <ol>
     *   <li>检查区块状态，跳过未生成地形的区块</li>
     *   <li>解析高度图数据</li>
     *   <li>解析所有Section数据并按Y坐标排序</li>
     * </ol>
     *
     * @param localX chunk本地X坐标 (0-31)
     * @param localZ chunk本地Z坐标 (0-31)
     * @param chunkNbt chunk NBT数据
     * @param worldHeightRange 维度高度范围（worldTopY - minBuildHeight）
     * @return ChunkInfo对象，如果区块无效则返回null
     */
    public static ChunkInfo parseChunk(int localX, int localZ, Tag.Compound chunkNbt, int worldHeightRange) {
        // 检查chunk状态 - 只处理已生成地形的区块
        // 区块生成顺序：empty -> structure_starts -> structure_references -> biomes -> noise -> surface -> ...
        // 只有 surface 及之后的状态才有实际地形数据
        String status = chunkNbt.getString("Status");

        // 跳过未生成地形的早期状态
        if (shouldSkipChunk(status)) {
            return null;
        }

        // 1.18+格式检查：sections在根层级
        Tag.Compound rootTag;
        if (chunkNbt.contains("sections", Tag.TAG_LIST)) {
            rootTag = chunkNbt;
        } else if (chunkNbt.contains("Level", Tag.TAG_COMPOUND)) {
            // 旧格式：数据嵌套在Level下
            rootTag = chunkNbt.getCompound("Level");
        } else {
            return null;  // 无法识别的格式
        }

        // 解析yPos (1.18+)
        int yPos = chunkNbt.getInt("yPos");
        int chunkBottomY = yPos * 16;

        // 解析高度图（传入维度高度范围）
        int[][] heightmap = parseHeightmap(rootTag, chunkBottomY, worldHeightRange);

        // 解析sections
        List<ChunkSectionParser.SectionData> sections = new ArrayList<>();
        if (rootTag.contains("sections", Tag.TAG_LIST)) {
            Tag.ListTag sectionsList = rootTag.getList("sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < sectionsList.items().size(); i++) {
                Tag.Compound sectionTag = (Tag.Compound) sectionsList.items().get(i);
                ChunkSectionParser.SectionData section = ChunkSectionParser.parseSection(sectionTag);
                sections.add(section);
            }
        }

        // 按Y坐标从高到低排序（用于从上到下扫描）
        sections.sort((a, b) -> Integer.compare(b.sectionY(), a.sectionY()));

        return new ChunkInfo(localX, localZ, yPos, chunkBottomY, status, heightmap, sections);
    }

    
    /**
     * 解析高度图数据
     *
     * <p>支持多种格式:</p>
     * <ul>
     *   <li>1.18+ WORLD_SURFACE (LongArray)</li>
     *   <li>MOTION_BLOCKING_NO_LEAVES（包括水方块）</li>
     *   <li>旧版 HeightMap (IntArray)</li>
     * </ul>
     *
     * <p>优先使用 MOTION_BLOCKING_NO_LEAVES，能正确检测上方的水方块</p>
     *
     * @param rootTag chunk根NBT数据
     * @param chunkBottomY chunk最低Y坐标
     * @param worldHeightRange 维度高度范围（用于计算bitsPerHeight）
     * @return 16x16的高度图数组（存储世界绝对Y坐标）
     */
    private static int[][] parseHeightmap(Tag.Compound rootTag, int chunkBottomY, int worldHeightRange) {
        int[][] heightmap = new int[16][16];

        // 尝试新格式 Heightmaps
        if (rootTag.contains("Heightmaps", Tag.TAG_COMPOUND)) {
            Tag.Compound heightmaps = rootTag.getCompound("Heightmaps");

            // 优先使用 MOTION_BLOCKING_NO_LEAVES（包括水方块）
            // 这样能正确检测上方的水方块
            if (heightmaps.contains("MOTION_BLOCKING_NO_LEAVES", Tag.TAG_LONG_ARRAY)) {
                long[] data = heightmaps.getLongArray("MOTION_BLOCKING_NO_LEAVES");
                int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
                if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                    decodeHeightmapLongArray(data, bitsPerHeight, chunkBottomY, heightmap);
                    return heightmap;
                }
            }

            // 备用 WORLD_SURFACE（不包括水方块）
            if (heightmaps.contains("WORLD_SURFACE", Tag.TAG_LONG_ARRAY)) {
                long[] data = heightmaps.getLongArray("WORLD_SURFACE");
                int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
                if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                    decodeHeightmapLongArray(data, bitsPerHeight, chunkBottomY, heightmap);
                    return heightmap;
                }
            }
        }

        // 旧格式 HeightMap (IntArray)
        if (rootTag.contains("HeightMap", Tag.TAG_INT_ARRAY)) {
            int[] data = rootTag.getIntArray("HeightMap");
            if (data.length == 256) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        heightmap[x][z] = data[z * 16 + x];  // 直接是世界绝对Y坐标
                    }
                }
                return heightmap;
            }
        }

        // 无高度图数据，返回默认值
        return heightmap;
    }

    /**
     * 计算高度图的bitsPerEntry
     *
     * <p>根据Wiki公式反推:</p>
     * <ul>
     *   <li>h = 最高高度 - 最低建筑高度（维度高度范围）</li>
     *   <li>b = ceil(log2(h))</li>
     *   <li>u = floor(64/b)</li>
     *   <li>l = ceil(256/u)</li>
     * </ul>
     *
     * @param longArrayLength LongArray的长度
     * @param worldHeightRange 维度高度范围
     * @return 每个高度值的位数b
     */
    private static int calculateBitsPerHeight(int longArrayLength, int worldHeightRange) {
        // 优先使用维度高度范围计算（更准确）
        if (worldHeightRange > 0) {
            // b = ceil(log2(h))
            return 32 - Integer.numberOfLeadingZeros(worldHeightRange - 1);
        }

        // 备用：从数组长度反推
        // l = ceil(256/u) => u ≈ ceil(256/l)
        // b = floor(64/u)
        if (longArrayLength <= 0) return 0;
        int u = (256 + longArrayLength - 1) / longArrayLength; // ceil(256/l)
        return 64 / u;
    }

    /**
     * 解码LongArray高度图（Wiki规范）
     *
     * <p>存储的是相对于维度最低建筑高度的偏移量</p>
     *
     * <p>Wiki公式:</p>
     * <ul>
     *   <li>编码序号 i = x + 16*z</li>
     *   <li>值 = (data[i/u] >> ((i%u)*b)) & ((1L<<b)-1L) + low</li>
     * </ul>
     *
     * @param data long数组
     * @param bitsPerHeight 每个高度值的位数b
     * @param chunkBottomY chunk的最低Y坐标（作为基线）
     * @param heightmap 输出高度图数组
     */
    private static void decodeHeightmapLongArray(long[] data, int bitsPerHeight, int chunkBottomY, int[][] heightmap) {
        if (data == null || data.length == 0 || bitsPerHeight <= 0) {
            return;
        }

        // u = floor(64/b)
        int u = 64 / bitsPerHeight;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // Wiki: i = x + 16*z
                int i = x + 16 * z;

                // Wiki公式：值 = (data[i/u] >> ((i%u)*b)) & ((1L<<b)-1L)
                int longIndex = i / u;
                int bitOffset = (i % u) * bitsPerHeight;

                if (longIndex >= data.length) {
                    heightmap[x][z] = chunkBottomY;
                    continue;
                }

                long rawValue = (data[longIndex] >>> bitOffset) & ((1L << bitsPerHeight) - 1L);

                // 世界绝对Y = chunkBottomY + 偏移量（chunkBottomY作为基线）
                heightmap[x][z] = chunkBottomY + (int) rawValue;
            }
        }
    }

    /**
     * 从chunk sections中获取指定位置的方块状态（完整信息）
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @return 方块状态对象
     */
    public static ChunkSectionParser.BlockState getBlockStateAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBlockStateAt(section, x, localY, z);
            }
        }

        return new ChunkSectionParser.BlockState("minecraft:air", Map.of());
    }

    /**
     * 从chunk sections中获取指定位置的生物群系（默认启用边界平滑）
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @return 生物群系名称字符串
     */
    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z) {
        return getBiomeAt(chunk, x, worldY, z, true);
    }

    /**
     * 从chunk sections中获取指定位置的生物群系（可选择是否启用边界平滑）
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @param smoothBoundary 是否启用voxel边界平滑
     * @return 生物群系名称字符串
     */
    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z, boolean smoothBoundary) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBiomeAt(section, x, localY, z, smoothBoundary);
            }
        }

        return null;
    }

    /**
     * 获取chunk中指定位置的方块光照
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @return 方块光照值 (0-15)
     */
    public static byte getBlockLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBlockLight(section, x, localY, z);
            }
        }

        return 0;
    }

    /**
     * 获取chunk中指定位置的天空光照
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @return 天空光照值 (0-15)
     */
    public static byte getSkyLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getSkyLight(section, x, localY, z);
            }
        }

        return 0;
    }

    /**
     * 判断指定位置是否有天空访问（用于光照计算）
     *
     * <p>基于高度图判断：如果worldY >= heightmap[x][z]，则有天空访问</p>
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @return 如果位置高于高度图则返回true
     */
    public static boolean hasSkyAccess(ChunkInfo chunk, int x, int worldY, int z) {
        int surfaceY = chunk.heightmap()[x][z];
        return worldY >= surfaceY;
    }

    /**
     * 获取有效光照值（支持光照模式）
     *
     * <p>根据光照模式和维度属性计算最终的有效光照</p>
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @param lightMode 光照模式（SURFACE 或 CAVE）
     * @param hasOverlay 是否有覆盖层（水、玻璃等透明方块）
     * @param worldHasSkylight 维度是否有天空光照
     * @return 有效光照值 (0-15)
     */
    public static byte getEffectiveLight(ChunkInfo chunk, int x, int worldY, int z,
                                          LightMode lightMode, boolean hasOverlay,
                                          boolean worldHasSkylight) {
        byte blockLight = getBlockLightAt(chunk, x, worldY, z);
        byte skyLight = getSkyLightAt(chunk, x, worldY, z);
        boolean hasSkyAccess = hasSkyAccess(chunk, x, worldY, z);

        return lightMode.calculateEffectiveLight(blockLight, skyLight, hasSkyAccess, hasOverlay, false, worldHasSkylight);
    }

    /**
     * 获取洞穴模式有效光照
     *
     * <p>参考 Xaero WorldDataReader 的洞穴模式光照逻辑:</p>
     * <ul>
     *   <li>BlockLight >= 15 时直接返回（发光方块）</li>
     *   <li>有天空访问且无 overlay 时返回 15（直接日照）</li>
     *   <li>无 overlay 时取 max(BlockLight, SkyLight)</li>
     *   <li>有 overlay 时使用 BlockLight（水下场景）</li>
     * </ul>
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param worldY 世界Y坐标
     * @param z 局部Z坐标 (0-15)
     * @param hasOverlay 是否有覆盖层
     * @return 有效光照值 (0-15)
     */
    public static byte getEffectiveLightCave(ChunkInfo chunk, int x, int worldY, int z,
                                              boolean hasOverlay) {
        byte blockLight = getBlockLightAt(chunk, x, worldY, z);

        // BlockLight >= 15 时直接返回（发光方块）
        if (blockLight >= 15) {
            return blockLight;
        }

        boolean hasSkyAccess = hasSkyAccess(chunk, x, worldY, z);

        // 有天空访问且无 overlay 时返回 15
        if (hasSkyAccess && !hasOverlay) {
            return 15;
        }

        // 无 overlay 时取 max(BlockLight, SkyLight)
        if (!hasOverlay) {
            byte skyLight = getSkyLightAt(chunk, x, worldY, z);
            return (byte) Math.max(blockLight, skyLight);
        }

        // 有 overlay 时使用 BlockLight
        return blockLight;
    }

    /**
     * 获取高度图值（带+3容差）
     *
     * <p>+3容差用于覆盖草方块上的草/花/雪层等装饰方块</p>
     *
     * @param chunk Chunk数据
     * @param x 局部X坐标 (0-15)
     * @param z 局部Z坐标 (0-15)
     * @param worldTopY 世界顶部Y坐标限制
     * @return 扫描起始高度（不超过世界顶部）
     */
    public static int getHeightmapStartY(ChunkInfo chunk, int x, int z, int worldTopY) {
        int heightMapValue = chunk.heightmap()[x][z];
        // +3容差（覆盖草方块上的草/花/雪层）
        int startY = heightMapValue + 3;
        // 不能超过世界顶部
        return Math.min(startY, worldTopY - 1);
    }
}