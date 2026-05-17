package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;

import java.util.*;

/**
 * 方块判断器 - 基于字符串匹配的方块属性判断
 * 替代 Minecraft 的 BlockState 方法调用
 */
public class BlockClassifier {

    // 空气方块集合
    private static final Set<String> AIR_BLOCKS = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air"
    );

    // 流体方块集合
    private static final Set<String> WATER_BLOCKS = Set.of(
        "minecraft:water", "minecraft:flowing_water"
    );
    private static final Set<String> LAVA_BLOCKS = Set.of(
        "minecraft:lava", "minecraft:flowing_lava"
    );

    // 透明方块集合（作为 overlay）
    // 参考 Xaero MapWriter.blockStateHasTranslucentRenderType
    // 这些方块使用 translucent 渲染类型，应该作为 overlay 处理
    private static final Set<String> TRANSPARENT_BLOCKS = Set.of(
        "minecraft:glass", "minecraft:glass_pane",
        "minecraft:white_stained_glass", "minecraft:orange_stained_glass",
        "minecraft:magenta_stained_glass", "minecraft:light_blue_stained_glass",
        "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
        "minecraft:pink_stained_glass", "minecraft:gray_stained_glass",
        "minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass",
        "minecraft:purple_stained_glass", "minecraft:blue_stained_glass",
        "minecraft:brown_stained_glass", "minecraft:green_stained_glass",
        "minecraft:red_stained_glass", "minecraft:black_stained_glass",
        "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
        "minecraft:tinted_glass",
        // 水生植物 - 都是 translucent 渲染类型，作为 overlay 处理
        "minecraft:kelp", "minecraft:kelp_plant",
        "minecraft:seagrass", "minecraft:tall_seagrass"
    );

    // 隐形方块集合（扫描时无条件跳过）
    // 参考 Xaero MapWriter.isInvisible():
    // - torch, short_grass: 无条件跳过
    // - glass, glass_pane: 无条件跳过
    // - DoublePlantBlock 非花的（tall_grass, large_fern）: 无条件跳过
    private static final Set<String> INVISIBLE_BLOCKS = Set.of(
        "minecraft:torch", "minecraft:wall_torch", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
        "minecraft:soul_torch", "minecraft:soul_wall_torch",
        "minecraft:short_grass", "minecraft:grass", // 1.20+ uses short_grass
        "minecraft:tall_grass", "minecraft:large_fern", // 非花的 DoublePlantBlock
        "minecraft:glass", "minecraft:glass_pane"
    );

    // 花方块集合（参考 Xaero BlockTags.FLOWERS + FlowerBlock + TallFlowerBlock）
    // Xaero 默认 FLOWERS=true，所以花应该作为表面显示
    private static final Set<String> FLOWER_BLOCKS = Set.of(
        // 单层花 (FlowerBlock)
        "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
        "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip",
        "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip",
        "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
        "minecraft:wither_rose", "minecraft:brown_mushroom", "minecraft:red_mushroom",
        // 双层花 (TallFlowerBlock)
        "minecraft:sunflower", "minecraft:rose_bush", "minecraft:peony", "minecraft:pitcher_plant"
    );

    // 有地图颜色的方块（非隐形、非透明的实体方块）
    // 大部分方块都有颜色，这里列出需要排除的
    private static final Set<String> NO_COLOR_BLOCKS = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:structure_void", "minecraft:barrier"
    );

    // 发光方块集合（light >= 15 的方块）
    // 这些方块强制光照值 = 15
    private static final Set<String> GLOWING_BLOCKS = Set.of(
        "minecraft:glowstone", "minecraft:lava", "minecraft:flowing_lava",
        "minecraft:torch", "minecraft:wall_torch", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
        "minecraft:soul_torch", "minecraft:soul_wall_torch",
        "minecraft:sea_lantern", "minecraft:sea_pickle",
        "minecraft:shroomlight", "minecraft:end_rod",
        "minecraft:beacon", "minecraft:conduit",
        "minecraft:jack_o_lantern", "minecraft:magma_block",
        "minecraft:lantern", "minecraft:soul_lantern",
        "minecraft:campfire", "minecraft:soul_campfire",
        "minecraft:light", "minecraft:crying_obsidian",
        "minecraft:respawn_anchor", "minecraft:glow_lichen",
        "minecraft:calcite", "minecraft:small_amethyst_bud",
        "minecraft:medium_amethyst_bud", "minecraft:large_amethyst_bud",
        "minecraft:amethyst_cluster", "minecraft:budding_amethyst"
    );

    /**
     * 判断是否为空气方块
     */
    public static boolean isAir(String blockName) {
        return AIR_BLOCKS.contains(blockName);
    }

    /**
     * 判断是否为水方块
     */
    public static boolean isWater(String blockName) {
        return WATER_BLOCKS.contains(blockName);
    }

    /**
     * 判断是否为熔岩方块
     */
    public static boolean isLava(String blockName) {
        return LAVA_BLOCKS.contains(blockName);
    }

    /**
     * 判断是否为流体方块（水或熔岩）
     */
    public static boolean isFluid(String blockName) {
        return isWater(blockName) || isLava(blockName);
    }

    /**
     * 判断是否为透明流体（水 - 作为 overlay）
     * 熔岩是不透明的，作为表面
     */
    public static boolean isTranslucentFluid(String blockName) {
        return isWater(blockName);
    }

    /**
     * 判断是否为透明方块（作为 overlay）
     */
    public static boolean isTransparent(String blockName) {
        return TRANSPARENT_BLOCKS.contains(blockName) || isWater(blockName);
    }

    /**
     * 判断是否为隐形方块（扫描时跳过）
     */
    public static boolean isInvisible(String blockName) {
        return INVISIBLE_BLOCKS.contains(blockName);
    }

    /**
     * 判断是否为花方块
     * 参考 Xaero: BlockTags.FLOWERS + FlowerBlock + TallFlowerBlock
     */
    public static boolean isFlower(String blockName) {
        return FLOWER_BLOCKS.contains(blockName);
    }

    /**
     * 判断是否有地图颜色（可见的实体方块）
     */
    public static boolean hasVanillaColor(String blockName) {
        return !NO_COLOR_BLOCKS.contains(blockName) && !isAir(blockName);
    }

    /**
     * 判断是否为草方块
     */
    public static boolean isGrassBlock(String blockName) {
        return blockName.equals("minecraft:grass_block");
    }

    /**
     * 判断是否为发光方块（强制 light = 15）
     * 参考 Xaero MapWriter.isGlowing()
     */
    public static boolean isGlowing(String blockName) {
        return GLOWING_BLOCKS.contains(blockName);
    }

    /**
     * 获取方块的 overlay 不透明度（使用 lightBlock 值）
     * 参考 Xaero MapWriter: overlayBuilder.build(state, state.getLightBlock(...), ...)
     *
     * 水: 2 (水的 lightBlock，Minecraft 1.13+)
     * 熔岩: 15 (熔岩的 lightBlock)
     * 冰: 2 (冰的 lightBlock)
     * 玻璃: 0 (玻璃的 lightBlock)
     * 树叶: 1 (树叶的 lightBlock)
     * 其他: 估算值
     */
    public static int getOverlayOpacity(String blockName) {
        // 使用 lightBlock 值作为 opacity
        return getLightBlock(blockName);
    }

    /**
     * 获取方块的光照遮挡值（light block）
     * 参考 Minecraft Block.getLightBlock() 和 Xaero overlay 处理
     *
     * 数值来源：Minecraft Wiki (https://minecraft.wiki/w/Opacity)
     */
    public static int getLightBlock(String blockName) {
        // 水 - Minecraft 1.13+ 光照遮挡值为 2
        if (isWater(blockName)) return 2;
        // 熔岩 - 发光方块，遮挡全部光照
        if (isLava(blockName)) return 15;
        // 冰类 - 光照遮挡值为 2
        if (blockName.equals("minecraft:ice") ||
            blockName.equals("minecraft:packed_ice") ||
            blockName.equals("minecraft:blue_ice") ||
            blockName.equals("minecraft:frosted_ice")) return 2;
        // 树叶类 - 光照遮挡值为 1
        if (blockName.contains("leaves") ||
            blockName.endsWith("_leaves")) return 1;
        // 玻璃类 - 光照遮挡值为 0（透明）
        if (blockName.equals("minecraft:glass") ||
            blockName.equals("minecraft:glass_pane") ||
            blockName.contains("stained_glass") ||
            blockName.contains("tinted_glass")) return 0;
        // 水生植物 - 使用水的遮光值（它们在水中）
        if (blockName.equals("minecraft:kelp") ||
            blockName.equals("minecraft:kelp_plant") ||
            blockName.equals("minecraft:seagrass") ||
            blockName.equals("minecraft:tall_seagrass")) return 2;
        // 空气
        if (isAir(blockName)) return 0;
        // 含水方块（waterlogged）的遮光值取决于方块本身
        // 大多数含水方块遮光值为 0（如栅栏门、楼梯等）
        // 默认值：大多数实体方块遮挡全部光照
        return 15;
    }

    /**
     * 判断方块是否应该作为 overlay 处理
     */
    public static boolean shouldOverlay(String blockName) {
        return isTranslucentFluid(blockName) || isTransparent(blockName);
    }

    /**
     * 判断是否为含水方块（waterlogged=true）
     */
    public static boolean isWaterlogged(ChunkSectionParser.BlockState state) {
        return state.isWaterlogged();
    }

    /**
     * 判断方块是否为表面方块且上方有水（含水方块）
     */
    public static boolean isWaterloggedSurface(ChunkSectionParser.BlockState state) {
        return state.isWaterloggedSurface();
    }

    /**
     * 使用 BlockState 进行判断
     */
    public static boolean isAir(ChunkSectionParser.BlockState state) {
        return isAir(state.name());
    }

    public static boolean isWater(ChunkSectionParser.BlockState state) {
        return isWater(state.name());
    }

    public static boolean isFluid(ChunkSectionParser.BlockState state) {
        return isFluid(state.name());
    }

    public static boolean isTranslucentFluid(ChunkSectionParser.BlockState state) {
        return isTranslucentFluid(state.name());
    }

    public static boolean isTransparent(ChunkSectionParser.BlockState state) {
        return isTransparent(state.name());
    }

    public static boolean isInvisible(ChunkSectionParser.BlockState state) {
        return isInvisible(state.name());
    }

    public static boolean isFlower(ChunkSectionParser.BlockState state) {
        return isFlower(state.name());
    }

    public static boolean hasVanillaColor(ChunkSectionParser.BlockState state) {
        return hasVanillaColor(state.name());
    }

    public static boolean isGrassBlock(ChunkSectionParser.BlockState state) {
        return isGrassBlock(state.name());
    }

    public static int getOverlayOpacity(ChunkSectionParser.BlockState state) {
        return getOverlayOpacity(state.name());
    }

    public static int getLightBlock(ChunkSectionParser.BlockState state) {
        return getLightBlock(state.name());
    }

    public static boolean shouldOverlay(ChunkSectionParser.BlockState state) {
        return shouldOverlay(state.name());
    }
}