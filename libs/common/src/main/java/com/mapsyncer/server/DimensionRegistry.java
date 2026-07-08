package com.mapsyncer.server;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.LayerPlan;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.DimensionTypeHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
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
 * 4. 所有维度类型信息从运行中的服务器动态获取，无硬编码预设
 */
public class DimensionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionRegistry.class);

    /** 是否已执行过首次注册 */
    private static volatile boolean hasRegistered = false;

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
        List<? extends String> currentConfigs = PlatformManager.getPlatform().getDimensionConfigs();

        // 解析为 DimensionScanConfig 对象便于匹配
        Set<String> configuredDimensions = new HashSet<>();
        for (DimensionScanConfig config : PlatformManager.getPlatform().parseDimensionConfigs()) {
            configuredDimensions.add(normalizeDimensionId(config.dimension()));
        }

        LOGGER.info("Currently configured dimensions: {}", configuredDimensions);

        // 扫描服务器所有已加载维度
        Set<String> newDimensions = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimKey = level.dimension();
            String dimId = DimensionApiHelper.getDimId(dimKey);

            String normalizedId = normalizeDimensionId(dimId);

            if (!configuredDimensions.contains(normalizedId)) {
                // 该维度未配置，需要添加
                newDimensions.add(dimId);
                LOGGER.debug("Found unconfigured dimension: {} (normalized: {})", dimId, normalizedId);
            }
        }

        if (newDimensions.isEmpty()) {
            LOGGER.info("All dimensions already configured, no updates needed");
            hasRegistered = true;
            return;
        }

        // 创建新的配置列表（保留原有配置 + 新增配置）
        List<String> updatedConfigs = new ArrayList<>(currentConfigs);

        // 添加新发现的维度，所有维度类型信息从服务器动态获取
        for (String dimId : newDimensions) {
            // 从 ServerLevel 获取真实的维度类型信息
            ServerLevel level = getLevelForDimension(server, dimId);
            DimensionTypeInfo dimTypeInfo;
            if (level != null) {
                dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
                LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
                    dimId, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
                    dimTypeInfo.minY(), dimTypeInfo.height());
            } else {
                // 无法获取维度信息，使用默认推断值
                dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimId);
            }

            // 使用默认地表模式和动态维度类型信息创建配置
            DimensionScanConfig finalConfig = new DimensionScanConfig(
                    dimId,
                    LayerPlan.surfaceOnly(),
                    dimTypeInfo
            );

            String configStr = configToString(finalConfig);
            updatedConfigs.add(configStr);
            LOGGER.info("Added dimension config: {} (layerPlan={}, hasSkylight={})",
                    dimId, finalConfig.layerPlan().toConfigString(), dimTypeInfo.hasSkylight());
        }

        // 更新配置值
        PlatformManager.getPlatform().setDimensionConfigs(updatedConfigs);

        // 保存配置文件
        PlatformManager.getPlatform().saveConfig();

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
            if (DimensionApiHelper.getDimId(level.dimension()).equals(dimId)) {
                return level;
            }
        }
        return null;
    }

    /**
     * 将DimensionScanConfig转换为字符串格式（用于配置文件）
     *
     * 格式：dimension|layerPlan
     * dim_type_info 不再写入配置文件；注册新维度时从运行中的 ServerLevel 动态获取
     *
     * @param config 维度扫描配置
     * @return 配置字符串
     */
    private static String configToString(DimensionScanConfig config) {
        return config.dimension() + "|" + config.layerPlan().toConfigString();
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
