package com.mapsyncer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块属性解析器 - 使用 Minecraft API 查询方块属性
 * 参考 Xaero WorldMap 的实现方式
 * 支持 mod 方块的自动识别
 */
public class BlockPropertyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockPropertyResolver.class);

    // 占位用的 BlockGetter 和 BlockPos（用于需要参数的API调用）
    private static final BlockGetter PLACEHOLDER_BLOCK_GETTER = new PlaceholderBlockGetter();
    private static final BlockPos PLACEHOLDER_BLOCKPOS = BlockPos.ZERO;

    // 缓存方块属性查询结果
    private static final ConcurrentHashMap<String, BlockProperties> propertiesCache = new ConcurrentHashMap<>();

    // 有问题的方块集合（MapColor 抛出异常的方块）
    private static final ConcurrentHashMap<String, Boolean> buggedBlocks = new ConcurrentHashMap<>();

    /**
     * 方块属性集合
     */
    public record BlockProperties(
        boolean isAir,
        boolean isWater,
        boolean isLava,
        boolean isFluid,
        boolean isTransparent,      // 透明方块（玻璃、冰等）
        boolean isInvisible,        // 隐形方块（扫描时跳过）
        boolean isFlower,
        boolean isGrassBlock,
        boolean isGlowing,          // 发光方块
        int lightBlock,             // 光照遮挡值
        int lightEmission,          // 光照发射值
        boolean canBeWaterlogged,   // 是否可以含水
        boolean hasVanillaColor,    // 是否有地图颜色
        boolean hasMapColor         // 是否有有效的 MapColor
    ) {
        /**
         * 判断是否为含水方块表面
         */
        public boolean isWaterloggedSurface(Map<String, String> properties) {
            if (properties == null) return false;
            return canBeWaterlogged &&
                   "true".equals(properties.get("waterlogged")) &&
                   !isWater && !isAir;
        }

        /**
         * 判断是否为透明流体（水）
         */
        public boolean isTranslucentFluid() {
            return isWater;
        }

        /**
         * 判断是否应该作为 overlay 处理
         */
        public boolean shouldOverlay() {
            return isWater || isTransparent;
        }
    }

    /**
     * 获取方块属性（通过方块名称）
     * @param blockName 方块名称，如 "minecraft:stone" 或 "modid:custom_block"
     */
    public static BlockProperties getProperties(String blockName) {
        return propertiesCache.computeIfAbsent(blockName, BlockPropertyResolver::resolveProperties);
    }

    /**
     * 获取方块属性（通过BlockState）
     */
    public static BlockProperties getProperties(BlockState state) {
        String blockName = getKey(state);
        return getProperties(blockName);
    }

    /**
     * 解析方块属性（使用 Minecraft API）
     */
    private static BlockProperties resolveProperties(String blockName) {
        try {
            ResourceLocation location = ResourceLocation.parse(blockName);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(location);

            if (blockOpt.isEmpty()) {
                LOGGER.debug("Block not found in registry: {}, using fallback", blockName);
                return getFallbackProperties(blockName);
            }

            Block block = blockOpt.get();

            // 获取默认 BlockState（用于查询通用属性）
            BlockState defaultState = block.defaultBlockState();

            // 查询属性
            boolean isAir = defaultState.isAir() || block instanceof AirBlock;

            // 检查流体：通过 LiquidBlock 类判断
            boolean isFluid = block instanceof LiquidBlock;
            FluidState fluidState = defaultState.getFluidState();
            Fluid fluid = fluidState.getType();
            boolean isWater = fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
            boolean isLava = fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;

            // 检查透明性：使用 Xaero 方式（AirBlock, TransparentBlock, translucent 渲染）
            boolean isTransparent = checkTransparency(block, defaultState);

            // 检查隐形性：使用 RenderShape.INVISIBLE + 标签 + 类判断
            boolean isInvisible = checkInvisibility(block, defaultState, true);

            // 检查是否为花：使用 BlockTags.FLOWERS + 类判断
            boolean isFlower = checkIsFlower(block, defaultState);

            // 检查是否为草方块
            boolean isGrassBlock = block == Blocks.GRASS_BLOCK;

            // 检查发光性：使用 getLightEmission API
            int lightEmission = defaultState.getLightEmission();
            boolean isGlowing = lightEmission >= 15;

            // 获取光照遮挡值
            int lightBlock = getLightBlock(defaultState);

            // 检查是否可以含水
            boolean canBeWaterlogged = checkCanBeWaterlogged(block, defaultState);

            // 检查是否有有效的 MapColor
            boolean hasMapColor = checkHasMapColor(defaultState, blockName);

            // 检查是否有地图颜色（非空气、非隐形、非问题方块）
            boolean hasVanillaColor = !isAir && !isInvisible && !buggedBlocks.containsKey(blockName);

            return new BlockProperties(
                isAir, isWater, isLava, isFluid,
                isTransparent, isInvisible, isFlower, isGrassBlock,
                isGlowing, lightBlock, lightEmission, canBeWaterlogged,
                hasVanillaColor, hasMapColor
            );

        } catch (Exception e) {
            LOGGER.warn("Failed to resolve block properties for {}: {}", blockName, e.getMessage());
            return getFallbackProperties(blockName);
        }
    }

    /**
     * 检查方块是否有有效的 MapColor
     * 参考 Xaero hasVanillaColor 实现
     */
    private static boolean checkHasMapColor(BlockState state, String blockName) {
        try {
            MapColor mapColor = state.getMapColor(PLACEHOLDER_BLOCK_GETTER, PLACEHOLDER_BLOCKPOS);
            if (mapColor != null && mapColor.col != 0) {
                return true;
            }
        } catch (Throwable t) {
            // 记录有问题的方块
            buggedBlocks.put(blockName, true);
            LOGGER.debug("Broken vanilla map color definition found: {}", blockName);
        }
        return false;
    }

    /**
     * 获取光照遮挡值（兼容新旧API）
     */
    private static int getLightBlock(BlockState state) {
        try {
            // getLightBlock 需要 BlockGetter 和 BlockPos 参数
            return state.getLightBlock(PLACEHOLDER_BLOCK_GETTER, PLACEHOLDER_BLOCKPOS);
        } catch (Exception e) {
            // 备用：基于方块类型估算
            FluidState fluidState = state.getFluidState();
            if (!fluidState.isEmpty()) {
                // 水：遮挡值为2，熔岩：遮挡值为15
                if (fluidState.getType() == Fluids.WATER || fluidState.getType() == Fluids.FLOWING_WATER) {
                    return 2;
                }
                if (fluidState.getType() == Fluids.LAVA || fluidState.getType() == Fluids.FLOWING_LAVA) {
                    return 15;
                }
            }
            // 空气：遮挡值为0
            if (state.isAir()) {
                return 0;
            }
            // 树叶：遮挡值为1
            if (state.is(BlockTags.LEAVES)) {
                return 1;
            }
            // 默认：大多数实体方块遮挡全部光照
            return 15;
        }
    }

    /**
     * 检查方块是否为透明方块（作为 overlay）
     * 参考 Xaero shouldOverlay 实现
     */
    private static boolean checkTransparency(Block block, BlockState state) {
        // 1. AirBlock 或 TransparentBlock 类（Xaero 方式）
        if (block instanceof AirBlock || block instanceof TransparentBlock) {
            return true;
        }

        // 2. 检查光照遮挡值：小于15的通常是透明方块
        int lightBlock = getLightBlock(state);
        if (lightBlock > 0 && lightBlock < 15) {
            return true;
        }

        // 3. 水生植物（海带、海草）
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (block == Blocks.KELP || block == Blocks.KELP_PLANT ||
            block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS ||
            blockId.contains("kelp") || blockId.contains("seagrass")) {
            return true;
        }

        return false;
    }

    /**
     * 检查方块是否为隐形方块（扫描时跳过）
     * 参考 Xaero MapWriter.isInvisible() 实现
     *
     * @param flowers 是否启用花渲染（配置项）
     */
    private static boolean checkInvisibility(Block block, BlockState state, boolean flowers) {
        // 1. 渲染形状为 INVISIBLE（mod 方块自动支持）
        if (!(block instanceof LiquidBlock) &&
            state.getRenderShape() == RenderShape.INVISIBLE) {
            return true;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();

        // 2. 火把类（Xaero 硬编码）
        if (block == Blocks.TORCH || blockId.contains("torch") || blockId.endsWith("_torch")) {
            return true;
        }

        // 3. 矮草（Xaero 默认跳过）
        if (block == Blocks.SHORT_GRASS) {
            return true;
        }

        // 4. 玻璃类（Xaero 作为隐形处理）
        if (block == Blocks.GLASS || block == Blocks.GLASS_PANE ||
            blockId.contains("stained_glass") || blockId.contains("stained_glass_pane")) {
            return true;
        }

        // 5. 检查是否为花
        boolean isFlower = checkIsFlower(block, state);

        // 6. DoublePlantBlock 非花类型（高草、大型蕨）
        if (block instanceof DoublePlantBlock && !isFlower) {
            return true;
        }

        // 7. 花配置关闭时跳过花
        if (isFlower && !flowers) {
            return true;
        }

        // 8. 有问题的方块（MapColor 抛异常）
        String blockName = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (buggedBlocks.containsKey(blockName)) {
            return true;
        }

        return false;
    }

    /**
     * 检查方块是否为花
     * 参考 Xaero: BlockTags.FLOWERS + FlowerBlock + TallFlowerBlock
     */
    private static boolean checkIsFlower(Block block, BlockState state) {
        // 1. 使用 BlockTags.FLOWERS 标签（支持 mod 花）
        if (state.is(BlockTags.FLOWERS)) {
            return true;
        }

        // 2. FlowerBlock 类（原版小花）
        if (block instanceof FlowerBlock) {
            return true;
        }

        // 3. TallFlowerBlock 类（原版双层花）
        if (block instanceof TallFlowerBlock) {
            return true;
        }

        // 4. 特定的原版花（蘑菇不算花标签但算花类）
        if (block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM) {
            return true;
        }

        // 5. PitcherCropBlock（ Pitcher 植物）
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("pitcher") || blockId.contains("pitcher_crop")) {
            return true;
        }

        return false;
    }

    /**
     * 检查方块是否可以含水
     * 通过检查 BlockState 定义中是否有 waterlogged 属性
     */
    private static boolean checkCanBeWaterlogged(Block block, BlockState state) {
        // 检查状态定义中是否有 waterlogged 属性（最准确）
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals("waterlogged")) {
                return true;
            }
        }

        // 备用：常见可含水方块类型（名称匹配）
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("fence_gate") || blockId.contains("stairs") ||
            blockId.contains("slab") || blockId.contains("wall") ||
            blockId.contains("door") || blockId.contains("trapdoor") ||
            blockId.contains("lantern") || blockId.contains("chain") ||
            blockId.contains("coral") || blockId.contains("grate") ||
            blockId.contains("sign") || blockId.contains("banner") ||
            blockId.contains("bed") || blockId.contains("scaffolding") ||
            blockId.contains("conduit") || blockId.contains("light") ||
            blockId.contains("sea_pickle") || blockId.contains("kelp")) {
            return true;
        }

        return false;
    }

    /**
     * 备用属性（当方块未在注册表中找到时）
     * 使用字符串模式匹配推断属性
     */
    private static BlockProperties getFallbackProperties(String blockName) {
        String name = blockName.toLowerCase();

        boolean isAir = name.contains("air") || name.contains("void");
        boolean isWater = name.contains("water") && !name.contains("waterlogged");
        boolean isLava = name.contains("lava");
        boolean isFluid = isWater || isLava;

        boolean isTransparent = name.contains("glass") || name.contains("ice") ||
                               name.contains("kelp") || name.contains("seagrass");

        boolean isInvisible = name.contains("torch") ||
                             (name.contains("grass") && !name.contains("grass_block") && !name.contains("tall"));

        boolean isFlower = name.contains("flower") || name.contains("rose") ||
                          name.contains("tulip") || name.contains("lily");

        boolean isGrassBlock = name.contains("grass_block");

        boolean isGlowing = name.contains("glow") || name.contains("lantern") ||
                           name.contains("lamp") || name.contains("torch") ||
                           name.contains("lava") || name.contains("fire");

        int lightBlock = isAir ? 0 : (isFluid || isTransparent ? 2 : 15);
        int lightEmission = isGlowing ? 15 : 0;

        boolean canBeWaterlogged = name.contains("fence") || name.contains("stairs") ||
                                  name.contains("slab") || name.contains("door") ||
                                  name.contains("trapdoor") || name.contains("wall") ||
                                  name.contains("lantern") || name.contains("coral");

        boolean hasVanillaColor = !isAir && !isInvisible;
        boolean hasMapColor = hasVanillaColor;

        return new BlockProperties(
            isAir, isWater, isLava, isFluid,
            isTransparent, isInvisible, isFlower, isGrassBlock,
            isGlowing, lightBlock, lightEmission, canBeWaterlogged,
            hasVanillaColor, hasMapColor
        );
    }

    /**
     * 获取方块的注册表键名
     */
    public static String getKey(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * 获取方块的注册表键名
     */
    public static String getKey(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        propertiesCache.clear();
        buggedBlocks.clear();
    }

    /**
     * 获取缓存统计
     */
    public static int getCacheSize() {
        return propertiesCache.size();
    }

    /**
     * 获取问题方块数量
     */
    public static int getBuggedBlocksCount() {
        return buggedBlocks.size();
    }

    // ========== 便捷方法 ==========

    public static boolean isAir(String blockName) {
        return getProperties(blockName).isAir();
    }

    public static boolean isWater(String blockName) {
        return getProperties(blockName).isWater();
    }

    public static boolean isLava(String blockName) {
        return getProperties(blockName).isLava();
    }

    public static boolean isFluid(String blockName) {
        return getProperties(blockName).isFluid();
    }

    public static boolean isTransparent(String blockName) {
        return getProperties(blockName).isTransparent();
    }

    public static boolean isInvisible(String blockName) {
        return getProperties(blockName).isInvisible();
    }

    public static boolean isFlower(String blockName) {
        return getProperties(blockName).isFlower();
    }

    public static boolean isGrassBlock(String blockName) {
        return getProperties(blockName).isGrassBlock();
    }

    public static boolean isGlowing(String blockName) {
        return getProperties(blockName).isGlowing();
    }

    public static int getLightBlock(String blockName) {
        return getProperties(blockName).lightBlock();
    }

    public static int getLightEmission(String blockName) {
        return getProperties(blockName).lightEmission();
    }

    public static boolean canBeWaterlogged(String blockName) {
        return getProperties(blockName).canBeWaterlogged();
    }

    public static boolean hasVanillaColor(String blockName) {
        return getProperties(blockName).hasVanillaColor();
    }

    public static boolean hasMapColor(String blockName) {
        return getProperties(blockName).hasMapColor();
    }

    public static boolean shouldOverlay(String blockName) {
        return getProperties(blockName).shouldOverlay();
    }

    public static boolean isTranslucentFluid(String blockName) {
        return getProperties(blockName).isTranslucentFluid();
    }

    /**
     * 检查含水方块表面
     */
    public static boolean isWaterloggedSurface(String blockName, Map<String, String> properties) {
        return getProperties(blockName).isWaterloggedSurface(properties);
    }

    /**
     * 占位 BlockGetter（用于需要 BlockGetter 参数的 API）
     */
    private static class PlaceholderBlockGetter implements BlockGetter {
        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }
}