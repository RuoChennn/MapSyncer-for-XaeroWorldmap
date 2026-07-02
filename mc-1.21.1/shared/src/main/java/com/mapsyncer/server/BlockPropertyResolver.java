package com.mapsyncer.server;

import com.mapsyncer.config.CacheConfig;
import com.mapsyncer.mca.BlockPropertyLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseCoralPlantBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DeadBushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.TorchflowerCropBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.TwistingVinesPlantBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
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
 * 方块属性解析器 - 使用Minecraft API查询方块属性
 *
 * 参考 Xaero WorldMap 的实现方式，用于解析方块的各种属性：
 * - 是否为空气、流体、透明方块
 * - 是否为花、植物
 * - 光照遮挡值和发射值
 * - 是否可以含水
 * - 是否有有效的地图颜色
 *
 * 支持原版方块和mod方块的自动识别，使用缓存提高查询效率。
 */
public class BlockPropertyResolver {

    /** BlockPropertyLookup 适配器实例，供 core 模块的 RegionConverterStandalone 使用 */
    public static final BlockPropertyLookup INSTANCE = new BlockPropertyLookup() {
        @Override public int getFlags(String name) { return BlockPropertyResolver.getFlags(name); }
        @Override public boolean isWater(String name) { return BlockPropertyResolver.isWater(name); }
        @Override public boolean isTransparent(String name) { return BlockPropertyResolver.isTransparent(name); }
        @Override public boolean isInvisible(String name) { return BlockPropertyResolver.isInvisible(name); }
        @Override public boolean shouldOverlay(String name) { return BlockPropertyResolver.shouldOverlay(name); }
        @Override public boolean hasVanillaColor(String name) { return BlockPropertyResolver.hasVanillaColor(name); }
        @Override public boolean isGrassBlock(String name) { return BlockPropertyResolver.isGrassBlock(name); }
        @Override public boolean isGlowing(String name) { return BlockPropertyResolver.isGlowing(name); }
        @Override public boolean isTranslucentFluid(String name) { return BlockPropertyResolver.isTranslucentFluid(name); }
        @Override public boolean isWaterloggedSurface(String name, Map<String, String> props) { return BlockPropertyResolver.isWaterloggedSurface(name, props); }
        @Override public boolean isWaterInheriting(String name) { return BlockPropertyResolver.isWaterInheriting(name); }
        @Override public int getLightBlock(String name) { return BlockPropertyResolver.getLightBlock(name); }
    };

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockPropertyResolver.class);

    /** 占位用的BlockGetter和BlockPos（用于需要参数的API调用） */
    private static final BlockGetter PLACEHOLDER_BLOCK_GETTER = PlaceholderBlockGetter.INSTANCE;
    private static final BlockPos PLACEHOLDER_BLOCKPOS = BlockPos.ZERO;

    /** 缓存方块属性查询结果（并发安全，超过上限时批量淘汰） */
    private static final ConcurrentHashMap<String, BlockProperties> propertiesCache = new ConcurrentHashMap<>();

    /** 缓存最大容量（使用集中配置，便于管理） */
    private static final int MAX_CACHE_SIZE = CacheConfig.MAX_BLOCK_PROPERTIES_CACHE;

    /** 有问题的方块集合（MapColor抛出异常的方块） */
    private static final ConcurrentHashMap<String, Boolean> buggedBlocks = new ConcurrentHashMap<>();

    /**
     * 方块属性集合
     *
     * 包含方块的所有解析属性，用于地图渲染判断。
     */
    public record BlockProperties(
        boolean isAir,
        boolean isWater,
        boolean isLava,
        boolean isFluid,
        boolean isTransparent,      // 透明方块（玻璃、冰等）
        boolean isInvisible,        // 隐形方块（扫描时跳过）
        boolean isFlower,
        boolean isPlant,            // 植物（花、草、作物、蘑菇等）
        boolean isGrassBlock,
        boolean isGlowing,          // 发光方块
        int lightBlock,             // 光照遮挡值
        int lightEmission,          // 光照发射值
        boolean canBeWaterlogged,   // 是否可以含水
        boolean hasVanillaColor,    // 是否有地图颜色
        boolean hasMapColor,        // 是否有有效的MapColor
        boolean isAquaticPlant      // 是否为水生植物（海草、海带等，始终视为含水方块）
    ) {
        /**
         * 判断是否为含水方块表面
         *
         * <p>水生植物（海草、海带等）虽然在 NBT 中不存储 waterlogged 属性，
         * 但它们生长在水中，地图渲染时应作为含水方块处理：
         * 上层水作为 overlay，植物本身作为表面方块。</p>
         *
         * @param properties 方块属性键值对
         * @return true表示是含水方块表面
         */
        public boolean isWaterloggedSurface(Map<String, String> properties) {
            if (properties == null) return false;
            return canBeWaterlogged &&
                   "true".equals(properties.get("waterlogged")) &&
                   !isWater && !isAir;
        }

        /**
         * 判断是否为透明流体（水）
         *
         * @return true表示是透明流体
         */
        public boolean isTranslucentFluid() {
            return isWater;
        }

        /**
         * 判断是否应该作为overlay处理
         *
         * overlay方块（水、透明方块）会渲染在下层方块之上。
         *
         * @return true表示应该作为overlay处理
         */
        public boolean shouldOverlay() {
            return isWater || isTransparent;
        }
    }

    /**
     * 获取方块属性（通过方块名称）
     *
     * 使用缓存提高效率，相同方块名称只解析一次。
     * 当缓存超过上限时，清理部分旧条目（LRU策略）。
     *
     * @param blockName 方块名称，如"minecraft:stone"或"modid:custom_block"
     * @return 方块属性集合
     */
    public static BlockProperties getProperties(String blockName) {
        BlockProperties cached = propertiesCache.get(blockName);
        if (cached != null) {
            return cached;
        }
        BlockProperties resolved = resolveProperties(blockName);
        propertiesCache.put(blockName, resolved);
        trimCacheIfNeeded();
        return resolved;
    }

    private static void trimCacheIfNeeded() {
        int targetSize = (MAX_CACHE_SIZE * 3) / 4;
        int toRemove = propertiesCache.size() - targetSize;
        if (toRemove <= 0) {
            return;
        }
        var it = propertiesCache.keySet().iterator();
        while (toRemove > 0 && it.hasNext()) {
            it.next();
            it.remove();
            toRemove--;
        }
    }

    /**
     * 获取方块属性（通过BlockState）
     *
     * @param state 方块状态
     * @return 方块属性集合
     */
    public static BlockProperties getProperties(BlockState state) {
        String blockName = getKey(state);
        return getProperties(blockName);
    }

    /**
     * 解析方块属性（使用Minecraft API）
     *
     * 查询方块的默认BlockState获取各种属性信息。
     *
     * @param blockName 方块注册表名称
     * @return 解析后的方块属性
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

            // 检查是否为植物：使用基类 + 标签判断（更广泛）
            boolean isPlant = checkIsPlant(block, defaultState, isFlower);

            // 检查是否为草方块
            boolean isGrassBlock = block == Blocks.GRASS_BLOCK;

            // 检查发光性：使用 getLightEmission API
            int lightEmission = defaultState.getLightEmission();
            boolean isGlowing = lightEmission >= 15;

            // 获取光照遮挡值
            int lightBlock = getLightBlock(defaultState);

            // 检查是否可以含水
            boolean canBeWaterlogged = checkCanBeWaterlogged(block, defaultState);

            // 检查是否为水生植物（海草、海带等生长在水中的植物）
            boolean isAquaticPlant = checkIsAquaticPlant(block, defaultState);

            // 检查是否有有效的 MapColor
            boolean hasMapColor = checkHasMapColor(defaultState, blockName);

            // 检查是否有地图颜色（非空气、非隐形、非问题方块，且必须有有效的MapColor）
            boolean hasVanillaColor = !isAir && !isInvisible && !buggedBlocks.containsKey(blockName) && hasMapColor;

            return new BlockProperties(
                isAir, isWater, isLava, isFluid,
                isTransparent, isInvisible, isFlower, isPlant, isGrassBlock,
                isGlowing, lightBlock, lightEmission, canBeWaterlogged,
                hasVanillaColor, hasMapColor, isAquaticPlant
            );

        } catch (RuntimeException e) {
            LOGGER.warn("Failed to resolve block properties for {}: {}", blockName, e.getMessage());
            return getFallbackProperties(blockName);
        }
    }

    /**
     * 检查方块是否有有效的MapColor
     *
     * 参考 Xaero hasVanillaColor 实现。
     *
     * @param state 方块状态
     * @param blockName 方块注册表名称
     * @return true表示有有效的MapColor
     */
    private static boolean checkHasMapColor(BlockState state, String blockName) {
        // 树叶和草方块是 biome-tinted 方块，需要真实 BlockGetter 才能获取 MapColor。
        // 服务端占位 BlockGetter 缺少 getMinY/getBiome 等方法，调用会抛异常。
        // 这些方块始终有有效的 MapColor，直接返回 true。
        if (state.is(BlockTags.LEAVES)) {
            return true;
        }
        if (state.getBlock() == Blocks.GRASS_BLOCK) {
            return true;
        }

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
     *
     * @param state 方块状态
     * @return 光照遮挡值（0-15）
     */
    private static int getLightBlock(BlockState state) {
        try {
            // getLightBlock 需要 BlockGetter 和 BlockPos 参数
            return state.getLightBlock(PLACEHOLDER_BLOCK_GETTER, PLACEHOLDER_BLOCKPOS);
        } catch (RuntimeException e) {
            // 备用：基于方块类型估算
            FluidState fluidState = state.getFluidState();
            if (!fluidState.isEmpty()) {
                // 水：遮挡值为1，熔岩：遮挡值为15（MC 1.18+ water lightBlock = 1）
                if (fluidState.getType() == Fluids.WATER || fluidState.getType() == Fluids.FLOWING_WATER) {
                    return 1;
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
     * 检查方块是否为透明方块（作为overlay）
     *
     * 参考 Xaero shouldOverlay 实现：
     * 1. AirBlock或TransparentBlock类 → overlay
     * 2. 渲染类型是translucent的方块 → overlay
     *
     * 树叶虽渲染类型为 cutout_mipped，但 Xaero (Forge/Fabric) 均不将其
     * 视为 translucent/overlay，而是作为主像素 + noise-based biome sampling。
     *
     * @param block 方块实例
     * @param state 方块状态
     * @return true表示是透明方块
     */
    private static boolean checkTransparency(Block block, BlockState state) {
        // 1. AirBlock 或 TransparentBlock 类（Xaero 方式）
        // TransparentBlock 包括：玻璃、冰、遮光玻璃等
        if (block instanceof AirBlock || block instanceof TransparentBlock) {
            return true;
        }

        // 彩色玻璃板继承自 StainedGlassPaneBlock（非 TransparentBlock），应作为 overlay
        if (block instanceof net.minecraft.world.level.block.StainedGlassPaneBlock) {
            return true;
        }

        // 2. 流体方块（水、熔岩）- 使用 translucent 渲染
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            return true;  // 流体使用 translucent 渲染
        }

        // 4. 已知使用 translucent 渲染类型的方块
        // 参考 Minecraft 渲染类型定义
        // 注意：树叶使用 cutout 渲染，不是 translucent，所以不在这里

        // 5. 检查是否有 translucent 渲染类型的线索
        // 服务端无法直接调用客户端渲染 API，所以使用方块属性推断
        // translucent 渲染的方块通常有较低的 lightBlock 值，但不包括树叶
        // 树叶的 lightBlock = 1，但渲染类型是 cutout_mipped，不是 translucent

        // 树叶不作为 overlay：Xaero shouldOverlay() 通过检查 blockStateHasTranslucentRenderType
        // (Forge: getRenderTypes.contains(translucent), Fabric: getRenderLayer==translucent)
        // 判断 — 树叶渲染类型为 cutout_mipped/cutout，不被视为 translucent，因此不是 overlay。
        // 树叶应作为主像素渲染，biome 使用 fillBiomes 噪声插值的结果（对应我们 getBiomeAt 查树叶 Y）。
        if (state.is(BlockTags.LEAVES)) {
            return false;
        }

        // 排除雪片：雪层方块虽然 lightBlock 很低，但应作为表面渲染
        if (block == Blocks.SNOW) {
            return false;
        }

        // 其他 lightBlock < 15 的方块可能是 translucent
        int lightBlock = getLightBlock(state);
        if (lightBlock > 0 && lightBlock < 15) {
            return true;
        }

        return false;
    }

    /**
     * 检查方块是否为隐形方块（扫描时跳过）
     *
     * 参考 Xaero MapWriter.isInvisible() 实现。
     *
     * @param block 方块实例
     * @param state 方块状态
     * @param flowers 是否启用花渲染（配置项）
     * @return true表示是隐形方块
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

        // 4. 普通玻璃/玻璃板（完全透明，不在画面中渲染）
        // 彩色玻璃由 TransparentBlock → shouldOverlay() 路径处理
        if (block == Blocks.GLASS || block == Blocks.GLASS_PANE) {
            return true;
        }

        // 5. 检查是否为花
        boolean isFlower = checkIsFlower(block, state);

        // 6. DoublePlantBlock 非花类型（高草、大型蕨）
        // 与 Xaero MapWriter.isInvisible() 保持一致
        // 注意：tall_seagrass 继承 DoublePlantBlock，通过 isWaterloggedSurface 处理
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
     *
     * 参考 Xaero实现：BlockTags.FLOWERS + FlowerBlock + TallFlowerBlock
     *
     * @param block 方块实例
     * @param state 方块状态
     * @return true表示是花
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

        // 4. MushroomBlock 类（蘑菇）
        if (block instanceof MushroomBlock) {
            return true;
        }

        // 5. WaterlilyBlock 类（睡莲）
        if (block instanceof WaterlilyBlock) {
            return true;
        }

        // 6. 特定的原版花（蘑菇不算花标签但算花类）
        if (block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM) {
            return true;
        }

        // 7. PitcherCropBlock（Pitcher 植物）
        if (block instanceof PitcherCropBlock) {
            return true;
        }

        // 8. TorchflowerCropBlock（火炬花作物）
        if (block instanceof TorchflowerCropBlock) {
            return true;
        }

        return false;
    }

    /**
     * 检查方块是否为植物（花、草、作物、蘑菇、藤蔓等）
     *
     * 使用基类继承检查 + BlockTags标签判断。
     *
     * @param block 方块实例
     * @param state 方块状态
     * @param isFlower 是否已经是花
     * @return true表示是植物
     */
    private static boolean checkIsPlant(Block block, BlockState state, boolean isFlower) {
        // 如果已经是花，则也是植物
        if (isFlower) {
            return true;
        }

        // 1. BushBlock - 基础植物类（大多数植物的基类）
        if (block instanceof BushBlock) {
            return true;
        }

        // 2. CropBlock - 作物类（小麦、胡萝卜、土豆、甜菜根等）
        if (block instanceof CropBlock) {
            return true;
        }

        // 3. StemBlock - 瓜茎类（南瓜茎、西瓜茎）
        if (block instanceof StemBlock) {
            return true;
        }

        // 4. AttachedStemBlock - 已结果的瓜茎（已结果的南瓜/西瓜）
        if (block instanceof AttachedStemBlock) {
            return true;
        }

        // 5. SaplingBlock - 树苗类
        if (block instanceof SaplingBlock) {
            return true;
        }

        // 6. TallGrassBlock - 高草类
        if (block instanceof TallGrassBlock) {
            return true;
        }

        // 7. DeadBushBlock - 死灌木
        if (block instanceof DeadBushBlock) {
            return true;
        }

        // 8. CactusBlock - 仙人掌
        if (block instanceof CactusBlock) {
            return true;
        }

        // 9. SugarCaneBlock - 甘蔗
        if (block instanceof SugarCaneBlock) {
            return true;
        }

        // 10. BambooStalkBlock / BambooSaplingBlock - 竹子
        if (block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock) {
            return true;
        }

        // 11. NetherWartBlock - 地狱疣
        if (block instanceof NetherWartBlock) {
            return true;
        }

        // 12. GrowingPlantBlock / GrowingPlantBodyBlock / GrowingPlantHeadBlock - 生长植物（藤蔓类）
        if (block instanceof GrowingPlantBlock || block instanceof GrowingPlantBodyBlock || block instanceof GrowingPlantHeadBlock) {
            return true;
        }

        // 15. CaveVinesBlock / CaveVinesPlantBlock - 洞穴藤蔓（发光地衣）
        if (block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock) {
            return true;
        }

        // 16. TwistingVinesBlock / TwistingVinesPlantBlock - 扭曲藤蔓
        if (block instanceof TwistingVinesBlock || block instanceof TwistingVinesPlantBlock) {
            return true;
        }

        // 17. WeepingVinesBlock / WeepingVinesPlantBlock - 垂泪藤蔓
        if (block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock) {
            return true;
        }

        // 18. ChorusPlantBlock / ChorusFlowerBlock - 紫颂植物
        if (block instanceof ChorusPlantBlock || block instanceof ChorusFlowerBlock) {
            return true;
        }

        // 19. BaseCoralPlantBlock - 珊瑚植物基类
        if (block instanceof BaseCoralPlantBlock) {
            return true;
        }

        // 20. BigDripleafBlock / SmallDripleafBlock - 滴叶草
        if (block instanceof BigDripleafBlock || block instanceof SmallDripleafBlock) {
            return true;
        }

        // 21. 使用 BlockTags 检查（支持 mod 植物）
        // CROPS 标签 - 作物
        if (state.is(BlockTags.CROPS)) {
            return true;
        }

        // 22. 名称模式匹配（备用，用于未使用标准基类的 mod 植物）
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("plant") || blockId.contains("crop") ||
            blockId.contains("sapling") || blockId.contains("seed") ||
            blockId.contains("vine") || blockId.contains("fern") ||
            blockId.contains("bush") || blockId.contains("grass") ||
            blockId.contains("cactus") || blockId.contains("reed") ||
            blockId.contains("stem") || blockId.contains("leaf") ||
            blockId.contains("mushroom") || blockId.contains("fungus")) {
            return true;
        }

        return false;
    }

    /**
     * 检查方块是否可以含水
     *
     * 通过检查BlockState定义中是否有waterlogged属性。
     *
     * @param block 方块实例
     * @param state 方块状态
     * @return true表示可以含水
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
     * 检查方块是否为水生植物（海草、海带等生长在水中的植物）
     *
     * <p>水生植物虽然在 NBT 中不存储 waterlogged 属性，但实际生长在水中。
     * 地图渲染时应添加水 overlay，使其正确显示。</p>
     *
     * @param block 方块实例
     * @param state 方块状态
     * @return true表示是水生植物
     */
    private static boolean checkIsAquaticPlant(Block block, BlockState state) {
        // 海草类（包括短海草）
        if (block == Blocks.SEAGRASS) {
            return true;
        }
        // 海带类
        if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
            return true;
        }
        // 名称匹配（备用，支持 mod 水生植物）
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return blockId.equals("seagrass") || blockId.equals("tall_seagrass") ||
               blockId.equals("kelp") || blockId.equals("kelp_plant");
    }

    /**
     * 备用属性（当方块未在注册表中找到时）
     *
     * 使用字符串模式匹配推断属性。
     *
     * @param blockName 方块名称
     * @return 推断的方块属性
     */
    private static BlockProperties getFallbackProperties(String blockName) {
        String name = blockName.toLowerCase();

        boolean isAir = name.contains("air") || name.contains("void");
        boolean isWater = name.contains("water") && !name.contains("waterlogged");
        boolean isLava = name.contains("lava");
        boolean isFluid = isWater || isLava;

        boolean isTransparent = name.contains("glass") || name.contains("ice");

        boolean isInvisible = name.contains("torch") ||
                             (name.contains("grass") && !name.contains("grass_block") && !name.contains("tall"));

        boolean isFlower = name.contains("flower") || name.contains("rose") ||
                          name.contains("tulip") || name.contains("lily");

        // 植物检测（名称模式匹配）
        boolean isPlant = isFlower || name.contains("plant") || name.contains("crop") ||
                         name.contains("sapling") || name.contains("seed") ||
                         name.contains("vine") || name.contains("fern") ||
                         name.contains("bush") || name.contains("grass") ||
                         name.contains("cactus") || name.contains("reed") ||
                         name.contains("stem") || name.contains("leaf") ||
                         name.contains("mushroom") || name.contains("fungus") ||
                         name.contains("wheat") || name.contains("carrot") ||
                         name.contains("potato") || name.contains("beetroot");

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

        // 水生植物检测
        boolean isAquaticPlant = name.contains("seagrass") || name.contains("kelp");

        boolean hasVanillaColor = !isAir && !isInvisible;
        boolean hasMapColor = hasVanillaColor;

        return new BlockProperties(
            isAir, isWater, isLava, isFluid,
            isTransparent, isInvisible, isFlower, isPlant, isGrassBlock,
            isGlowing, lightBlock, lightEmission, canBeWaterlogged,
            hasVanillaColor, hasMapColor, isAquaticPlant
        );
    }

    /**
     * 获取方块的注册表键名
     *
     * @param state 方块状态
     * @return 方块注册表键名（如"minecraft:stone"）
     */
    public static String getKey(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * 获取方块的注册表键名
     *
     * @param block 方块实例
     * @return 方块注册表键名（如"minecraft:stone"）
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
     * 获取缓存大小
     *
     * @return 缓存中的方块数量
     */
    public static int getCacheSize() {
        return propertiesCache.size();
    }

    /**
     * 获取问题方块数量
     *
     * @return 有问题的方块数量（MapColor抛异常的方块）
     */
    public static int getBuggedBlocksCount() {
        return buggedBlocks.size();
    }

    // ========== 便捷方法 ==========

    /**
     * 判断是否为空气方块
     *
     * @param blockName 方块名称
     * @return true表示是空气
     */
    public static boolean isAir(String blockName) {
        return getProperties(blockName).isAir();
    }

    /**
     * 判断是否为水方块
     *
     * @param blockName 方块名称
     * @return true表示是水
     */
    public static boolean isWater(String blockName) {
        return getProperties(blockName).isWater();
    }

    /**
     * 判断是否为熔岩方块
     *
     * @param blockName 方块名称
     * @return true表示是熔岩
     */
    public static boolean isLava(String blockName) {
        return getProperties(blockName).isLava();
    }

    /**
     * 判断是否为流体方块
     *
     * @param blockName 方块名称
     * @return true表示是流体
     */
    public static boolean isFluid(String blockName) {
        return getProperties(blockName).isFluid();
    }

    /**
     * 判断是否为透明方块
     *
     * @param blockName 方块名称
     * @return true表示是透明方块
     */
    public static boolean isTransparent(String blockName) {
        return getProperties(blockName).isTransparent();
    }

    /**
     * 判断是否为隐形方块
     *
     * @param blockName 方块名称
     * @return true表示是隐形方块
     */
    public static boolean isInvisible(String blockName) {
        return getProperties(blockName).isInvisible();
    }

    /**
     * 判断是否为花
     *
     * @param blockName 方块名称
     * @return true表示是花
     */
    public static boolean isFlower(String blockName) {
        return getProperties(blockName).isFlower();
    }

    /**
     * 判断是否为植物
     *
     * @param blockName 方块名称
     * @return true表示是植物
     */
    public static boolean isPlant(String blockName) {
        return getProperties(blockName).isPlant();
    }

    /**
     * 判断是否为草方块
     *
     * @param blockName 方块名称
     * @return true表示是草方块
     */
    public static boolean isGrassBlock(String blockName) {
        return getProperties(blockName).isGrassBlock();
    }

    /**
     * 判断是否为发光方块
     *
     * @param blockName 方块名称
     * @return true表示是发光方块
     */
    public static boolean isGlowing(String blockName) {
        return getProperties(blockName).isGlowing();
    }

    /**
     * 获取光照遮挡值
     *
     * @param blockName 方块名称
     * @return 光照遮挡值（0-15）
     */
    public static int getLightBlock(String blockName) {
        return getProperties(blockName).lightBlock();
    }

    /**
     * 获取光照发射值
     *
     * @param blockName 方块名称
     * @return 光照发射值（0-15）
     */
    public static int getLightEmission(String blockName) {
        return getProperties(blockName).lightEmission();
    }

    /**
     * 判断是否可以含水
     *
     * @param blockName 方块名称
     * @return true表示可以含水
     */
    public static boolean canBeWaterlogged(String blockName) {
        return getProperties(blockName).canBeWaterlogged();
    }

    /**
     * 判断是否有原版地图颜色
     *
     * @param blockName 方块名称
     * @return true表示有原版地图颜色
     */
    public static boolean hasVanillaColor(String blockName) {
        return getProperties(blockName).hasVanillaColor();
    }

    /**
     * 判断是否有有效的地图颜色
     *
     * @param blockName 方块名称
     * @return true表示有有效的地图颜色
     */
    public static boolean hasMapColor(String blockName) {
        return getProperties(blockName).hasMapColor();
    }

    /**
     * 判断是否应该作为overlay处理
     *
     * @param blockName 方块名称
     * @return true表示应该作为overlay
     */
    public static boolean shouldOverlay(String blockName) {
        return getProperties(blockName).shouldOverlay();
    }

    /**
     * 判断是否为透明流体
     *
     * @param blockName 方块名称
     * @return true表示是透明流体
     */
    public static boolean isTranslucentFluid(String blockName) {
        return getProperties(blockName).isTranslucentFluid();
    }

    /**
     * 检查含水方块表面
     *
     * @param blockName 方块名称
     * @param properties 方块属性键值对
     * @return true表示是含水方块表面
     */
    public static boolean isWaterloggedSurface(String blockName, Map<String, String> properties) {
        return getProperties(blockName).isWaterloggedSurface(properties);
    }

    /**
     * 判断方块是否为水生植物（Water-inheriting）
     *
     * <p>水生植物（海草、海带等）生长在水中，地图生成时应继承上方水体的 overlay。
     * 扫描代码在遇到水生植物时会添加水 overlay，然后将植物作为表面方块。</p>
     *
     * @param blockName 方块注册表名称
     * @return true表示是水生植物
     */
    public static boolean isWaterInheriting(String blockName) {
        return getProperties(blockName).isAquaticPlant();
    }

    public static int getFlags(String blockName) {
        BlockProperties p = getProperties(blockName);
        int flags = 0;
        if (p.isWater()) flags |= BlockPropertyLookup.FLAG_WATER;
        if (p.isTransparent()) flags |= BlockPropertyLookup.FLAG_TRANSPARENT;
        if (p.isInvisible()) flags |= BlockPropertyLookup.FLAG_INVISIBLE;
        if (p.shouldOverlay()) flags |= BlockPropertyLookup.FLAG_SHOULD_OVERLAY;
        if (p.hasVanillaColor()) flags |= BlockPropertyLookup.FLAG_HAS_VANILLA_COLOR;
        if (p.isGlowing()) flags |= BlockPropertyLookup.FLAG_GLOWING;
        if (p.isTranslucentFluid()) flags |= BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID;
        if (p.isAquaticPlant()) flags |= BlockPropertyLookup.FLAG_WATER_INHERITING;
        return flags;
    }
}