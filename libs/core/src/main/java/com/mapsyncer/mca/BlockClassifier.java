package com.mapsyncer.mca;

import java.util.*;

/**
 * 方块判断器 - 基于字符串匹配的方块属性判断
 * 替代 Minecraft 的 BlockState 方法调用
 *
 * @deprecated 此类为备用功能，暂未使用。
 *             当前项目主要在服务器运行时工作，推荐使用 BlockPropertyLookup 的平台实现，
 *             它通过 Minecraft API 动态解析方块属性，支持 mod 方块。
 *             BlockClassifier 保留用于以下潜在场景：
 *             1. 离线/预生成模式（无 Minecraft 运行环境）
 *             2. BlockPropertyLookup 不可用时作为 fallback
 *             3. 需要快速判断且不需要 mod 方块支持的场景
 *
 * @see BlockPropertyLookup 运行时方块属性查询接口（推荐）
 */
@Deprecated(since = "2026-05-21", forRemoval = false)
public class BlockClassifier {

    private static final Set<String> AIR_BLOCKS = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air"
    );

    private static final Set<String> WATER_BLOCKS = Set.of(
        "minecraft:water", "minecraft:flowing_water"
    );

    private static final Set<String> LAVA_BLOCKS = Set.of(
        "minecraft:lava", "minecraft:flowing_lava"
    );

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
        "minecraft:tinted_glass"
    );

    private static final Set<String> INVISIBLE_BLOCKS = Set.of(
        "minecraft:torch", "minecraft:wall_torch", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
        "minecraft:soul_torch", "minecraft:soul_wall_torch",
        "minecraft:short_grass", "minecraft:grass",
        "minecraft:tall_grass", "minecraft:large_fern",
        "minecraft:glass", "minecraft:glass_pane"
    );

    private static final Set<String> FLOWER_BLOCKS = Set.of(
        "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
        "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip",
        "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip",
        "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
        "minecraft:wither_rose", "minecraft:brown_mushroom", "minecraft:red_mushroom",
        "minecraft:sunflower", "minecraft:rose_bush", "minecraft:peony", "minecraft:pitcher_plant"
    );

    private static final Set<String> NO_COLOR_BLOCKS = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:structure_void", "minecraft:barrier"
    );

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

    public static boolean isAir(String blockName) {
        return AIR_BLOCKS.contains(blockName);
    }

    public static boolean isWater(String blockName) {
        return WATER_BLOCKS.contains(blockName);
    }

    public static boolean isLava(String blockName) {
        return LAVA_BLOCKS.contains(blockName);
    }

    public static boolean isFluid(String blockName) {
        return isWater(blockName) || isLava(blockName);
    }

    public static boolean isTranslucentFluid(String blockName) {
        return isWater(blockName);
    }

    public static boolean isTransparent(String blockName) {
        return TRANSPARENT_BLOCKS.contains(blockName) || isWater(blockName);
    }

    public static boolean isInvisible(String blockName) {
        return INVISIBLE_BLOCKS.contains(blockName);
    }

    public static boolean isFlower(String blockName) {
        return FLOWER_BLOCKS.contains(blockName);
    }

    public static boolean hasVanillaColor(String blockName) {
        return !NO_COLOR_BLOCKS.contains(blockName) && !isAir(blockName);
    }

    public static boolean isGrassBlock(String blockName) {
        return blockName.equals("minecraft:grass_block");
    }

    public static boolean isGlowing(String blockName) {
        return GLOWING_BLOCKS.contains(blockName);
    }

    public static boolean shouldOverlay(String blockName) {
        return isTranslucentFluid(blockName) || isTransparent(blockName);
    }

    public static int getLightBlock(String blockName) {
        if (isWater(blockName)) return 2;
        if (isLava(blockName)) return 15;
        if (blockName.equals("minecraft:ice") ||
            blockName.equals("minecraft:packed_ice") ||
            blockName.equals("minecraft:blue_ice") ||
            blockName.equals("minecraft:frosted_ice")) return 2;
        if (blockName.contains("leaves") ||
            blockName.endsWith("_leaves")) return 1;
        if (blockName.equals("minecraft:glass") ||
            blockName.equals("minecraft:glass_pane") ||
            blockName.contains("stained_glass") ||
            blockName.contains("tinted_glass")) return 0;
        if (isAir(blockName)) return 0;
        return 15;
    }
}
