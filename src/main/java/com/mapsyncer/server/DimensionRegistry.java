package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.DimensionScanConfig;
import com.mapsyncer.config.ModConfig.ScanMode;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * 维度注册器 - 在首次执行地图转换时自动检测维度路径并注册到配置文件
 *
 * 功能：
 * 1. 首次执行地图生成时扫描服务器所有已加载维度
 * 2. 自动检测维度使用的路径格式（新格式 dimensions/ 或传统格式 DIM）
 * 3. 对未配置的维度自动添加推荐配置（扫描模式等）
 *
 * Minecraft 1.21+ 路径格式：
 * - 新格式：dimensions/minecraft/overworld/region, dimensions/minecraft/the_nether/region
 * - 传统格式：region/, DIM-1/region/, DIM1/region/, DIM{id}/region/
 */
public class DimensionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionRegistry.class);

    /** 是否已执行过首次注册 */
    private static volatile boolean hasRegistered = false;

    /**
     * 已知维度的推荐配置（系统预设）
     *
     * 原版维度使用特定配置，mod维度使用预设或默认地表模式。
     * 包含维度类型信息用于离线解析时的光照计算和高度范围确定。
     */
    private static final Map<String, DimensionScanConfig> PRESET_CONFIGS = new LinkedHashMap<>();

    static {
        // 原版维度预设配置（1.21+ 自动检测路径）
        // 主世界：地表模式，有天空光照，minY=-64, height=384
        PRESET_CONFIGS.put("minecraft:overworld",
                new DimensionScanConfig("minecraft:overworld", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.overworld()));

        // 地狱：洞穴模式，无天空光照，有顶棚，minY=0, height=256
        PRESET_CONFIGS.put("minecraft:the_nether",
                new DimensionScanConfig("minecraft:the_nether", ScanMode.CAVE, 63,
                    DimensionTypeInfo.nether()));

        // 末地：地表模式，无天空光照，无顶棚，minY=0, height=256
        PRESET_CONFIGS.put("minecraft:the_end",
                new DimensionScanConfig("minecraft:the_end", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.theEnd()));

        // Mod维度预设配置
        // Twilight Forest: 地表模式（森林地形），类似主世界
        PRESET_CONFIGS.put("twilightforest:twilight_forest",
                new DimensionScanConfig("twilightforest:twilight_forest", ScanMode.SURFACE, 63,
                    new DimensionTypeInfo(true, false, 0, 256, 256)));

        // Aether: 天空维度，使用地表模式
        PRESET_CONFIGS.put("aether:the_aether",
                new DimensionScanConfig("aether:the_aether", ScanMode.SURFACE, 63,
                    new DimensionTypeInfo(true, false, 0, 256, 256)));

        // Betweenlands: 地下沼泽维度，可能需要洞穴模式
        PRESET_CONFIGS.put("thebetweenlands:betweenlands",
                new DimensionScanConfig("thebetweenlands:betweenlands", ScanMode.CAVE, 32,
                    new DimensionTypeInfo(false, true, 0, 256, 256)));

        // Erebus: 昆虫洞穴维度，使用洞穴模式
        PRESET_CONFIGS.put("erebus:erebus",
                new DimensionScanConfig("erebus:erebus", ScanMode.CAVE, 32,
                    new DimensionTypeInfo(false, true, 0, 256, 256)));
    }

    /**
     * 在首次执行地图转换时注册所有维度到配置文件
     *
     * 自动检测每个维度的实际路径格式并写入配置文件。
     * 只在首次执行时运行，后续调用会跳过。
     *
     * @param server MinecraftServer实例
     */
    public static void registerAllDimensions(MinecraftServer server) {
        // 防止重复注册
        if (hasRegistered) {
            LOGGER.debug("Dimensions already registered, skipping");
            return;
        }

        LOGGER.info("Starting dimension registration on first map generation...");

        // 获取世界根目录
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);

        // 使用 DimensionPathMapping 扫描并注册所有维度路径
        DimensionPathMapping mapping = DimensionPathMapping.getInstance();
        mapping.scanAndRegisterDimensions(worldRoot);

        // 获取当前配置列表
        ConfigValue<List<? extends String>> configValue = ModConfig.SERVER.dimensionConfigs;
        List<? extends String> currentConfigs = configValue.get();

        // 解析为 DimensionScanConfig 对象便于匹配
        Set<String> configuredDimensions = new HashSet<>();
        for (DimensionScanConfig config : ModConfig.SERVER.parseDimensionConfigs()) {
            configuredDimensions.add(normalizeDimensionId(config.dimension()));
        }

        LOGGER.info("Currently configured dimensions: {}", configuredDimensions);

        // 扫描服务器所有已加载维度
        Set<String> newDimensions = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimKey = level.dimension();
            String dimId = dimKey.location().toString();

            String normalizedId = normalizeDimensionId(dimId);

            if (!configuredDimensions.contains(normalizedId)) {
                // 该维度未配置，需要添加
                newDimensions.add(dimId);
                LOGGER.info("Found unconfigured dimension: {} (normalized: {})", dimId, normalizedId);
            }
        }

        if (newDimensions.isEmpty()) {
            LOGGER.info("All dimensions already configured, no updates needed");
            hasRegistered = true;
            return;
        }

        // 创建新的配置列表（保留原有配置 + 新增配置）
        List<String> updatedConfigs = new ArrayList<>(currentConfigs);

        // 添加新发现的维度（1.21+ 自动检测路径）
        for (String dimId : newDimensions) {
            // 获取推荐配置（扫描模式等）
            DimensionScanConfig preset = getRecommendedConfig(dimId);

            // 从 ServerLevel 获取真实的维度类型信息
            ServerLevel level = getLevelForDimension(server, dimId);
            DimensionTypeInfo dimTypeInfo;
            if (level != null) {
                dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());
                LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
                    dimId, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
                    dimTypeInfo.minY(), dimTypeInfo.height());
            } else {
                // 无法获取维度信息，使用预设或推断值
                dimTypeInfo = preset.dimTypeInfo() != null ? preset.dimTypeInfo() : DimensionTypeInfo.fromDimensionId(dimId);
            }

            // 使用推荐配置和维度类型信息创建最终配置
            DimensionScanConfig finalConfig = new DimensionScanConfig(
                    dimId,
                    preset.scanMode(),
                    preset.caveStart(),
                    dimTypeInfo
            );

            String configStr = configToString(finalConfig);
            updatedConfigs.add(configStr);
            LOGGER.info("Added dimension config: {} (scan_mode={}, hasSkylight={})",
                    dimId, finalConfig.scanMode(), dimTypeInfo.hasSkylight());
        }

        // 更新配置值
        configValue.set(updatedConfigs);

        // 保存配置文件
        ModConfig.SERVER_SPEC.save();

        hasRegistered = true;
        LOGGER.info("Dimension registration completed: {} new dimensions added, total {} dimensions configured",
                newDimensions.size(), updatedConfigs.size());
    }

    /**
     * 重置注册状态（用于测试或重新扫描）
     */
    public static void resetRegistration() {
        hasRegistered = false;
        DimensionPathMapping.resetInstance();
        LOGGER.info("Dimension registration state reset");
    }

    /**
     * 规范化维度ID（移除minecraft:前缀，转小写）
     *
     * @param dimId 维度ID
     * @return 规范化后的维度ID
     */
    private static String normalizeDimensionId(String dimId) {
        return dimId.replace("minecraft:", "").toLowerCase();
    }

    /**
     * 获取维度的ServerLevel实例
     *
     * @param server MinecraftServer实例
     * @param dimId 维度ID（如"minecraft:overworld"）
     * @return ServerLevel实例，未找到返回null
     */
    private static ServerLevel getLevelForDimension(MinecraftServer server, String dimId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimId)) {
                return level;
            }
        }
        return null;
    }

    /**
     * 获取维度的推荐配置（扫描模式等）
     *
     * @param dimId 维度ID
     * @return 推荐的维度扫描配置
     */
    private static DimensionScanConfig getRecommendedConfig(String dimId) {
        // 检查是否有预设配置（扫描模式和维度类型信息）
        for (Map.Entry<String, DimensionScanConfig> entry : PRESET_CONFIGS.entrySet()) {
            if (normalizeDimensionId(entry.getKey()).equals(normalizeDimensionId(dimId))) {
                return entry.getValue();
            }
        }

        // 非原版维度：使用默认地表模式，维度类型信息自动推断
        return new DimensionScanConfig(dimId, ScanMode.SURFACE, 63,
            DimensionTypeInfo.fromDimensionId(dimId));
    }

    /**
     * 将DimensionScanConfig转换为字符串格式（用于配置文件）
     *
     * 格式：dimension|scan_mode|cave_start|dim_type_info
     * dim_type_info格式：hasSkylight|hasCeiling|minY|height|logicalHeight
     *
     * @param config 维度扫描配置
     * @return 配置字符串
     */
    private static String configToString(DimensionScanConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.dimension());
        sb.append("|").append(config.scanMode().name());
        sb.append("|").append(config.caveStart());

        // 添加维度类型信息
        if (config.dimTypeInfo() != null) {
            sb.append("|").append(config.dimTypeInfo().toConfigString());
        }

        return sb.toString();
    }

    /**
     * 获取所有已配置维度的列表（用于命令建议）
     *
     * @return 维度友好名称列表
     */
    public static List<String> getConfiguredDimensionNames() {
        List<String> names = new ArrayList<>();
        for (DimensionScanConfig config : ModConfig.SERVER.parseDimensionConfigs()) {
            String friendlyName = toFriendlyName(config.dimension());
            names.add(friendlyName);
        }
        return names;
    }

    /**
     * 将维度ID转换为用户友好名称（使用标准名称）
     *
     * @param dimId 维度ID
     * @return 用户友好的维度名称
     */
    private static String toFriendlyName(String dimId) {
        String normalized = normalizeDimensionId(dimId);
        // 直接返回标准名称（移除 minecraft: 前缀）
        return normalized;
    }

    /**
     * 检查是否已注册过维度
     *
     * @return true表示已注册，false表示未注册
     */
    public static boolean isRegistered() {
        return hasRegistered;
    }
}