package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk Section数据解析器
 * 解析单个section的方块和生物群系数据
 */
public class ChunkSectionParser {

    /**
     * 方块状态数据（包含名称和属性）
     */
    public record BlockState(
        String name,                        // 方块名称 "minecraft:stone"
        Map<String, String> properties      // 属性 {snowy: "false", facing: "north"}
    ) {
        /**
         * 获取完整方块ID（带属性）
         * 格式: "minecraft:grass_block[snowy=false]"
         */
        public String getFullName() {
            if (properties.isEmpty()) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name);
            sb.append("[");
            boolean first = true;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (!first) sb.append(",");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }

        /**
         * 判断是否为空气
         */
        public boolean isAir() {
            return name.equals("minecraft:air") ||
                   name.equals("minecraft:cave_air") ||
                   name.equals("minecraft:void_air");
        }

        /**
         * 判断是否为水
         */
        public boolean isWater() {
            return name.equals("minecraft:water") || name.equals("minecraft:flowing_water");
        }

        /**
         * 判断是否为熔岩
         */
        public boolean isLava() {
            return name.equals("minecraft:lava") || name.equals("minecraft:flowing_lava");
        }

        /**
         * 判断是否为流体方块（水或熔岩）
         */
        public boolean isFluid() {
            return isWater() || isLava();
        }

        /**
         * 判断是否为草方块
         */
        public boolean isGrassBlock() {
            return name.equals("minecraft:grass_block");
        }

        /**
         * 判断是否为透明方块（水、熔岩、玻璃等）
         * 用于决定是否作为 overlay 处理
         */
        public boolean isTransparentOverlay() {
            return isWater() || name.equals("minecraft:glass") ||
                   name.endsWith("_stained_glass") || name.equals("minecraft:glass_pane") ||
                   name.endsWith("_stained_glass_pane") || name.equals("minecraft:ice") ||
                   name.endsWith("_ice") || name.equals("minecraft:tinted_glass");
        }

        /**
         * 判断是否为隐形方块（扫描时跳过）
         * 包括: torch, short_grass, flowers, glass 等
         */
        public boolean isInvisible() {
            // Torch
            if (name.equals("minecraft:torch") || name.endsWith("_torch")) return true;
            // Short grass
            if (name.equals("minecraft:short_grass") || name.equals("minecraft:grass")) return true;
            // Glass (handled as transparent overlay, not invisible in scan)
            // Flowers (default flowers config off)
            if (isFlower()) return true;
            // Double plant non-flowers (tall_grass, large_fern)
            if (name.equals("minecraft:tall_grass") || name.equals("minecraft:large_fern")) return true;
            return false;
        }

        /**
         * 判断是否为花朵
         */
        public boolean isFlower() {
            return name.equals("minecraft:dandelion") || name.equals("minecraft:poppy") ||
                   name.equals("minecraft:blue_orchid") || name.equals("minecraft:allium") ||
                   name.equals("minecraft:red_tulip") || name.equals("minecraft:orange_tulip") ||
                   name.equals("minecraft:white_tulip") || name.equals("minecraft:pink_tulip") ||
                   name.equals("minecraft:oxeye_daisy") || name.equals("minecraft:cornflower") ||
                   name.equals("minecraft:lily_of_the_valley") || name.equals("minecraft:wither_rose") ||
                   name.equals("minecraft:sunflower") || name.equals("minecraft:rose_bush") ||
                   name.equals("minecraft:peony") || name.equals("minecraft:azure_bluet") ||
                   name.endsWith("_tulip") || name.contains("orchid") ||
                   name.equals("minecraft:pitcher_plant") || name.endsWith("_pitcher_crop");
        }

        /**
         * 判断是否为含水方块（waterlogged=true）
         */
        public boolean isWaterlogged() {
            return properties.containsKey("waterlogged") &&
                   "true".equals(properties.get("waterlogged"));
        }

        /**
         * 判断是否可以含水（方块类型支持 waterlogged 属性）
         */
        public boolean canBeWaterlogged() {
            // 栅栏门、楼梯、台阶、墙、门、陷阱门、灯笼、海泡菜、海带等
            return name.contains("fence_gate") || name.contains("stairs") ||
                   name.contains("slab") || name.contains("wall") ||
                   name.contains("door") && !name.contains("iron_door") ||
                   name.contains("trapdoor") || name.contains("lantern") ||
                   name.contains("soul_lantern") || name.contains("chain") ||
                   name.equals("minecraft:sea_pickle") || name.equals("minecraft:kelp") ||
                   name.equals("minecraft:kelp_plant") || name.contains("coral") ||
                   name.contains("coral_block") || name.contains("coral_fan") ||
                   name.equals("minecraft:copper_grate") || name.contains("grate") ||
                   name.contains("conduit") || name.equals("minecraft:scaffolding") ||
                   name.equals("minecraft:light") || name.contains("sign") ||
                   name.contains("banner") || name.contains("bed");
        }

        /**
         * 判断方块是否为表面方块且上方有水（含水方块）
         * 含水方块：方块本身有颜色，但上方应该渲染水 overlay
         */
        public boolean isWaterloggedSurface() {
            return isWaterlogged() && !isWater() && !isAir();
        }
    }

    /**
     * Section数据
     */
    public record SectionData(
        int sectionY,                       // Section的世界Y坐标 (sectionY * 16 = section基线Y)
        List<BlockState> blockPalette,      // 方块状态列表（包含属性）
        List<String> blockNames,            // 方块名称列表（仅名称，用于快速查询）
        long[] blockData,                   // 位压缩的方块索引数据
        int blockBitsPerEntry,              // 每个方块索引的位数
        List<String> biomePalette,          // 生物群系名称列表 ["minecraft:plains", ...]
        long[] biomeData,                   // 位压缩的生物群系索引数据
        int biomeBitsPerEntry,              // 每个生物群系索引的位数
        byte[] blockLight,                  // 方块光照数组 (2048字节)
        byte[] skyLight                     // 天空光照数组 (2048字节)
    ) {

        /**
         * 获取方块名称列表（仅名称）
         */
        public List<String> getBlockNames() {
            return blockNames;
        }
    }

    /**
     * 从NBT Compound解析Section数据
     */
    public static SectionData parseSection(Tag.Compound sectionTag) {
        int sectionY = sectionTag.getByte("Y");

        // 解析block_states
        List<BlockState> blockPalette = new ArrayList<>();
        List<String> blockNames = new ArrayList<>();
        long[] blockData = null;
        int blockBitsPerEntry = 0;

        if (sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
            Tag.Compound blockStates = sectionTag.getCompound("block_states");

            // 解析palette（包含完整属性）
            if (blockStates.contains("palette", Tag.TAG_LIST)) {
                Tag.ListTag paletteList = blockStates.getList("palette", Tag.TAG_COMPOUND);
                for (int i = 0; i < paletteList.items().size(); i++) {
                    Tag.Compound stateTag = (Tag.Compound) paletteList.items().get(i);
                    BlockState blockState = parseBlockState(stateTag);
                    blockPalette.add(blockState);
                    blockNames.add(blockState.name());
                }
            }

            // 解析data
            if (blockStates.contains("data", Tag.TAG_LONG_ARRAY)) {
                blockData = blockStates.getLongArray("data");
            }

            // 计算bitsPerEntry
            blockBitsPerEntry = calculateBitsPerEntry(blockPalette.size(), blockData);
        }

        // 解析biomes
        List<String> biomePalette = new ArrayList<>();
        long[] biomeData = null;
        int biomeBitsPerEntry = 0;

        if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
            Tag.Compound biomes = sectionTag.getCompound("biomes");

            // 解析palette (biome palette元素是String类型)
            if (biomes.contains("palette", Tag.TAG_LIST)) {
                Tag.ListTag paletteList = biomes.getList("palette", Tag.TAG_STRING);
                for (int i = 0; i < paletteList.items().size(); i++) {
                    Tag.StringTag biomeTag = (Tag.StringTag) paletteList.items().get(i);
                    biomePalette.add(biomeTag.value());
                }
            }

            // 解析data
            if (biomes.contains("data", Tag.TAG_LONG_ARRAY)) {
                biomeData = biomes.getLongArray("data");
            }

            // 计算bitsPerEntry - biome使用64个voxel（4x4x4），不是4096
            biomeBitsPerEntry = calculateBiomeBitsPerEntry(biomePalette.size(), biomeData);
        }

        // 解析光照数据
        byte[] blockLight = sectionTag.getByteArray("BlockLight");
        byte[] skyLight = sectionTag.getByteArray("SkyLight");

        return new SectionData(
            sectionY, blockPalette, blockNames, blockData, blockBitsPerEntry,
            biomePalette, biomeData, biomeBitsPerEntry,
            blockLight.length == 2048 ? blockLight : null,
            skyLight.length == 2048 ? skyLight : null
        );
    }

    /**
     * 解析单个方块状态的NBT
     * 格式: {Name: "minecraft:grass_block", Properties: {snowy: "false"}}
     */
    private static BlockState parseBlockState(Tag.Compound stateTag) {
        String name = stateTag.getString("Name");
        Map<String, String> properties = new LinkedHashMap<>();

        if (stateTag.contains("Properties", Tag.TAG_COMPOUND)) {
            Tag.Compound propsTag = stateTag.getCompound("Properties");
            for (Map.Entry<String, Tag> entry : propsTag.children().entrySet()) {
                Tag propTag = entry.getValue();
                if (propTag instanceof Tag.StringTag str) {
                    properties.put(entry.getKey(), str.value());
                }
            }
        }

        return new BlockState(name, properties);
    }

    /**
     * 计算bitsPerEntry（方块状态）
     * 根据Wiki规范：
     * - 当c ≤ 16时，b = 4
     * - 当c > 16时，b = ceil(log2(c))
     */
    private static int calculateBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;  // 单方块section，无需data数组
        }

        // Wiki规范：c ≤ 16 时 b = 4，否则 b = ceil(log2(c))
        if (paletteSize <= 16) {
            return 4;
        }
        // ceil(log2(c)) = 32 - numberOfLeadingZeros(c - 1)
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /**
     * 计算bitsPerEntry（生物群系）
     * 根据Wiki规范：b = ceil(log2(c))
     */
    private static int calculateBiomeBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;  // 单biome section，无需data数组
        }

        // Wiki规范：b = ceil(log2(c))
        // ceil(log2(c)) = 32 - numberOfLeadingZeros(c - 1)
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /**
     * 从Section获取指定位置的方块状态（完整信息）
     * @param section Section数据
     * @param x 局部X (0-15)
     * @param y 局部Y (0-15)
     * @param z 局部Z (0-15)
     */
    public static BlockState getBlockStateAt(SectionData section, int x, int y, int z) {
        if (section.blockPalette.isEmpty()) {
            return new BlockState("minecraft:air", Map.of());
        }

        // 单方块palette
        if (section.blockPalette.size() == 1) {
            return section.blockPalette.get(0);
        }

        // 无数据
        if (section.blockData == null || section.blockBitsPerEntry == 0) {
            return new BlockState("minecraft:air", Map.of());
        }

        // 计算索引 (YZX顺序)
        int blockIndex = (y << 8) | (z << 4) | x;

        // 从位数组读取palette索引
        int paletteIndex = readBitsFromArray(section.blockData, blockIndex, section.blockBitsPerEntry);

        if (paletteIndex < 0 || paletteIndex >= section.blockPalette.size()) {
            return new BlockState("minecraft:air", Map.of());
        }

        return section.blockPalette.get(paletteIndex);
    }

    /**
     * 从Section获取指定位置的方块名称（仅名称）
     * @param section Section数据
     * @param x 局部X (0-15)
     * @param y 局部Y (0-15)
     * @param z 局部Z (0-15)
     */
    public static String getBlockAt(SectionData section, int x, int y, int z) {
        return getBlockStateAt(section, x, y, z).name();
    }

    /**
     * 从Section获取指定位置的生物群系名称
     * @param section Section数据
     * @param x 局部X (0-15)
     * @param y 局部Y (0-15)
     * @param z 局部Z (0-15)
     */
    public static String getBiomeAt(SectionData section, int x, int y, int z) {
        return getBiomeAt(section, x, y, z, false);
    }

    /**
     * 从Section获取指定位置的生物群系名称（支持边界平滑）
     *
     * biome 使用 4x4x4 voxel 格式，每个 voxel 覆盖 4x4 像素。
     * 为了实现类似 Xaero 的 biome blending 效果，在 voxel 边缘位置
     * 偏向相邻 voxel，使得 Xaero 客户端的十字形采样能获取不同的 biome。
     *
     * voxel 坐标系统 (以 voxel 为单位):
     * - voxel (0,0) 覆盖像素 (0-3, 0-3)
     * - voxel (1,0) 覆盖像素 (4-7, 0-3)
     * - voxel (0,1) 覆盖像素 (0-3, 4-7)
     *
     * 平滑策略:
     * - 每个像素的"名义 voxel"是其所在的 voxel
     * - 但像素 (relX, relZ) 在 voxel 内的位置决定了它的实际采样位置
     * - relX < 2 且 relZ < 2 (左上区域): 使用本 voxel 或左上方向 voxel
     * - relX >= 2 且 relZ < 2 (右上区域): 倾向右侧 voxel
     * - relX < 2 且 relZ >= 2 (左下区域): 倾向下方 voxel
     * - relX >= 2 且 relZ >= 2 (右下区域): 倾向右下方向 voxel
     *
     * 参考 Xaero BiomeColorCalculator: 十字形采样5个位置计算平均颜色
     *
     * @param section Section数据
     * @param x 局部X (0-15)
     * @param y 局部Y (0-15)
     * @param z 局部Z (0-15)
     * @param smoothBoundary 是否启用边界平滑
     */
    public static String getBiomeAt(SectionData section, int x, int y, int z, boolean smoothBoundary) {
        // 参考 Xaero WorldDataReader: 默认使用 THE_VOID biome
        // 当 biomePalette 为空时返回 THE_VOID（虚空区域的深紫色）
        if (section.biomePalette.isEmpty()) {
            return "minecraft:the_void";
        }

        // 单生物群系palette
        if (section.biomePalette.size() == 1) {
            return section.biomePalette.get(0);
        }

        // 无数据时返回 THE_VOID（默认值）
        if (section.biomeData == null || section.biomeBitsPerEntry == 0) {
            return "minecraft:the_void";
        }

        // 标准计算：voxelIndex = (y/4)*16 + (z/4)*4 + (x/4)
        int voxelY = y >> 2;
        int voxelZ = z >> 2;
        int voxelX = x >> 2;

        // 边界平滑：根据像素在 voxel 内的位置调整
        if (smoothBoundary) {
            // 计算像素在 voxel 内的相对位置 (0-3)
            int relX = x & 3;
            int relZ = z & 3;

            // 核心思想：让 Xaero 的十字形采样能获取不同的 biome
            // 十字形采样: (x-1,z), (x,z-1), (x,z), (x,z+1), (x+1,z)
            // 需要让采样位置落在不同的 voxel 内

            // 使用像素的实际位置作为"重心"，然后找到包含该位置的 voxel
            // 像素在 voxel 内的位置可以看作是相对于 voxel 左上角的偏移
            // 偏移 0,1 → 倾向本 voxel
            // 偏移 2,3 → 倾向相邻 voxel（因为接近 voxel 边界右侧/下侧）

            // 如果 relX >= 2，像素接近 voxel 右边界，使用右侧 voxel
            // 如果 relZ >= 2，像素接近 voxel 下边界，使用下方 voxel

            if (relX >= 2 && voxelX < 3) {
                voxelX++;
            }
            if (relZ >= 2 && voxelZ < 3) {
                voxelZ++;
            }
        }

        int voxelIndex = (voxelY << 4) | (voxelZ << 2) | voxelX;

        int paletteIndex = readBitsFromArray(section.biomeData, voxelIndex, section.biomeBitsPerEntry);

        if (paletteIndex < 0 || paletteIndex >= section.biomePalette.size()) {
            return null;
        }

        return section.biomePalette.get(paletteIndex);
    }

    /**
     * 从位数组读取指定索引的值（Wiki规范）
     *
     * Wiki公式：
     * - u = floor(64/b)，一个long能存储的元素数量
     * - getPalette(i) = (data[i/u] >>> ((i%u)*b)) & ((1L<<b)-1)
     *
     * 元素不会跨long存储，每个元素完全在一个long内
     *
     * @param data long数组
     * @param index 元素序号（对于方块是YZX编码的索引，对于biome是voxel索引）
     * @param bitsPerEntry 每个元素的位数b
     */
    public static int readBitsFromArray(long[] data, int index, int bitsPerEntry) {
        if (data == null || data.length == 0 || bitsPerEntry <= 0) {
            return 0;
        }

        // u = floor(64/b)，一个long能存储的元素数量
        int u = 64 / bitsPerEntry;

        // Wiki公式：data[i/u] >>> ((i%u)*b) & ((1L<<b)-1)
        int longIndex = index / u;
        int posInLong = index % u;
        int bitOffset = posInLong * bitsPerEntry;

        if (longIndex >= data.length) {
            return 0;
        }

        // 使用无符号右移，然后掩码取b位
        return (int) ((data[longIndex] >>> bitOffset) & ((1L << bitsPerEntry) - 1L));
    }

    /**
     * 获取方块光照值 (从nibble array)
     * @param section Section数据
     * @param x 局部X (0-15)
     * @param y 局部Y (0-15)
     * @param z 局部Z (0-15)
     */
    public static byte getBlockLight(SectionData section, int x, int y, int z) {
        return getLightValue(section.blockLight(), x, y, z);
    }

    /**
     * 获取天空光照值 (从nibble array)
     * @param section Section数据
     * @param x 局部X (0-15)
     * @param y 局部Y (0-15)
     * @param z 局部Z (0-15)
     */
    public static byte getSkyLight(SectionData section, int x, int y, int z) {
        return getLightValue(section.skyLight(), x, y, z);
    }

    /**
     * 获取光照值 (从nibble array) - Wiki规范
     * 亮度存储：每字节存储2个亮度值（4比特），2048字节存储4096个亮度
     * 写入顺序：YZX编码
     *
     * Wiki公式：getLight(x, y, z) = (data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF
     * 其中 yzx = toYZX(x, y, z) = (y << 8) | (z << 4) | x
     *
     * @param lightArray nibble数组 (2048字节，存储4096个4位值)
     * @param x 局部X (0-15)
     * @param y 局部Y (0-15)
     * @param z 局部Z (0-15)
     */
    public static byte getLightValue(byte[] lightArray, int x, int y, int z) {
        if (lightArray == null || lightArray.length != 2048) {
            return 0;
        }

        // YZX编码序号
        int yzx = (y << 8) | (z << 4) | x;

        // Wiki公式：(data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF
        // yzx >> 1 找到字节索引（每字节存2个亮度）
        // yzx & 1 判断是该字节的第几个亮度（0=低4位，1=高4位）
        // 4 * (yzx & 1) 计算位偏移（0或4）
        return (byte) ((lightArray[yzx >> 1] >> (4 * (yzx & 1))) & 0xF);
    }

    /**
     * 解析完整的光照数据 (16x16x16 section)
     * @param section Section数据
     * @return 光照数据数组，索引格式 (y<<8)|(z<<4)|x
     */
    public static LightData parseLightData(SectionData section) {
        byte[] blockLight = new byte[4096];
        byte[] skyLight = new byte[4096];

        if (section.blockLight() != null && section.blockLight().length == 2048) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        blockLight[idx] = getLightValue(section.blockLight(), x, y, z);
                    }
                }
            }
        }

        if (section.skyLight() != null && section.skyLight().length == 2048) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        skyLight[idx] = getLightValue(section.skyLight(), x, y, z);
                    }
                }
            }
        }

        return new LightData(section.sectionY(), blockLight, skyLight);
    }

    /**
     * 光照数据结构
     */
    public record LightData(
        int sectionY,           // Section的世界Y坐标
        byte[] blockLight,      // 方块光照 (4096字节，每个位置0-15)
        byte[] skyLight         // 天空光照 (4096字节，每个位置0-15)
    ) {
        /**
         * 检查是否有光照数据
         */
        public boolean hasLightData() {
            return blockLight != null || skyLight != null;
        }

        /**
         * 获取指定位置的光照值
         * @param x 局部X (0-15)
         * @param localY 局部Y (0-15，相对于section底部)
         * @param z 局部Z (0-15)
         */
        public byte getBlockLightAt(int x, int localY, int z) {
            if (blockLight == null) return 0;
            int idx = (localY << 8) | (z << 4) | x;
            return idx < blockLight.length ? blockLight[idx] : 0;
        }

        public byte getSkyLightAt(int x, int localY, int z) {
            if (skyLight == null) return 0;
            int idx = (localY << 8) | (z << 4) | x;
            return idx < skyLight.length ? skyLight[idx] : 0;
        }

        /**
         * 计算有效光照值 (地表模式)
         * 只使用 BlockLight，忽略 SkyLight
         */
        public byte getEffectiveLightSurface(int x, int localY, int z) {
            return getBlockLightAt(x, localY, z);
        }

        /**
         * 计算有效光照值 (洞穴模式)
         * Xaero 在日光条件下使用 skyLight=15，但水下使用 blockLight
         * 参考 Xaero WorldDataReader:537-561
         *
         * @param x 局部X (0-15)
         * @param localY 局部Y (0-15)
         * @param z 局部Z (0-15)
         * @param hasSkyAccess 是否有天空访问（位置高于高度图）
         * @param hasOverlay 是否有覆盖层（水、玻璃等）
         */
        public byte getEffectiveLightCave(int x, int localY, int z,
                                          boolean hasSkyAccess, boolean hasOverlay) {
            byte blockLight = getBlockLightAt(x, localY, z);

            // 发光方块（火把等）BlockLight >= 15 时直接返回
            if (blockLight >= 15) {
                return blockLight;
            }

            // 有天空访问时 SkyLight = 15（直接日照）
            if (hasSkyAccess && !hasOverlay) {
                return 15;
            }

            // 无 overlay 时取 max(blockLight, skyLight)
            if (!hasOverlay) {
                byte skyLight = getSkyLightAt(x, localY, z);
                return (byte) Math.max(blockLight, skyLight);
            }

            // 有 overlay（水下等）使用 BlockLight
            return blockLight;
        }

        /**
         * 计算有效光照值（通用方法）
         *
         * @param x 局部X (0-15)
         * @param localY 局部Y (0-15)
         * @param z 局部Z (0-15)
         * @param lightMode 光照模式
         * @param hasSkyAccess 是否有天空访问
         * @param hasOverlay 是否有覆盖层
         * @param worldHasSkylight 维度是否有天空光照
         */
        public byte getEffectiveLight(int x, int localY, int z,
                                       LightMode lightMode,
                                       boolean hasSkyAccess, boolean hasOverlay,
                                       boolean worldHasSkylight) {
            return lightMode.calculateEffectiveLight(
                getBlockLightAt(x, localY, z),
                getSkyLightAt(x, localY, z),
                hasSkyAccess, hasOverlay, false, worldHasSkylight
            );
        }
    }
}