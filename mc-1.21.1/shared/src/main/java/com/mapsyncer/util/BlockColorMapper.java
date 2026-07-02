package com.mapsyncer.util;

import com.mapsyncer.config.CacheConfig;
import com.mapsyncer.server.PlaceholderBlockGetter;
import com.mapsyncer.platform.PlatformManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块颜色映射器
 * 参考 Xaero WorldMap 的颜色获取实现
 * 支持原版方块、mod 方块和纹理颜色提取
 *
 * 使用四层颜色获取策略：
 * 1. 纹理颜色提取（仅客户端可用）
 * 2. MapColor API
 * 3. 原版方块精确颜色
 * 4. 启发式规则（基于方块名称模式）
 *
 * 注意：此类包含所有平台共享的业务逻辑，通过 PlatformManager 访问平台特定功能。
 */
public class BlockColorMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockColorMapper.class);

    /** 方块颜色查询结果缓存 */
    private static final ConcurrentHashMap<String, Integer> blockColorCache = new ConcurrentHashMap<>();

    /** 纹理颜色缓存 */
    private static final ConcurrentHashMap<String, Integer> textureColorCache = new ConcurrentHashMap<>();

    /** 有问题的方块集合（MapColor 抛出异常） */
    private static final ConcurrentHashMap<String, Boolean> buggedBlocks = new ConcurrentHashMap<>();

    /** 缓存最大条目数（使用集中配置，便于管理） */
    private static final int MAX_CACHE_SIZE = CacheConfig.MAX_BLOCK_COLOR_CACHE;

    /** 缓存是否需要清除的标志 */
    private static volatile boolean clearCachedColors = false;

    /** 启发式规则：基于方块名称模式的默认颜色 */
    private static final Map<String, Integer> patternColors = new HashMap<>();

    static {
        initPatternColors();
    }

    /**
     * 初始化启发式颜色模式规则
     */
    private static void initPatternColors() {
        // 矿石类 - 金色
        patternColors.put("_ore", 0xFDF546);
        patternColors.put("_deepslate_ore", 0xFDF546);

        // 原木类 - 棕色
        patternColors.put("_log", 0x6B5231);
        patternColors.put("_wood", 0x6B5231);
        patternColors.put("_stem", 0x6B5231);
        patternColors.put("_hyphae", 0x6B5231);

        // 树叶类 - 绿色
        patternColors.put("_leaves", 0x3A7D23);

        // 木板类 - 木色
        patternColors.put("_planks", 0xBC945A);

        // 石头类 - 灰色
        patternColors.put("stone", 0x808080);
        patternColors.put("_stone", 0x808080);
        patternColors.put("cobblestone", 0x7F7F7F);
        patternColors.put("_cobblestone", 0x7F7F7F);
        patternColors.put("deepslate", 0x6B6B6B);
        patternColors.put("_deepslate", 0x6B6B6B);

        // 土类 - 土色
        patternColors.put("dirt", 0x866043);
        patternColors.put("_dirt", 0x866043);
        patternColors.put("grass_block", 0x5B8731);
        patternColors.put("farmland", 0x866043);
        patternColors.put("podzol", 0x6B5231);
        patternColors.put("mycelium", 0x6B5231);

        // 砂类 - 砂色
        patternColors.put("sand", 0xD9E090);
        patternColors.put("_sand", 0xD9E090);
        patternColors.put("sandstone", 0xD7D2A0);
        patternColors.put("_sandstone", 0xD7D2A0);
        patternColors.put("gravel", 0x848484);

        // 水类 - 蓝色
        patternColors.put("water", 0x3344FF);
        patternColors.put("_water", 0x3344FF);

        // 熔岩类 - 橙色
        patternColors.put("lava", 0xFF6600);
        patternColors.put("_lava", 0xFF6600);

        // 下界类 - 红色
        patternColors.put("netherrack", 0x723131);
        patternColors.put("_netherrack", 0x723131);
        patternColors.put("nether_bricks", 0x2A1515);
        patternColors.put("_nether_bricks", 0x2A1515);
        patternColors.put("soul_sand", 0x50433B);
        patternColors.put("soul_soil", 0x50433B);
        patternColors.put("crimson_", 0x8B3030);
        patternColors.put("warped_", 0x2E7B5E);

        // 末地类 - 末地色
        patternColors.put("end_stone", 0xD6D69D);
        patternColors.put("_end_stone", 0xD6D69D);

        // 冰类 - 冰色
        patternColors.put("ice", 0xA0D0FF);
        patternColors.put("_ice", 0xA0D0FF);
        patternColors.put("snow", 0xFAFAFF);
        patternColors.put("_snow", 0xFAFAFF);

        // 玻璃类 - 浅蓝白色
        patternColors.put("glass", 0xE0F0FF);
        patternColors.put("_glass", 0xE0F0FF);

        // 金属类 - 金属色
        patternColors.put("iron", 0xD8AF8A);
        patternColors.put("_iron", 0xD8AF8A);
        patternColors.put("gold", 0xFDF546);
        patternColors.put("_gold", 0xFDF546);
        patternColors.put("copper", 0xB87333);
        patternColors.put("_copper", 0xB87333);
        patternColors.put("diamond", 0x4AEDD0);
        patternColors.put("_diamond", 0x4AEDD0);
        patternColors.put("emerald", 0x33FF66);
        patternColors.put("_emerald", 0x33FF66);
        patternColors.put("lapis", 0x3355FF);
        patternColors.put("_lapis", 0x3355FF);
        patternColors.put("redstone", 0xFF3333);
        patternColors.put("_redstone", 0xFF3333);
        patternColors.put("netherite", 0x4A4A4A);
        patternColors.put("_netherite", 0x4A4A4A);

        // 草类 - 绿色
        patternColors.put("grass", 0x7ABD47);
        patternColors.put("fern", 0x5B8731);
        patternColors.put("seagrass", 0x5B8731);
        patternColors.put("kelp", 0x5B8731);
        patternColors.put("cactus", 0x5B8731);

        // 花 - 花色
        patternColors.put("flower", 0xFF69B4);
        patternColors.put("rose", 0xFF3333);
        patternColors.put("tulip", 0xFF9999);
        patternColors.put("dandelion", 0xFFFF00);
        patternColors.put("orchid", 0x3399FF);

        // 双层花 - 需要根据 half 属性处理
        patternColors.put("sunflower_upper", 0xFFD700);
        patternColors.put("rose_bush_upper", 0xFF3333);
        patternColors.put("peony_upper", 0xFFB6C1);
        patternColors.put("pitcher_plant_upper", 0x9932CC);

        // 羊毛类
        patternColors.put("wool", 0xFFFFFF);
        patternColors.put("_wool", 0xFFFFFF);

        // 陶瓦类
        patternColors.put("terracotta", 0xC9674B);
        patternColors.put("_terracotta", 0xC9674B);

        // 混凝土类
        patternColors.put("concrete", 0x808080);
        patternColors.put("_concrete", 0x808080);

        // 发光类
        patternColors.put("glowstone", 0xFFCC66);
        patternColors.put("shroomlight", 0xFFCC66);
        patternColors.put("lantern", 0xFFCC66);
        patternColors.put("lamp", 0xFFCC66);
        patternColors.put("sea_lantern", 0xE0E8FF);

        // 建筑类
        patternColors.put("bricks", 0xB54B3D);
        patternColors.put("_bricks", 0xB54B3D);
        patternColors.put("brick", 0xB54B3D);

        // 基岩
        patternColors.put("bedrock", 0x333333);
        patternColors.put("obsidian", 0x1A1A2E);
        patternColors.put("_obsidian", 0x1A1A2E);
        patternColors.put("crying_obsidian", 0x1A1A2E);
    }

    /**
     * 获取方块颜色（通过 BlockState）
     *
     * @param state 方块状态
     * @return 方块颜色值（ARGB 格式）
     */
    public static int getBlockColor(BlockState state) {
        String blockName = getKey(state);
        checkCacheSize();
        return blockColorCache.computeIfAbsent(blockName, name -> computeColor(state, name));
    }

    /**
     * 检查缓存大小，超过限制时清理。
     * 防止服务端长期运行导致无界缓存增长。
     */
    private static void checkCacheSize() {
        trimColorCacheIfNeeded(blockColorCache);
        trimColorCacheIfNeeded(textureColorCache);
    }

    private static void trimColorCacheIfNeeded(ConcurrentHashMap<String, Integer> cache) {
        int targetSize = (MAX_CACHE_SIZE * 3) / 4;
        int toRemove = cache.size() - targetSize;
        if (toRemove <= 0) {
            return;
        }
        var it = cache.keySet().iterator();
        while (toRemove > 0 && it.hasNext()) {
            it.next();
            it.remove();
            toRemove--;
        }
        LOGGER.debug("Trimmed color cache to {} entries (target {})", cache.size(), targetSize);
    }

    /**
     * 获取方块颜色（通过方块名称和属性）
     * 用于处理需要根据属性确定颜色的方块（如双层花的 half 属性）
     *
     * @param blockName 方块名称
     * @param properties 方块属性
     * @return 颜色值
     */
    public static int getBlockColorWithProperties(String blockName, Map<String, String> properties) {
        // 特殊处理：双层花的 half 属性
        if (properties != null && properties.containsKey("half")) {
            String half = properties.get("half");
            String key = blockName + "_" + half;
            Integer specialColor = patternColors.get(key.toLowerCase());
            if (specialColor != null) {
                return specialColor;
            }
        }

        // 默认使用方块名称获取颜色
        return getBlockColorByName(blockName);
    }

    /**
     * 获取方块颜色（通过方块名称）
     */
    public static int getBlockColorByName(String blockName) {
        return blockColorCache.computeIfAbsent(blockName, BlockColorMapper::computeColorByName);
    }

    /**
     * 计算方块颜色（使用四层策略）
     *
     * @param state 方块状态
     * @param blockName 方块注册名
     * @return 计算得出的颜色值
     */
    private static int computeColor(BlockState state, String blockName) {
        // 检查是否需要清除缓存
        if (clearCachedColors) {
            blockColorCache.clear();
            textureColorCache.clear();
            clearCachedColors = false;
            LOGGER.debug("BlockColorMapper cache cleared");
        }

        // 检查是否为问题方块
        if (buggedBlocks.containsKey(blockName)) {
            return computeColorFromPattern(blockName);
        }

        // 第一层：尝试纹理颜色提取（仅客户端）
        if (PlatformManager.getPlatform().isClientEnvironment()) {
            int textureColor = tryGetTextureColor(state, blockName);
            if (textureColor != -1) {
                LOGGER.debug("Using texture color for {}: {}", blockName, Integer.toHexString(textureColor));
                return textureColor;
            }
        }

        // 第二层：尝试 MapColor API
        int mapColor = tryGetMapColor(state, blockName);
        if (mapColor != -1) {
            return mapColor;
        }

        // 第三层：原版方块精确颜色
        int vanillaColor = getVanillaBlockColor(state);
        if (vanillaColor != -1) {
            return vanillaColor;
        }

        // 第四层：启发式规则
        return computeColorFromPattern(blockName);
    }

    /**
     * 计算方块颜色（通过名称，无法获取 BlockState 时）
     *
     * @param blockName 方块注册名
     * @return 计算得出的颜色值
     */
    private static int computeColorByName(String blockName) {
        // 检查是否为问题方块
        if (buggedBlocks.containsKey(blockName)) {
            return computeColorFromPattern(blockName);
        }

        // 尝试获取方块并使用 BlockState
        try {
            ResourceLocation location = ResourceLocation.parse(blockName);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(location);

            if (blockOpt.isPresent()) {
                BlockState defaultState = blockOpt.get().defaultBlockState();
                return computeColor(defaultState, blockName);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to parse block name: {}", blockName);
        }

        // 使用启发式规则
        return computeColorFromPattern(blockName);
    }

    /**
     * 尝试从纹理提取颜色（参考 Xaero loadBlockColourFromTexture）
     * 仅在客户端环境可用
     *
     * @param state 方块状态
     * @param blockName 方块注册名
     * @return 纹理颜色值，失败返回 -1
     */
    private static int tryGetTextureColor(BlockState state, String blockName) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getBlockRenderer() == null) {
                return -1;
            }

            BlockModelShaper bms = mc.getBlockRenderer().getBlockModelShaper();
            BakedModel model = bms.getBlockModel(state);

            if (model == null) {
                return -1;
            }

            // 尝试获取 UP 方向的 quads
            List<BakedQuad> upQuads = model.getQuads(state, Direction.UP, mc.level.random);

            TextureAtlasSprite texture;
            int tintIndex = -1;

            if (upQuads != null && !upQuads.isEmpty()) {
                texture = upQuads.get(0).getSprite();
                tintIndex = upQuads.get(0).getTintIndex();
            } else {
                // 使用 particle 纹理
                texture = model.getParticleIcon();
                tintIndex = 0;
            }

            if (texture == null) {
                return -1;
            }

            // 从纹理名称提取颜色
            String textureName = texture.contents().name().toString() + ".png";
            Integer cachedColor = textureColorCache.get(textureName);

            if (cachedColor != null) {
                return cachedColor;
            }

            // 从纹理资源加载颜色
            int color = extractColorFromTexture(textureName, mc);
            if (color != -1) {
                textureColorCache.put(textureName, color);
                return color;
            }

        } catch (Exception e) {
            LOGGER.debug("Failed to get texture color for {}: {}", blockName, e.getMessage());
        }

        return -1;
    }

    /**
     * 从纹理资源提取平均颜色
     *
     * @param textureName 纹理名称
     * @param mc Minecraft 客户端实例
     * @return 纹理平均颜色值，失败返回 -1
     */
    private static int extractColorFromTexture(String textureName, Minecraft mc) {
        try {
            String[] args = textureName.split(":");
            if (args.length < 2) {
                args = new String[]{"minecraft", args[0]};
            }

            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(args[0], "textures/" + args[1]);

            var resource = mc.getResourceManager().getResource(location).orElse(null);
            if (resource == null) {
                return -1;
            }

            try (InputStream input = resource.open();
                 ImageInputStream imageInputStream = ImageIO.createImageInputStream(input)) {
                BufferedImage img = ImageIO.read(imageInputStream);

                if (img == null) {
                    return -1;
                }

                // 计算纹理平均颜色
                int red = 0, green = 0, blue = 0, alpha = 0;
                int total = 0;

                int width = img.getWidth();
                int height = img.getHeight();
                int ts = Math.min(width, height);

                if (ts > 0) {
                    int diff = Math.max(1, Math.min(4, ts / 8));
                    int parts = ts / diff;

                    Raster raster = img.getData();
                    int[] colorHolder = null;

                    for (int i = 0; i < parts; i++) {
                        for (int j = 0; j < parts; j++) {
                            int rgb;
                            if (img.getColorModel().getNumComponents() < 3) {
                                colorHolder = raster.getPixel(i * diff, j * diff, colorHolder);
                                int sample = colorHolder[0] & 0xFF;
                                int a = colorHolder.length > 1 ? colorHolder[1] : 255;
                                rgb = a << 24 | sample << 16 | sample << 8 | sample;
                            } else {
                                rgb = img.getRGB(i * diff, j * diff);
                            }

                            int a = (rgb >> 24) & 0xFF;
                            if (rgb == 0 || a == 0) continue;

                            red += (rgb >> 16) & 0xFF;
                            green += (rgb >> 8) & 0xFF;
                            blue += rgb & 0xFF;
                            alpha += a;
                            total++;
                        }
                    }
                }

                if (total == 0) {
                    total = 1;
                }

                red = red / total;
                green = green / total;
                blue = blue / total;
                alpha = Math.min(255, alpha / total);

                return (alpha << 24) | (red << 16) | (green << 8) | blue;
            }

        } catch (IOException e) {
            LOGGER.debug("Failed to extract color from texture {}: {}", textureName, e.getMessage());
        }

        return -1;
    }

    /**
     * 尝试从 MapColor API 获取颜色
     *
     * @param state 方块状态
     * @param blockName 方块注册名
     * @return MapColor 颜色值，失败返回 -1
     */
    private static int tryGetMapColor(BlockState state, String blockName) {
        try {
            // 使用工厂获取占位 BlockGetter
            BlockGetter placeholderBlockGetter = PlaceholderBlockGetter.INSTANCE;
            BlockPos placeholderPos = BlockPos.ZERO;

            MapColor mapColor = state.getMapColor(placeholderBlockGetter, placeholderPos);

            if (mapColor != null && mapColor.col != 0) {
                // MapColor 的颜色值
                int color = getMapColorValue(mapColor);
                if (color != 0x808080) {  // 不是默认灰色
                    return color;
                }
                // 使用 MapColor 的原始 col 值
                return mapColor.col;
            }

        } catch (Throwable t) {
            // 记录有问题的方块
            buggedBlocks.put(blockName, true);
            LOGGER.debug("Broken vanilla map color definition found: {}", blockName);
        }

        return -1;
    }

    /**
     * 从 MapColor 获取颜色值
     * 参考：https://minecraft.wiki/w/Map_color
     *
     * @param mapColor MapColor 对象
     * @return RGB 颜色值
     */
    private static int getMapColorValue(MapColor mapColor) {
        // MapColor 的颜色 ID 到 RGB 的映射
        return switch (mapColor.id) {
            case 0 -> 0x808080;  // NONE
            case 1 -> 0x5B8731;  // GRASS
            case 2 -> 0x866043;  // SAND
            case 3 -> 0x808080;  // WOOL
            case 4 -> 0xFF3333;  // TNT / FIRE
            case 5 -> 0xA0D0FF;  // ICE
            case 6 -> 0xFAFAFF;  // SNOW
            case 7 -> 0x3344FF;  // WATER
            case 8 -> 0x7ABD47;  // PLANT
            case 9 -> 0x723131;  // CLAY / NETHERRACK
            case 10 -> 0x866043; // DIRT
            case 12 -> 0xD9E090; // GOLD
            case 13 -> 0xD7D2A0; // SANDSTONE
            case 14 -> 0x6B5231; // WOOD
            case 15 -> 0x808080; // STONE
            case 20 -> 0xD6D69D; // END_STONE
            case 21 -> 0x723131; // NETHERRACK
            case 22 -> 0x2A1515; // NETHER_BRICKS
            case 23 -> 0x8B3030; // CRIMSON_NYLIUM
            case 24 -> 0x2E7B5E; // WARPED_NYLIUM
            case 25 -> 0x1A1A2E; // OBSIDIAN
            case 26 -> 0x6B5231; // PODZOL
            case 27 -> 0x6B5231; // MYCELIUM
            case 28 -> 0xA0D0FF; // ICE
            case 29 -> 0xD8AF8A; // IRON
            case 32 -> 0xA0A4C9; // CLAY
            case 33 -> 0xFF6600; // LAVA
            case 35 -> 0x6B5231; // TERRACOTTA
            case 36 -> 0x7ABD47; // PLANT
            case 37 -> 0x3A7D23; // LEAVES
            case 61 -> 0x4AEDD0; // DIAMOND
            case 62 -> 0x33FF66; // EMERALD
            case 63 -> 0x3355FF; // LAPIS
            default -> 0x808080;
        };
    }

    /**
     * 原版方块精确颜色（保持与之前一致的视觉效果）
     *
     * @param state 方块状态
     * @return 精确颜色值，非原版方块返回 -1
     */
    private static int getVanillaBlockColor(BlockState state) {
        Block block = state.getBlock();

        // 常见原版方块精确颜色
        if (block == Blocks.GRASS_BLOCK) return 0x5B8731;
        if (block == Blocks.STONE) return 0x808080;
        if (block == Blocks.DIRT) return 0x866043;
        if (block == Blocks.SAND) return 0xD9E090;
        if (block == Blocks.WATER) return 0x3344FF;
        if (block == Blocks.OAK_LOG) return 0x6B5231;
        if (block == Blocks.OAK_LEAVES) return 0x3A7D23;
        if (block == Blocks.SNOW) return 0xFAFAFF;
        if (block == Blocks.ICE) return 0xA0D0FF;
        if (block == Blocks.GRAVEL) return 0x848484;
        if (block == Blocks.COBBLESTONE) return 0x7F7F7F;
        if (block == Blocks.BEDROCK) return 0x333333;
        if (block == Blocks.OBSIDIAN) return 0x1A1A2E;
        if (block == Blocks.GOLD_ORE) return 0xFDF546;
        if (block == Blocks.IRON_ORE) return 0xD8AF8A;
        if (block == Blocks.COAL_ORE) return 0x4A4A4A;
        if (block == Blocks.DIAMOND_ORE) return 0x4AEDD0;
        if (block == Blocks.REDSTONE_ORE) return 0xFF3333;
        if (block == Blocks.LAPIS_ORE) return 0x3355FF;
        if (block == Blocks.EMERALD_ORE) return 0x33FF66;
        if (block == Blocks.CLAY) return 0xA0A4C9;
        if (block == Blocks.SANDSTONE) return 0xD7D2A0;
        if (block == Blocks.SHORT_GRASS) return 0x7ABD47;
        if (block == Blocks.FERN) return 0x5B8731;
        if (block == Blocks.DEAD_BUSH) return 0x9B8B6B;
        if (block == Blocks.CACTUS) return 0x5B8731;
        if (block == Blocks.OAK_PLANKS) return 0xBC945A;
        if (block == Blocks.SPRUCE_PLANKS) return 0x70543E;
        if (block == Blocks.BIRCH_PLANKS) return 0xA6864B;
        if (block == Blocks.GLASS) return 0xE0F0FF;
        if (block == Blocks.LAVA) return 0xFF6600;
        if (block == Blocks.NETHERRACK) return 0x723131;
        if (block == Blocks.SOUL_SAND) return 0x50433B;
        if (block == Blocks.END_STONE) return 0xD6D69D;
        if (block == Blocks.GLOWSTONE) return 0xFFCC66;
        if (block == Blocks.NETHER_BRICKS) return 0x2A1515;
        if (block == Blocks.RED_NETHER_BRICKS) return 0x5B2020;
        if (block == Blocks.CRIMSON_NYLIUM) return 0x8B3030;
        if (block == Blocks.WARPED_NYLIUM) return 0x2E7B5E;
        if (block == Blocks.PODZOL) return 0x6B5231;
        if (block == Blocks.MYCELIUM) return 0x6B5231;
        if (block == Blocks.DEEPSLATE) return 0x6B6B6B;
        if (block == Blocks.DEEPSLATE_GOLD_ORE) return 0xFDF546;
        if (block == Blocks.DEEPSLATE_IRON_ORE) return 0xD8AF8A;
        if (block == Blocks.DEEPSLATE_COAL_ORE) return 0x4A4A4A;
        if (block == Blocks.DEEPSLATE_DIAMOND_ORE) return 0x4AEDD0;
        if (block == Blocks.DEEPSLATE_REDSTONE_ORE) return 0xFF3333;
        if (block == Blocks.DEEPSLATE_LAPIS_ORE) return 0x3355FF;
        if (block == Blocks.DEEPSLATE_EMERALD_ORE) return 0x33FF66;

        return -1;  // 未找到原版方块
    }

    /**
     * 从方块名称模式推断颜色（启发式规则）
     *
     * @param blockName 方块注册名
     * @return 推断得出的颜色值，默认返回灰色
     */
    private static int computeColorFromPattern(String blockName) {
        String name = blockName.toLowerCase();

        // 检查模式匹配（优先匹配最长模式）
        String bestMatch = null;
        int bestLength = 0;

        for (Map.Entry<String, Integer> entry : patternColors.entrySet()) {
            String pattern = entry.getKey();
            if (name.endsWith(pattern) || name.contains(pattern)) {
                if (pattern.length() > bestLength) {
                    bestMatch = pattern;
                    bestLength = pattern.length();
                }
            }
        }

        if (bestMatch != null) {
            return patternColors.get(bestMatch);
        }

        // 默认灰色
        return 0x808080;
    }

    /**
     * 获取方块的注册表键名
     *
     * @param state 方块状态
     * @return 方块注册名（如 "minecraft:stone"）
     */
    public static String getKey(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * 获取方块的注册表键名
     *
     * @param block 方块对象
     * @return 方块注册名（如 "minecraft:stone"）
     */
    public static String getKey(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        clearCachedColors = true;
        blockColorCache.clear();
        textureColorCache.clear();
        buggedBlocks.clear();
    }

    /**
     * 获取缓存大小
     *
     * @return 方块颜色缓存中的条目数量
     */
    public static int getCacheSize() {
        return blockColorCache.size();
    }

    /**
     * 获取纹理缓存大小
     *
     * @return 纹理颜色缓存中的条目数量
     */
    public static int getTextureCacheSize() {
        return textureColorCache.size();
    }

    /**
     * 添加自定义颜色规则（用于配置扩展）
     *
     * @param pattern 方块名称模式（如 "_ore"）
     * @param color 颜色值（RGB 格式）
     */
    public static void addPatternColor(String pattern, int color) {
        patternColors.put(pattern.toLowerCase(), color);
    }

    /**
     * 批量添加自定义颜色规则
     *
     * @param colors 颜色规则 Map（模式 -> 颜色值）
     */
    public static void addPatternColors(Map<String, Integer> colors) {
        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            patternColors.put(entry.getKey().toLowerCase(), entry.getValue());
        }
    }
}