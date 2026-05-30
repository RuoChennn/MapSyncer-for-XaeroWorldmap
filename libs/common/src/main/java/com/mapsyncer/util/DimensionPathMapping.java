package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 维度路径映射管理器
 *
 * 统一管理维度的各种名称映射关系：
 * - ResourceLocation path (the_nether, the_end, overworld)
 * - 文件系统目录名
 * - Xaero 目录名 (DIM-1, DIM1, null)
 *
 * Minecraft 维度路径格式：
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ 1.21.X 及更早版本：                                                 │
 * │   原版维度（使用传统格式）：                                         │
 * │     主世界: world/region/                                           │
 * │     地狱:   world/DIM-1/region/                                     │
 * │     末地:   world/DIM1/region/                                      │
 * │   Mod 维度（使用 dimensions/ 新格式）：                             │
 * │     格式: world/dimensions/<namespace>/<dimension_name>/region/     │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ 26.1+ 版本（所有维度统一使用新格式）：                               │
 * │   格式: world/dimensions/<namespace>/<dimension_name>/region/       │
 * │   主世界: dimensions/minecraft/overworld/                           │
 * │   地狱:   dimensions/minecraft/the_nether/                          │
 * │   末地:   dimensions/minecraft/the_end/                             │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * 本类根据游戏版本和维度类型自动选择正确的路径格式。
 *
 * 示例（1.21.X）：
 * | ResourceLocation | 文件系统目录 | Xaero 目录 |
 * |------------------|-------------|------------|
 * | minecraft:overworld | . (根目录) | null |
 * | minecraft:the_nether | DIM-1 | DIM-1 |
 * | minecraft:the_end | DIM1 | DIM1 |
 * | twilightforest:twilight_forest | dimensions/twilightforest/twilight_forest | twilightforest$twilight_forest |
 *
 * 示例（26.1+）：
 * | ResourceLocation | 文件系统目录 | Xaero 目录 |
 * |------------------|-------------|------------|
 * | minecraft:overworld | dimensions/minecraft/overworld | null |
 * | minecraft:the_nether | dimensions/minecraft/the_nether | DIM-1 |
 * | minecraft:the_end | dimensions/minecraft/the_end | DIM1 |
 * | twilightforest:twilight_forest | dimensions/twilightforest/twilight_forest | twilightforest$twilight_forest |
 */
public class DimensionPathMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPathMapping.class);

    /** 26.1+ 版本开始统一使用新格式 */
    private static final int NEW_FORMAT_MIN_VERSION = 26;

    // 单例实例
    private static volatile DimensionPathMapping instance;

    // Minecraft 主版本号（1.20.x = 20, 1.21.x = 21, 26.x = 26）
    private int majorVersion = 21;

    // 是否对原版维度也使用新格式（26.1+ 为 true）
    private boolean useNewFormatForVanilla = false;

    // ResourceLocation path → 文件系统目录名（运行时检测到的实际路径）
    private final Map<String, String> pathToFolder = new ConcurrentHashMap<>();

    // ResourceLocation path → Xaero 目录名
    private final Map<String, String> pathToXaero = new ConcurrentHashMap<>();

    // ========== 预设映射 ==========

    // 原版维度 - 传统格式（1.21.X 原版维度使用此格式）
    private static final Map<String, String> VANILLA_LEGACY_FORMAT = new LinkedHashMap<>();

    // 原版维度 - 新格式（26.1+ 所有维度使用此格式）
    private static final Map<String, String> VANILLA_NEW_FORMAT = new LinkedHashMap<>();

    // 原版维度 - Xaero 目录映射（固定，与版本无关）
    private static final Map<String, String> VANILLA_XAERO_MAPPINGS = new LinkedHashMap<>();

    static {
        // 原版维度 - 传统格式（Minecraft 1.21.X 及更早）
        VANILLA_LEGACY_FORMAT.put("overworld", ".");
        VANILLA_LEGACY_FORMAT.put("the_nether", "DIM-1");
        VANILLA_LEGACY_FORMAT.put("the_end", "DIM1");

        // 原版维度 - 新格式（Minecraft 26.1+）
        VANILLA_NEW_FORMAT.put("overworld", "dimensions/minecraft/overworld");
        VANILLA_NEW_FORMAT.put("the_nether", "dimensions/minecraft/the_nether");
        VANILLA_NEW_FORMAT.put("the_end", "dimensions/minecraft/the_end");

        // Xaero 目录映射 - 原版维度（有待验证是否与版本无关）
        VANILLA_XAERO_MAPPINGS.put("overworld", "null");
        VANILLA_XAERO_MAPPINGS.put("the_nether", "DIM-1");
        VANILLA_XAERO_MAPPINGS.put("the_end", "DIM1");
    }

    /**
     * 私有构造方法，初始化默认映射
     */
    private DimensionPathMapping() {
        // 初始化 Xaero 映射（仅原版维度）
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);

        LOGGER.info("DimensionPathMapping initialized (version: {}, new format: {})",
                majorVersion, useNewFormatForVanilla);
    }

    /**
     * 初始化版本信息
     *
     * 应在 mod 初始化时调用此方法设置游戏版本。
     * 26.1+ 版本会对所有维度使用 dimensions/ 新格式。
     *
     * @param majorVersion Minecraft 主版本号（如 21 for 1.21.x, 26 for 26.x）
     */
    public void initialize(int majorVersion) {
        this.majorVersion = majorVersion;
        this.useNewFormatForVanilla = majorVersion >= NEW_FORMAT_MIN_VERSION;

        // 清除旧的文件夹映射，保留 Xaero 映射
        pathToFolder.clear();

        LOGGER.info("DimensionPathMapping version set to: {}, use new format for vanilla: {}",
                majorVersion, useNewFormatForVanilla);
    }

    /**
     * 获取当前配置的主版本号
     *
     * @return 主版本号
     */
    public int getMajorVersion() {
        return majorVersion;
    }

    /**
     * 是否对原版维度使用新格式
     *
     * @return true 如果是 26.1+ 版本
     */
    public boolean isUseNewFormatForVanilla() {
        return useNewFormatForVanilla;
    }

    /**
     * 获取单例实例
     *
     * @return DimensionPathMapping 单例实例
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
     *
     * @return void
     */
    public static void resetInstance() {
        synchronized (DimensionPathMapping.class) {
            instance = null;
        }
        LOGGER.info("DimensionPathMapping instance reset");
    }

    // ========== 文件系统目录检测 ==========

    /**
     * 检测维度的实际 region 目录路径
     *
     * 根据版本和维度类型选择正确的检测顺序：
     * - 1.21.X 原版维度：使用传统格式（region/, DIM-1/, DIM1/）
     * - 26.1+ 所有维度：统一使用 dimensions/<namespace>/<path>/ 格式
     *
     * @param worldRoot 世界根目录
     * @param dimPath 维度 path（如 "overworld", "the_nether", "twilightforest:twilight_forest"）
     * @return 找到的 region 目录路径，如果未找到返回 null
     */
    public Path detectRegionDir(Path worldRoot, String dimPath) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return null;
        }

        String normalized = normalizeDimPath(dimPath);

        // 1. 检查已缓存的映射
        String cachedFolder = pathToFolder.get(normalized);
        if (cachedFolder != null) {
            Path regionDir = resolveRegionDir(worldRoot, cachedFolder);
            if (Files.exists(regionDir)) {
                return regionDir;
            }
        }

        // 2. 26.1+ 版本：所有维度统一使用新格式
        if (useNewFormatForVanilla) {
            String newFormatFolder = buildNewFormatPath(normalized);
            if (newFormatFolder != null) {
                Path regionDir = resolveRegionDir(worldRoot, newFormatFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected dimension {} (26.1+ new format): {}", normalized, newFormatFolder);
                    pathToFolder.put(normalized, newFormatFolder);
                    return regionDir;
                }
            }
        }

        // 3. 1.21.X 原版维度：使用传统格式
        if (!useNewFormatForVanilla && isVanillaDimension(normalized)) {
            String legacyFolder = VANILLA_LEGACY_FORMAT.get(normalized);
            if (legacyFolder != null) {
                Path regionDir = resolveRegionDir(worldRoot, legacyFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected vanilla dimension {} (legacy format): {}", normalized, legacyFolder);
                    pathToFolder.put(normalized, legacyFolder);
                    return regionDir;
                }
            }
        }

        // 4. Mod 维度：尝试 dimensions/<namespace>/<path>/ 新格式
        if (normalized.contains(":")) {
            String newFormatFolder = buildNewFormatPath(normalized);
            if (newFormatFolder != null) {
                Path regionDir = resolveRegionDir(worldRoot, newFormatFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected Mod dimension {} (new format): {}", normalized, newFormatFolder);
                    pathToFolder.put(normalized, newFormatFolder);
                    return regionDir;
                }
            }
        }

        // 5. 备用：尝试传统 DIM{id} 格式（部分旧 mod 可能使用）
        // 动态检测，不预设映射

        LOGGER.warn("Could not detect region directory for dimension: {}", normalized);
        return null;
    }

    /**
     * 构建新格式路径
     *
     * @param normalized 标准化后的维度路径
     * @return 新格式路径，如果无法构建返回 null
     */
    private String buildNewFormatPath(String normalized) {
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                return "dimensions/" + parts[0] + "/" + parts[1];
            }
        }
        // 不带命名空间的维度，默认使用 minecraft
        if (!normalized.isEmpty()) {
            return "dimensions/minecraft/" + normalized;
        }
        return null;
    }

    /**
     * 判断是否为原版维度（仅判断三个原版维度）
     *
     * @param dimPath 维度路径（已标准化）
     * @return true 如果是原版维度，false 否则
     */
    private boolean isVanillaDimension(String dimPath) {
        return "overworld".equals(dimPath) || "the_nether".equals(dimPath) || "the_end".equals(dimPath);
    }

    /**
     * 根据文件夹名解析 region 目录路径
     *
     * @param worldRoot 世界根目录
     * @param folder 文件夹名（如 "."、"DIM-1"、"dimensions/minecraft/overworld"）
     * @return region 目录的完整路径
     */
    private Path resolveRegionDir(Path worldRoot, String folder) {
        if (folder == null || folder.isEmpty() || ".".equals(folder)) {
            return worldRoot.resolve("region");
        }
        return worldRoot.resolve(folder).resolve("region");
    }

    // ========== 文件系统目录映射 ==========

    /**
     * 根据维度 ResourceLocation path 获取文件系统目录名
     *
     * 根据版本选择格式：
     * - 26.1+：所有维度使用 dimensions/<namespace>/<path>/ 格式
     * - 1.21.X：原版维度传统格式，Mod 维度使用新格式
     *
     * @param dimPath 维度 path（如 "the_nether", "my_mod:custom_dim"）
     * @return 文件系统目录名
     */
    public String getFolderName(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        // 检查已缓存的映射（使用标准化后的 key）
        String cached = pathToFolder.get(normalized);
        if (cached != null) {
            return cached;
        }

        // 26.1+ 版本：所有维度统一使用新格式
        if (useNewFormatForVanilla) {
            String newFormat = buildNewFormatPath(normalized);
            if (newFormat != null) {
                return newFormat;
            }
        }

        // 1.21.X 原版维度：返回传统格式
        if (isVanillaDimension(normalized)) {
            String legacyFolder = VANILLA_LEGACY_FORMAT.get(normalized);
            return legacyFolder != null ? legacyFolder : ".";
        }

        // Mod 维度：返回 dimensions/<namespace>/<path> 新格式
        String newFormat = buildNewFormatPath(normalized);
        if (newFormat != null) {
            return newFormat;
        }

        // 默认返回根目录
        return ".";
    }

    /**
     * 根据文件系统目录名获取 ResourceLocation path
     *
     * @param folderName 文件系统目录名
     * @return 维度的 ResourceLocation path
     */
    public String getPathFromFolder(String folderName) {
        // 遍历正向映射查找
        for (Map.Entry<String, String> entry : pathToFolder.entrySet()) {
            if (entry.getValue().equals(folderName)) {
                return entry.getKey();
            }
        }

        // 新格式：dimensions/<namespace>/<path> → namespace:path
        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring("dimensions/".length());
            String[] parts = remaining.split("/");
            if (parts.length == 2) {
                return parts[0] + ":" + parts[1];
            }
            return remaining;
        }

        // 传统格式反向映射（1.21.X 原版维度）
        if (".".equals(folderName) || "region".equals(folderName)) return "overworld";
        if ("DIM-1".equals(folderName)) return "the_nether";
        if ("DIM1".equals(folderName)) return "the_end";

        // 兼容旧版本：namespace$path → namespace:path
        if (folderName.contains("$")) {
            return folderName.replace('$', ':');
        }

        return folderName;
    }

    // ========== Xaero 目录映射 ==========

    /**
     * 根据维度 ResourceLocation path 获取 Xaero 目录名
     *
     * 原版维度返回固定格式（null, DIM-1, DIM1），
     * Mod 维度返回 namespace$path 格式。
     *
     * @param dimPath 维度 ResourceLocation path
     * @return Xaero 目录名
     */
    public String getXaeroFolder(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        // 原版维度：使用固定映射
        String vanillaXaero = VANILLA_XAERO_MAPPINGS.get(normalized);
        if (vanillaXaero != null) {
            return vanillaXaero;
        }

        // 检查已注册的映射（Mod 维度动态检测）
        String registered = pathToXaero.get(normalized);
        if (registered != null) {
            return registered;
        }

        // Mod 维度：使用 namespace$path 格式
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                return parts[0] + "$" + parts[1];
            }
        }

        return normalized;
    }

    /**
     * 根据 Xaero 目录名获取 ResourceLocation path
     *
     * @param xaeroFolder Xaero 目录名
     * @return 维度的 ResourceLocation path
     */
    public String getPathFromXaero(String xaeroFolder) {
        // 遍历正向映射查找
        for (Map.Entry<String, String> entry : pathToXaero.entrySet()) {
            if (entry.getValue().equals(xaeroFolder)) {
                return entry.getKey();
            }
        }

        // Xaero 原版维度特殊值
        if ("null".equals(xaeroFolder)) return "overworld";
        if ("DIM-1".equals(xaeroFolder)) return "the_nether";
        if ("DIM1".equals(xaeroFolder)) return "the_end";

        // Mod 维度：namespace$path → namespace:path
        if (xaeroFolder.contains("$")) {
            return xaeroFolder.replace('$', ':');
        }

        return xaeroFolder;
    }

    // ========== 双向转换（客户端 ↔ 服务端）==========

    /**
     * 将客户端维度名转换为服务端格式
     *
     * @param clientDim 客户端维度名（可能是 Xaero 格式）
     * @return 服务端格式的维度名
     */
    public String toServerDimension(String clientDim) {
        if (clientDim == null || clientDim.isEmpty()) {
            return "overworld";
        }

        String normalized = normalizeDimPath(clientDim);

        // Xaero 原版维度转换
        if ("null".equals(normalized)) return "overworld";
        if ("DIM-1".equals(normalized)) return "the_nether";
        if ("DIM1".equals(normalized)) return "the_end";

        // 从 Xaero 目录名反向查找（遍历正向映射）
        for (Map.Entry<String, String> entry : pathToXaero.entrySet()) {
            if (entry.getValue().equals(normalized)) {
                return entry.getKey();
            }
        }

        return normalized;
    }

    /**
     * 将服务端维度名转换为 Xaero 格式
     *
     * @param serverDim 服务端维度名
     * @return Xaero 格式的维度名
     */
    public String toXaeroDimension(String serverDim) {
        if (serverDim == null || serverDim.isEmpty()) {
            return "null";
        }

        // 检查是否已经是 Xaero 格式
        if (serverDim.equals("null") || serverDim.equals("DIM-1") || serverDim.equals("DIM1")) {
            return serverDim;
        }
        if (serverDim.contains("$")) {
            return serverDim;
        }
        if (serverDim.startsWith("DIM")) {
            return serverDim;
        }

        // 转换为 Xaero 格式
        return getXaeroFolder(normalizeDimPath(serverDim));
    }

    /**
     * 获取用户友好的维度显示名称（namespace:path 格式）。
     *
     * <p>接受任何格式的输入（Xaero 目录名、文件系统目录名、ResourceLocation path），
     * 统一返回 {@code namespace:path} 格式。</p>
     *
     * @param dimPath 维度路径（支持 Xaero 格式如 null/DIM-1、文件系统格式、ResourceLocation 格式）
     * @return namespace:path 格式的维度名称（如 minecraft:overworld）
     */
    public String getFriendlyName(String dimPath) {
        String serverDim = toServerDimension(dimPath);
        String normalized = normalizeDimPath(serverDim);
        if (normalized.contains(":")) {
            return normalized;
        }
        return "minecraft:" + normalized;
    }

    // ========== 辅助方法 ==========

    /**
     * 标准化维度 path（移除 minecraft: 前缀）
     *
     * @param dimPath 原始维度路径
     * @return 标准化后的维度路径（不带 minecraft: 前缀）
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
     *
     * @param dimPath 维度路径
     * @return true 如果是主世界，false 否则
     */
    public boolean isOverworld(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "overworld".equals(normalized) || ".".equals(normalized);
    }

    /**
     * 检查是否为地狱
     *
     * @param dimPath 维度路径
     * @return true 如果是地狱，false 否则
     */
    public boolean isNether(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_nether".equals(normalized) || "DIM-1".equals(normalized);
    }

    /**
     * 检查是否为末地
     *
     * @param dimPath 维度路径
     * @return true 如果是末地，false 否则
     */
    public boolean isEnd(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_end".equals(normalized) || "DIM1".equals(normalized);
    }

    /**
     * 获取 region 目录相对路径
     *
     * @param dimPath 维度路径
     * @return region 目录的相对路径（如 "region" 或 "DIM-1/region"）
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
        LOGGER.info("Registered dimension mapping: {} -> folder={}, xaero={}", dimPath, folderName, xaeroFolder);
    }

    /**
     * 注册维度路径映射（自动计算 Xaero 目录名）
     *
     * @param dimPath 维度 ResourceLocation path
     * @param folderName 文件系统目录名
     */
    public void registerMapping(String dimPath, String folderName) {
        String xaeroFolder = computeXaeroFolderFromFolderName(dimPath, folderName);
        registerMapping(dimPath, folderName, xaeroFolder);
    }

    /**
     * 根据文件系统目录名计算正确的 Xaero 目录名
     *
     * @param dimPath 维度 ResourceLocation path
     * @param folderName 文件系统目录名
     * @return 计算得出的 Xaero 目录名
     */
    private String computeXaeroFolderFromFolderName(String dimPath, String folderName) {
        // 新格式路径：dimensions/<namespace>/<path>
        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring("dimensions/".length());
            String[] parts = remaining.split("/");
            if (parts.length == 2) {
                // 26.1+ 原版维度：使用 Xaero 预设映射
                String normalized = normalizeDimPath(dimPath);
                if ("minecraft".equals(parts[0]) && VANILLA_XAERO_MAPPINGS.containsKey(normalized)) {
                    return VANILLA_XAERO_MAPPINGS.get(normalized);
                }
                // Mod 维度：使用 namespace$path 格式
                return parts[0] + "$" + parts[1];
            }
        }

        // 传统格式 DIM{id}：直接使用
        if (folderName.startsWith("DIM") || ".".equals(folderName)) {
            // 原版维度使用预设映射
            String vanillaXaero = VANILLA_XAERO_MAPPINGS.get(normalizeDimPath(dimPath));
            if (vanillaXaero != null) {
                return vanillaXaero;
            }
            return folderName;
        }

        return getXaeroFolder(dimPath);
    }

    /**
     * 移除映射
     *
     * @param dimPath 要移除的维度 ResourceLocation path
     */
    public void removeMapping(String dimPath) {
        pathToFolder.remove(dimPath);
        pathToXaero.remove(dimPath);
        LOGGER.info("Removed dimension mapping for: {}", dimPath);
    }

    /**
     * 清除所有检测到的映射（重置为初始状态）
     *
     * @return void
     */
    public void clearDetectedMappings() {
        pathToFolder.clear();
        // 保留原版维度 Xaero 映射
        pathToXaero.clear();
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);
        LOGGER.info("Cleared all detected dimension mappings");
    }

    /**
     * 获取所有已注册的文件夹映射
     *
     * @return 文件夹映射的副本 Map
     */
    public Map<String, String> getAllFolderMappings() {
        return new HashMap<>(pathToFolder);
    }

    /**
     * 获取所有已注册的 Xaero 映射
     *
     * @return Xaero 映射的副本 Map
     */
    public Map<String, String> getAllXaeroMappings() {
        return new HashMap<>(pathToXaero);
    }

    // ========== 自动搜索方法 ==========

    /**
     * 自动搜索维度 region 目录
     *
     * @param worldRoot 世界根目录
     * @param dimId 维度 ID
     * @return 找到的 region 目录路径，如果未找到返回 null
     */
    public Path autoSearchRegionDir(Path worldRoot, String dimId) {
        return detectRegionDir(worldRoot, dimId);
    }

    /**
     * 扫描世界目录并自动注册所有发现的维度映射
     *
     * <p>使用try-with-resources确保Files.list返回的Stream被正确关闭。</p>
     *
     * @param worldRoot 世界根目录路径
     * @return 已注册的维度映射数量
     */
    public int scanAndRegisterDimensions(Path worldRoot) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return 0;
        }

        try {
            // 1. 扫描 dimensions/ 目录（新格式）
            Path dimensionsDir = worldRoot.resolve("dimensions");
            if (Files.exists(dimensionsDir)) {
                try (Stream<Path> namespaceStream = Files.list(dimensionsDir)) {
                    namespaceStream.filter(Files::isDirectory)
                        .forEach(namespaceDir -> {
                            String namespace = namespaceDir.getFileName().toString();

                            // 1.21.X：跳过 minecraft 命名空间（原版维度使用传统格式）
                            // 26.1+：扫描 minecraft 命名空间（所有维度使用新格式）
                            if ("minecraft".equals(namespace) && !useNewFormatForVanilla) {
                                LOGGER.debug("Skipping minecraft namespace in dimensions/ (1.21.X vanilla dims use traditional format)");
                                return;
                            }

                            try (Stream<Path> dimStream = Files.list(namespaceDir)) {
                                dimStream.filter(Files::isDirectory)
                                    .forEach(dimDir -> {
                                        String dimName = dimDir.getFileName().toString();
                                        Path regionDir = dimDir.resolve("region");
                                        if (Files.exists(regionDir)) {
                                            String dimPath = namespace + ":" + dimName;
                                            if (!pathToFolder.containsKey(dimPath)) {
                                                String folderPath = "dimensions/" + namespace + "/" + dimName;
                                                registerMapping(dimPath, folderPath);
                                                LOGGER.info("Auto-registered dimension: {} -> {}", dimPath, folderPath);
                                            }
                                        }
                                    });
                            } catch (IOException e) {
                                LOGGER.warn("Error scanning namespace directory: {}", namespace, e);
                            }
                        });
                }
            }

            // 2. 1.21.X：扫描 DIM{id} 格式目录（传统格式）
            if (!useNewFormatForVanilla) {
                try (Stream<Path> rootStream = Files.list(worldRoot)) {
                    rootStream.filter(Files::isDirectory)
                        .forEach(dir -> {
                            String dirName = dir.getFileName().toString();
                            if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
                                // 跳过原版维度（已在预设映射中）
                                if ("DIM-1".equals(dirName) || "DIM1".equals(dirName)) {
                                    return;
                                }
                                Path regionDir = dir.resolve("region");
                                if (Files.exists(regionDir)) {
                                    // 未知 DIM{id} 格式，记录但不注册（无法确定维度 ID）
                                    LOGGER.info("Found unknown DIM directory: {} (cannot determine dimension ID)", dirName);
                                }
                            }
                        });
                }

                // 3. 1.21.X：检查主世界（region/ 目录）
                Path overworldRegion = worldRoot.resolve("region");
                if (Files.exists(overworldRegion)) {
                    pathToFolder.put("overworld", ".");
                    LOGGER.info("Confirmed overworld using legacy format: region/");
                }
            }

        } catch (IOException e) {
            LOGGER.warn("Error scanning world directory: {}", e.getMessage());
        }

        return pathToFolder.size();
    }

    /**
     * 获取所有检测到的维度映射（用于保存到配置文件）
     *
     * @return 维度映射的副本 Map
     */
    public Map<String, String> getDetectedMappingsForConfig() {
        return new LinkedHashMap<>(pathToFolder);
    }
}
