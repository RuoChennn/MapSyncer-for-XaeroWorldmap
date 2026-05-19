package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.DimensionScanConfig;
import com.mapsyncer.config.ModConfig.ScanMode;
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
 * 维度注册器：在首次执行地图转换时自动检测维度路径并注册到配置文件
 *
 * 功能：
 * 1. 首次执行地图生成时扫描服务器所有已加载维度
 * 2. 自动检测维度使用的路径格式（新格式 dimensions/ 或传统格式 DIM）
 * 3. 将检测到的 region_folder 写入配置文件
 * 4. 对未配置的维度自动添加推荐配置（扫描模式等）
 *
 * Minecraft 26.1 路径格式支持：
 * - 新格式：dimensions/minecraft/overworld/region, dimensions/minecraft/the_nether/region
 * - 传统格式：region/, DIM-1/region/, DIM1/region/, DIM{id}/region/
 */
public class DimensionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionRegistry.class);

    // 是否已执行过首次注册
    private static volatile boolean hasRegistered = false;

    /**
     * 已知维度的推荐配置（系统预设）
     * 原版维度使用特定配置，mod 维度使用预设或默认地表模式
     */
    private static final Map<String, DimensionScanConfig> PRESET_CONFIGS = new LinkedHashMap<>();

    static {
        // 原版维度预设配置（region_folder 为空，由自动检测决定）
        PRESET_CONFIGS.put("minecraft:overworld",
                new DimensionScanConfig("minecraft:overworld", "", ScanMode.SURFACE, 63));
        PRESET_CONFIGS.put("minecraft:the_nether",
                new DimensionScanConfig("minecraft:the_nether", "", ScanMode.CAVE, 63));
        PRESET_CONFIGS.put("minecraft:the_end",
                new DimensionScanConfig("minecraft:the_end", "", ScanMode.SURFACE, 63));

        // Mod 维度预设配置
        // Twilight Forest: 地表模式（森林地形）
        PRESET_CONFIGS.put("twilightforest:twilight_forest",
                new DimensionScanConfig("twilightforest:twilight_forest", "", ScanMode.SURFACE, 63));

        // Aether: 天空维度，使用地表模式
        PRESET_CONFIGS.put("aether:the_aether",
                new DimensionScanConfig("aether:the_aether", "", ScanMode.SURFACE, 63));

        // Betweenlands: 地下沼泽维度，可能需要洞穴模式
        PRESET_CONFIGS.put("thebetweenlands:betweenlands",
                new DimensionScanConfig("thebetweenlands:betweenlands", "", ScanMode.CAVE, 32));

        // Erebus: 昆虫洞穴维度，使用洞穴模式
        PRESET_CONFIGS.put("erebus:erebus",
                new DimensionScanConfig("erebus:erebus", "", ScanMode.CAVE, 32));
    }

    /**
     * 在首次执行地图转换时注册所有维度到配置文件
     *
     * 自动检测每个维度的实际路径格式并写入配置文件。
     * 只在首次执行时运行，后续调用会跳过。
     *
     * @param server MinecraftServer 实例
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

        // 添加新发现的维度（使用检测到的 region_folder）
        for (String dimId : newDimensions) {
            // 检测实际的 region_folder
            String detectedFolder = detectRegionFolder(worldRoot, dimId);

            // 获取推荐配置（扫描模式等）
            DimensionScanConfig preset = getRecommendedConfig(dimId);

            // 使用检测到的路径替换预设的 region_folder
            DimensionScanConfig finalConfig = new DimensionScanConfig(
                    dimId,
                    detectedFolder,
                    preset.scanMode(),
                    preset.caveStart()
            );

            String configStr = configToString(finalConfig);
            updatedConfigs.add(configStr);
            LOGGER.info("Added dimension config: {} (region_folder={}, scan_mode={})",
                    dimId, detectedFolder, finalConfig.scanMode());
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
     * 检测维度的实际 region_folder
     *
     * 自动检测维度使用的是新格式（dimensions/）还是传统格式（DIM）
     *
     * @param worldRoot 世界根目录
     * @param dimId 维度 ID（如 "minecraft:overworld", "twilightforest:twilight_forest"）
     * @return 检测到的 region_folder（如 "dimensions/minecraft/overworld", "DIM-1", ""）
     */
    private static String detectRegionFolder(Path worldRoot, String dimId) {
        DimensionPathMapping mapping = DimensionPathMapping.getInstance();

        // 使用 DimensionPathMapping 的检测方法
        Path regionDir = mapping.detectRegionDir(worldRoot, dimId);

        if (regionDir != null) {
            // 从检测到的路径提取 region_folder
            String detectedFolder = mapping.getFolderName(dimId.replace("minecraft:", ""));
            LOGGER.info("Detected region_folder for {}: {}", dimId, detectedFolder);
            return detectedFolder;
        }

        // 无法检测，返回空（使用默认 Minecraft 路径）
        LOGGER.warn("Could not detect region_folder for {}, using default", dimId);
        return "";
    }

    /**
     * 规范化维度 ID（移除 minecraft: 前缀，转小写）
     */
    private static String normalizeDimensionId(String dimId) {
        return dimId.replace("minecraft:", "").toLowerCase();
    }

    /**
     * 获取维度的推荐配置（扫描模式等）
     * region_folder 由 detectRegionFolder() 决定，不使用预设值
     */
    private static DimensionScanConfig getRecommendedConfig(String dimId) {
        // 检查是否有预设配置（扫描模式）
        for (Map.Entry<String, DimensionScanConfig> entry : PRESET_CONFIGS.entrySet()) {
            if (normalizeDimensionId(entry.getKey()).equals(normalizeDimensionId(dimId))) {
                return entry.getValue();
            }
        }

        // 非原版维度：使用默认地表模式
        return new DimensionScanConfig(dimId, "", ScanMode.SURFACE, 63);
    }

    /**
     * 将 DimensionScanConfig 转换为字符串格式（用于配置文件）
     * 格式：dimension|region_folder|scan_mode|cave_start
     */
    private static String configToString(DimensionScanConfig config) {
        return config.dimension() + "|" + config.regionFolder() + "|" + config.scanMode().name() + "|" + config.caveStart();
    }

    /**
     * 获取所有已配置维度的列表（用于命令建议）
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
     * 将维度 ID 转换为用户友好名称
     */
    private static String toFriendlyName(String dimId) {
        String normalized = normalizeDimensionId(dimId);
        switch (normalized) {
            case "overworld":
                return "overworld";
            case "the_nether":
                return "nether";
            case "the_end":
                return "end";
            default:
                return normalized;
        }
    }

    /**
     * 检查是否已注册过维度
     */
    public static boolean isRegistered() {
        return hasRegistered;
    }
}