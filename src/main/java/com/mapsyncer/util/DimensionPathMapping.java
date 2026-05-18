package com.mapsyncer.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度路径映射管理器
 *
 * 统一管理维度的各种名称映射关系：
 * - ResourceLocation path (the_nether, the_end, overworld)
 * - 文件系统目录名 (DIM-1, DIM1, .)
 * - Xaero 目录名 (DIM-1, DIM1, null)
 *
 * 支持：
 * - 原版维度的默认映射
 * - Mod 维度的动态注册
 * - 自定义映射覆盖
 * - 双向转换（服务端 ↔ Xaero 格式）
 *
 * 示例：
 * | ResourceLocation | 文件系统目录 | Xaero 目录 |
 * |------------------|-------------|------------|
 * | overworld        | .           | null       |
 * | the_nether       | DIM-1       | DIM-1      |
 * | the_end          | DIM1        | DIM1       |
 * | my_mod:custom    | my_mod$custom | my_mod$custom |
 */
public class DimensionPathMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPathMapping.class);

    // 单例实例
    private static volatile DimensionPathMapping instance;

    // ResourceLocation path → 文件系统目录名
    private final Map<String, String> pathToFolder = new ConcurrentHashMap<>();

    // 文件系统目录名 → ResourceLocation path（反向映射）
    private final Map<String, String> folderToPath = new ConcurrentHashMap<>();

    // ResourceLocation path → Xaero 目录名
    private final Map<String, String> pathToXaero = new ConcurrentHashMap<>();

    // Xaero 目录名 → ResourceLocation path
    private final Map<String, String> xaeroToPath = new ConcurrentHashMap<>();

    // 默认映射（原版维度）
    private static final Map<String, String> VANILLA_FOLDER_MAPPINGS = new HashMap<>();
    private static final Map<String, String> VANILLA_XAERO_MAPPINGS = new HashMap<>();

    static {
        // 文件系统目录映射
        VANILLA_FOLDER_MAPPINGS.put("the_nether", "DIM-1");
        VANILLA_FOLDER_MAPPINGS.put("the_end", "DIM1");
        VANILLA_FOLDER_MAPPINGS.put("overworld", ".");
        VANILLA_FOLDER_MAPPINGS.put(".", ".");

        // Xaero 目录映射
        VANILLA_XAERO_MAPPINGS.put("overworld", "null");
        VANILLA_XAERO_MAPPINGS.put(".", "null");
        VANILLA_XAERO_MAPPINGS.put("the_nether", "DIM-1");
        VANILLA_XAERO_MAPPINGS.put("the_end", "DIM1");
    }

    private DimensionPathMapping() {
        // 初始化原版维度映射
        pathToFolder.putAll(VANILLA_FOLDER_MAPPINGS);
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);

        // 构建反向映射
        rebuildReverseMappings();
    }

    private void rebuildReverseMappings() {
        folderToPath.clear();
        xaeroToPath.clear();

        for (Map.Entry<String, String> entry : pathToFolder.entrySet()) {
            folderToPath.put(entry.getValue(), entry.getKey());
        }

        for (Map.Entry<String, String> entry : pathToXaero.entrySet()) {
            xaeroToPath.put(entry.getValue(), entry.getKey());
        }
    }

    /**
     * 获取单例实例
     */
    public static DimensionPathMapping getInstance() {
        if (instance == null) {
            synchronized (DimensionPathMapping.class) {
                if (instance == null) {
                    instance = new DimensionPathMapping();
                }
            }
        }
        return instance;
    }

    /**
     * 重置单例实例（用于测试或重新加载配置）
     */
    public static void resetInstance() {
        synchronized (DimensionPathMapping.class) {
            instance = null;
        }
        LOGGER.info("DimensionPathMapping instance reset");
    }

    // ========== 文件系统目录映射 ==========

    /**
     * 根据维度 ResourceLocation path 获取文件系统目录名
     *
     * @param dimPath 维度 path（如 "the_nether", "my_mod:custom_dim"）
     * @return 文件系统目录名（如 "DIM-1", "my_mod$custom_dim"）
     */
    public String getFolderName(String dimPath) {
        // 检查已注册的映射
        String registered = pathToFolder.get(dimPath);
        if (registered != null) {
            return registered;
        }

        // 默认规则：将 namespace:path 转换为 namespace$path
        if (dimPath.contains(":")) {
            return dimPath.replace(':', '$');
        }

        return dimPath;
    }

    /**
     * 根据维度 ResourceKey 获取文件系统目录名
     *
     * @param dimensionKey 维度 ResourceKey
     * @return 文件系统目录名
     */
    public String getFolderName(ResourceKey<Level> dimensionKey) {
        // 原版维度快速映射
        if (dimensionKey == Level.NETHER) {
            return "DIM-1";
        }
        if (dimensionKey == Level.END) {
            return "DIM1";
        }
        if (dimensionKey == Level.OVERWORLD) {
            return ".";
        }
        return getFolderName(dimensionKey.location().getPath());
    }

    /**
     * 根据文件系统目录名获取 ResourceLocation path
     *
     * @param folderName 文件系统目录名（如 "DIM-1", "my_mod$custom_dim"）
     * @return ResourceLocation path
     */
    public String getPathFromFolder(String folderName) {
        String registered = folderToPath.get(folderName);
        if (registered != null) {
            return registered;
        }

        // 默认规则：将 namespace$path 转换为 namespace:path
        if (folderName.contains("$")) {
            return folderName.replace('$', ':');
        }

        return folderName;
    }

    // ========== Xaero 目录映射 ==========

    /**
     * 根据维度 ResourceLocation path 获取 Xaero 目录名
     *
     * @param dimPath 维度 path
     * @return Xaero 目录名（如 "null", "DIM-1", "DIM1"）
     */
    public String getXaeroFolder(String dimPath) {
        // 标准化输入
        String normalizedPath = normalizeDimPath(dimPath);

        String registered = pathToXaero.get(normalizedPath);
        if (registered != null) {
            return registered;
        }

        // 默认规则：与文件系统目录名相同
        return getFolderName(normalizedPath);
    }

    /**
     * 根据 Xaero 目录名获取 ResourceLocation path
     *
     * @param xaeroFolder Xaero 目录名
     * @return ResourceLocation path
     */
    public String getPathFromXaero(String xaeroFolder) {
        String registered = xaeroToPath.get(xaeroFolder);
        if (registered != null) {
            return registered;
        }

        // 默认规则：与文件系统目录名反向映射相同
        return getPathFromFolder(xaeroFolder);
    }

    // ========== 双向转换（客户端 ↔ 服务端）==========

    /**
     * 将客户端维度名转换为服务端格式
     *
     * 支持输入格式：
     * - Xaero 目录名: "null", "DIM-1", "DIM1"
     * - ResourceLocation: "minecraft:the_nether"
     * - 原始 path: "the_nether"
     *
     * @param clientDim 客户端维度名
     * @return 服务端维度 path（如 "overworld", "the_nether", "the_end"）
     */
    public String toServerDimension(String clientDim) {
        if (clientDim == null || clientDim.isEmpty()) {
            return "overworld";
        }

        String normalized = normalizeDimPath(clientDim);

        // Xaero 格式转换
        if ("null".equals(normalized)) {
            return "overworld";
        }
        if ("DIM-1".equals(normalized)) {
            return "the_nether";
        }
        if ("DIM1".equals(normalized)) {
            return "the_end";
        }

        // 直接匹配
        if (pathToFolder.containsKey(normalized)) {
            return normalized;
        }

        // 从 Xaero 目录名反向查找
        String fromXaero = xaeroToPath.get(normalized);
        if (fromXaero != null) {
            return fromXaero;
        }

        // 从文件系统目录名反向查找
        String fromFolder = folderToPath.get(normalized);
        if (fromFolder != null) {
            return fromFolder;
        }

        // 默认返回原始值（可能是自定义维度）
        return normalized;
    }

    /**
     * 将服务端维度名转换为 Xaero 格式
     *
     * @param serverDim 服务端维度 path（如 "the_nether", "overworld"）
     * @return Xaero 目录名（如 "DIM-1", "null"）
     */
    public String toXaeroDimension(String serverDim) {
        if (serverDim == null || serverDim.isEmpty()) {
            return "null";
        }

        String normalized = normalizeDimPath(serverDim);
        return getXaeroFolder(normalized);
    }

    /**
     * 获取用户友好的维度显示名称
     *
     * 用于命令建议和日志显示：
     * - 原版维度使用规范化名称 (the_nether, the_end, overworld)
     * - mod 维度移除 minecraft: 前缀，保持原始 path
     *
     * @param dimPath 维度 path 或完整 ResourceLocation
     * @return 用户友好的维度名称
     */
    public String getFriendlyName(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        // 移除 minecraft: 前缀，使用规范化名称
        // 原版维度: the_nether, the_end, overworld
        // mod 维度: 保持原始 path

        return normalized;
    }

    /**
     * 获取用户友好的维度显示名称
     *
     * @param dimensionKey 维度 ResourceKey
     * @return 用户友好的维度名称
     */
    public String getFriendlyName(ResourceKey<Level> dimensionKey) {
        return getFriendlyName(dimensionKey.location().getPath());
    }

    // ========== 辅助方法 ==========

    /**
     * 标准化维度 path
     *
     * 处理常见的输入格式变体：
     * - "minecraft:the_nether" → "the_nether"
     * - "minecraft:overworld" → "overworld"
     * - "null" → "overworld" (Xaero 特殊格式)
     */
    private String normalizeDimPath(String dimPath) {
        if (dimPath == null || dimPath.isEmpty()) {
            return "overworld";
        }

        // 移除 minecraft: 前缀
        if (dimPath.startsWith("minecraft:")) {
            dimPath = dimPath.substring(10);
        }

        // Xaero 的 null 表示主世界
        if ("null".equals(dimPath)) {
            return "overworld";
        }

        return dimPath;
    }

    /**
     * 检查是否为主世界
     */
    public boolean isOverworld(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "overworld".equals(normalized) || ".".equals(normalized);
    }

    /**
     * 检查是否为地狱
     */
    public boolean isNether(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_nether".equals(normalized) || "DIM-1".equals(normalized);
    }

    /**
     * 检查是否为末地
     */
    public boolean isEnd(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_end".equals(normalized) || "DIM1".equals(normalized);
    }

    /**
     * 获取 region 目录相对路径
     *
     * @param dimPath 维度 ResourceLocation path
     * @return region 目录相对路径（如 "DIM-1/region", "region"）
     */
    public String getRegionRelativePath(String dimPath) {
        String folder = getFolderName(dimPath);
        if (".".equals(folder)) {
            return "region";
        }
        return folder + "/region";
    }

    // ========== 注册方法 ==========

    /**
     * 注册维度路径映射
     *
     * @param dimPath 维度 ResourceLocation path
     * @param folderName 文件系统目录名
     * @param xaeroFolder Xaero 目录名
     */
    public void registerMapping(String dimPath, String folderName, String xaeroFolder) {
        pathToFolder.put(dimPath, folderName);
        pathToXaero.put(dimPath, xaeroFolder);
        rebuildReverseMappings();
        LOGGER.info("Registered dimension mapping: {} → folder={}, xaero={}", dimPath, folderName, xaeroFolder);
    }

    /**
     * 注册维度路径映射（Xaero 目录名与文件系统目录名相同）
     *
     * @param dimPath 维度 ResourceLocation path
     * @param folderName 文件系统目录名（也用作 Xaero 目录名）
     */
    public void registerMapping(String dimPath, String folderName) {
        registerMapping(dimPath, folderName, folderName);
    }

    /**
     * 移除映射
     */
    public void removeMapping(String dimPath) {
        pathToFolder.remove(dimPath);
        pathToXaero.remove(dimPath);
        rebuildReverseMappings();
        LOGGER.info("Removed dimension mapping for: {}", dimPath);
    }

    /**
     * 清除所有自定义映射（保留原版映射）
     */
    public void clearCustomMappings() {
        pathToFolder.clear();
        pathToXaero.clear();
        pathToFolder.putAll(VANILLA_FOLDER_MAPPINGS);
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);
        rebuildReverseMappings();
        LOGGER.info("Cleared all custom dimension mappings");
    }

    /**
     * 获取所有已注册的映射
     */
    public Map<String, String> getAllFolderMappings() {
        return new HashMap<>(pathToFolder);
    }

    /**
     * 获取所有 Xaero 映射
     */
    public Map<String, String> getAllXaeroMappings() {
        return new HashMap<>(pathToXaero);
    }
}