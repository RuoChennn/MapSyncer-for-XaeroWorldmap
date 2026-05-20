package com.mapsyncer.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度路径映射管理器
 *
 * 统一管理维度的各种名称映射关系：
 * - ResourceLocation path (the_nether, the_end, overworld)
 * - 文件系统目录名（支持新旧两种格式）
 * - Xaero 目录名 (DIM-1, DIM1, null)
 *
 * Minecraft 26.1 (1.21.x) 维度路径格式变化：
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ Minecraft 26.1+ 新格式（所有维度统一使用 dimensions/ 目录）：        │
 * │   主世界: world/dimensions/minecraft/overworld/region/              │
 * │   地狱:   world/dimensions/minecraft/the_nether/region/             │
 * │   末地:   world/dimensions/minecraft/the_end/region/                │
 * │   Mod 维度: world/dimensions/<namespace>/<dimension_name>/region/   │
 * │   例如: world/dimensions/twilightforest/twilight_forest/region/     │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ 传统格式（旧版本 Minecraft 或部分 mod 使用）：                       │
 * │   主世界: world/region/                                             │
 * │   地狱:   world/DIM-1/region/                                       │
 * │   末地:   world/DIM1/region/                                        │
 * │   Mod 维度: world/DIM{id}/region/  例如 DIM7, DIM-17               │
 * └─────────────────────────────────────────────────────────────────────┤
 *
 * 本类同时支持新旧两种格式，优先检测新格式，找不到时回退到传统格式。
 * 首次运行时会自动检测实际使用的格式并缓存到配置文件。
 *
 * 示例：
 * | ResourceLocation | 新格式目录 | 传统格式目录 | Xaero 目录 |
 * |------------------|-----------|-------------|------------|
 * | overworld        | dimensions/minecraft/overworld | . | null |
 * | the_nether       | dimensions/minecraft/the_nether | DIM-1 | DIM-1 |
 * | the_end          | dimensions/minecraft/the_end | DIM1 | DIM1 |
 * | twilightforest:twilight_forest | dimensions/twilightforest/twilight_forest | DIM7 | DIM7 |
 */
public class DimensionPathMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPathMapping.class);

    // 单例实例
    private static volatile DimensionPathMapping instance;

    // ResourceLocation path → 文件系统目录名（运行时检测到的实际路径）
    private final Map<String, String> pathToFolder = new ConcurrentHashMap<>();

    // 文件系统目录名 → ResourceLocation path（反向映射）
    private final Map<String, String> folderToPath = new ConcurrentHashMap<>();

    // ResourceLocation path → Xaero 目录名
    private final Map<String, String> pathToXaero = new ConcurrentHashMap<>();

    // Xaero 目录名 → ResourceLocation path
    private final Map<String, String> xaeroToPath = new ConcurrentHashMap<>();

    // ========== 预设映射 ==========

    // 原版维度 - 新格式（Minecraft 26.1+）
    private static final Map<String, String> VANILLA_NEW_FORMAT = new LinkedHashMap<>();

    // 原版维度 - 传统格式
    private static final Map<String, String> VANILLA_LEGACY_FORMAT = new LinkedHashMap<>();

    // 原版维度 - Xaero 目录映射（固定）
    private static final Map<String, String> VANILLA_XAERO_MAPPINGS = new LinkedHashMap<>();

    // Mod 维度预设映射 - 已移除
// 原因：预设 DIM{id} 映射会导致新格式路径被强制转换为旧格式
// 现在统一使用动态检测的 namespace$path 格式（如 twilightforest$twilight_forest）
// private static final Map<String, String> MOD_LEGACY_MAPPINGS = new LinkedHashMap<>();
// private static final Map<String, String> MOD_XAERO_MAPPINGS = new LinkedHashMap<>();

    static {
        // 原版维度 - 新格式（26.1+）
        VANILLA_NEW_FORMAT.put("overworld", "dimensions/minecraft/overworld");
        VANILLA_NEW_FORMAT.put("minecraft:overworld", "dimensions/minecraft/overworld");
        VANILLA_NEW_FORMAT.put("the_nether", "dimensions/minecraft/the_nether");
        VANILLA_NEW_FORMAT.put("minecraft:the_nether", "dimensions/minecraft/the_nether");
        VANILLA_NEW_FORMAT.put("the_end", "dimensions/minecraft/the_end");
        VANILLA_NEW_FORMAT.put("minecraft:the_end", "dimensions/minecraft/the_end");

        // 原版维度 - 传统格式
        VANILLA_LEGACY_FORMAT.put("overworld", ".");
        VANILLA_LEGACY_FORMAT.put("minecraft:overworld", ".");
        VANILLA_LEGACY_FORMAT.put("the_nether", "DIM-1");
        VANILLA_LEGACY_FORMAT.put("minecraft:the_nether", "DIM-1");
        VANILLA_LEGACY_FORMAT.put("the_end", "DIM1");
        VANILLA_LEGACY_FORMAT.put("minecraft:the_end", "DIM1");

        // Xaero 目录映射 - 原版维度（固定格式）
        VANILLA_XAERO_MAPPINGS.put("overworld", "null");
        VANILLA_XAERO_MAPPINGS.put("minecraft:overworld", "null");
        VANILLA_XAERO_MAPPINGS.put("the_nether", "DIM-1");
        VANILLA_XAERO_MAPPINGS.put("minecraft:the_nether", "DIM-1");
        VANILLA_XAERO_MAPPINGS.put("the_end", "DIM1");
        VANILLA_XAERO_MAPPINGS.put("minecraft:the_end", "DIM1");

        // Mod 维度不再预设 DIM{id} 映射
        // 统一使用动态检测的 namespace$path 格式
        // 例如：twilightforest:twilight_forest → twilightforest$twilight_forest
    }

    private DimensionPathMapping() {
        // 初始化 Xaero 映射（仅原版维度）
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);

        // 构建反向映射
        rebuildReverseMappings();

        LOGGER.info("DimensionPathMapping initialized with {} Xaero mappings", pathToXaero.size());
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

    // ========== 文件系统目录检测 ==========

    /**
     * 检测维度的实际 region 目录路径
     *
     * 优先检测新格式（dimensions/...），找不到时回退到传统格式。
     * 检测结果会被缓存到 pathToFolder 映射中。
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

        // 2. 尝试新格式（dimensions/<namespace>/<path>/region）
        String newFormatFolder = getNewFormatFolder(normalized);
        Path newRegionDir = resolveRegionDir(worldRoot, newFormatFolder);
        if (Files.exists(newRegionDir)) {
            LOGGER.info("Detected new format for dimension {}: {}", normalized, newFormatFolder);
            pathToFolder.put(normalized, newFormatFolder);
            rebuildReverseMappings();
            return newRegionDir;
        }

        // 3. 尝试传统格式
        String legacyFolder = getLegacyFormatFolder(normalized);
        Path legacyRegionDir = resolveRegionDir(worldRoot, legacyFolder);
        if (Files.exists(legacyRegionDir)) {
            LOGGER.info("Detected legacy format for dimension {}: {}", normalized, legacyFolder);
            pathToFolder.put(normalized, legacyFolder);
            rebuildReverseMappings();
            return legacyRegionDir;
        }

        // 不再使用预设 DIM{id} 映射，统一依赖动态检测

        LOGGER.warn("Could not detect region directory for dimension: {}", normalized);
        return null;
    }

    /**
     * 根据文件夹名解析 region 目录路径
     */
    private Path resolveRegionDir(Path worldRoot, String folder) {
        if (folder == null || folder.isEmpty() || ".".equals(folder)) {
            return worldRoot.resolve("region");
        }
        return worldRoot.resolve(folder).resolve("region");
    }

    /**
     * 获取新格式目录名（dimensions/<namespace>/<path>）
     */
    private String getNewFormatFolder(String dimPath) {
        // 原版维度
        if (VANILLA_NEW_FORMAT.containsKey(dimPath)) {
            return VANILLA_NEW_FORMAT.get(dimPath);
        }

        // 带命名空间的维度（如 minecraft:overworld）
        if (dimPath.contains(":")) {
            String[] parts = dimPath.split(":");
            if (parts.length == 2) {
                return "dimensions/" + parts[0] + "/" + parts[1];
            }
        }

        // 无命名空间的维度，假设为 minecraft
        return "dimensions/minecraft/" + dimPath;
    }

    /**
     * 获取传统格式目录名（DIM-1, DIM1, .）
     */
    private String getLegacyFormatFolder(String dimPath) {
        // 原版维度
        if (VANILLA_LEGACY_FORMAT.containsKey(dimPath)) {
            return VANILLA_LEGACY_FORMAT.get(dimPath);
        }

        // Mod 维度不再预设 DIM{id} 映射
        // 返回 null（表示没有传统格式，依赖动态检测）

        return null;
    }

    // ========== 文件系统目录映射 ==========

    /**
     * 根据维度 ResourceLocation path 获取文件系统目录名
     *
     * 如果已检测并缓存，返回缓存值；
     * 否则返回新格式作为默认值。
     *
     * @param dimPath 维度 path（如 "the_nether", "my_mod:custom_dim"）
     * @return 文件系统目录名
     */
    public String getFolderName(String dimPath) {
        // 检查已缓存的映射
        String cached = pathToFolder.get(dimPath);
        if (cached != null) {
            return cached;
        }

        // 未缓存时返回新格式作为默认
        return getNewFormatFolder(normalizeDimPath(dimPath));
    }

    /**
     * 根据维度 ResourceKey 获取文件系统目录名
     */
    public String getFolderName(ResourceKey<Level> dimensionKey) {
        return getFolderName(dimensionKey.location().getPath());
    }

    /**
     * 根据文件系统目录名获取 ResourceLocation path
     */
    public String getPathFromFolder(String folderName) {
        String registered = folderToPath.get(folderName);
        if (registered != null) {
            return registered;
        }

        // 新格式：dimensions/<namespace>/<path> → namespace:path
        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring(11);
            String[] parts = remaining.split("/");
            if (parts.length == 2) {
                return parts[0] + ":" + parts[1];
            }
            return remaining;
        }

        // 传统格式反向映射
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
     * 优先级：
     * 1. 已检测并注册的映射（新格式优先）
     * 2. 原版维度预设映射
     * 3. Mod 预设映射
     * 4. 自动计算（namespace$path 格式）
     */
    public String getXaeroFolder(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        // 优先检查已注册的 Xaero 映射（可能来自自动检测）
        String registered = pathToXaero.get(normalized);
        if (registered != null) {
            return registered;
        }

        // 检查已检测到的文件系统路径
        // 如果检测到新格式路径，则计算对应的 namespace$path 格式
        String detectedFolder = pathToFolder.get(normalized);
        if (detectedFolder != null) {
            return computeXaeroFolderFromFolderName(normalized, detectedFolder);
        }

        // 原版维度预设映射
        String vanillaXaero = VANILLA_XAERO_MAPPINGS.get(normalized);
        if (vanillaXaero != null) {
            return vanillaXaero;
        }

        // Mod 维度不再预设 DIM{id} 映射
        // 直接使用 namespace$path 格式（Xaero 新格式）

        // 无预设时，使用 namespace$path 格式（Xaero 新格式）
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                return parts[0] + "$" + parts[1]; // Xaero 兼容格式
            }
        }

        return normalized;
    }

    /**
     * 根据 Xaero 目录名获取 ResourceLocation path
     */
    public String getPathFromXaero(String xaeroFolder) {
        String registered = xaeroToPath.get(xaeroFolder);
        if (registered != null) {
            return registered;
        }

        // Xaero 特殊值
        if ("null".equals(xaeroFolder)) return "overworld";
        if ("DIM-1".equals(xaeroFolder)) return "the_nether";
        if ("DIM1".equals(xaeroFolder)) return "the_end";

        // Xaero 兼容格式：namespace$path → namespace:path
        if (xaeroFolder.contains("$")) {
            return xaeroFolder.replace('$', ':');
        }

        return xaeroFolder;
    }

    // ========== 双向转换（客户端 ↔ 服务端）==========

    /**
     * 将客户端维度名转换为服务端格式
     */
    public String toServerDimension(String clientDim) {
        if (clientDim == null || clientDim.isEmpty()) {
            return "overworld";
        }

        String normalized = normalizeDimPath(clientDim);

        // Xaero 格式转换
        if ("null".equals(normalized)) return "overworld";
        if ("DIM-1".equals(normalized)) return "the_nether";
        if ("DIM1".equals(normalized)) return "the_end";

        // 从 Xaero 目录名反向查找
        String fromXaero = xaeroToPath.get(normalized);
        if (fromXaero != null) {
            return fromXaero;
        }

        return normalized;
    }

    /**
     * 将服务端维度名转换为 Xaero 格式
     *
     * 输入可能是以下格式：
     * 1. 完整维度 ID：如 "twilightforest:twilight_forest"
     * 2. 维度 path：如 "twilight_forest"
     * 3. 已经是 Xaero 格式：如 "twilightforest$twilight_forest" 或 "DIM-1"
     *
     * 如果输入已经是 Xaero 格式，直接返回。
     */
    public String toXaeroDimension(String serverDim) {
        if (serverDim == null || serverDim.isEmpty()) {
            return "null";
        }

        // 检查是否已经是 Xaero 格式
        // Xaero 格式特征：
        // - 原版：null, DIM-1, DIM1
        // - Mod 新格式：namespace$path（包含 $ 符号）
        // - Mod 传统格式：DIM{id}（如 DIM7, DIM-17）
        if (serverDim.equals("null") || serverDim.equals("DIM-1") || serverDim.equals("DIM1")) {
            return serverDim; // 原版 Xaero 格式，直接返回
        }
        if (serverDim.contains("$")) {
            return serverDim; // Mod 新格式 namespace$path，直接返回
        }
        if (serverDim.startsWith("DIM") || serverDim.startsWith("DIM-")) {
            return serverDim; // Mod 传统格式 DIM{id}，直接返回
        }

        // 不是 Xaero 格式，需要转换
        return getXaeroFolder(normalizeDimPath(serverDim));
    }

    /**
     * 获取用户友好的维度显示名称
     */
    public String getFriendlyName(String dimPath) {
        return normalizeDimPath(dimPath);
    }

    public String getFriendlyName(ResourceKey<Level> dimensionKey) {
        return getFriendlyName(dimensionKey.location().getPath());
    }

    // ========== 辅助方法 ==========

    /**
     * 标准化维度 path（移除 minecraft: 前缀）
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
     * @param dimPath 维度 path（如 "twilightforest:twilight_forest"）
     * @param folderName 文件系统目录名（如 "dimensions/twilightforest/twilight_forest" 或 "DIM7"）
     * @param xaeroFolder Xaero 目录名（如 "twilightforest$twilight_forest" 或 "DIM7"）
     */
    public void registerMapping(String dimPath, String folderName, String xaeroFolder) {
        pathToFolder.put(dimPath, folderName);
        pathToXaero.put(dimPath, xaeroFolder);
        rebuildReverseMappings();
        LOGGER.info("Registered dimension mapping: {} → folder={}, xaero={}", dimPath, folderName, xaeroFolder);
    }

    /**
     * 注册维度路径映射（自动计算 Xaero 目录名）
     *
     * 当检测到新格式路径时，使用 namespace$path 格式作为 Xaero 目录名；
     * 当检测到传统格式路径时，使用 DIM{id} 格式或预设值。
     */
    public void registerMapping(String dimPath, String folderName) {
        String xaeroFolder = computeXaeroFolderFromFolderName(dimPath, folderName);
        registerMapping(dimPath, folderName, xaeroFolder);
    }

    /**
     * 根据文件系统目录名计算正确的 Xaero 目录名
     *
     * 关键逻辑：
     * - 新格式（dimensions/...）：始终使用 namespace$path 格式，忽略预设映射
     * - 传统格式（DIM{id}）：使用预设映射或 DIM{id} 本身
     *
     * 这确保了当 mod 使用新规范路径时，Xaero 目录名也同步使用新格式，
     * 与客户端期望的路径一致。
     */
    private String computeXaeroFolderFromFolderName(String dimPath, String folderName) {
        // 新格式路径：dimensions/<namespace>/<path>
        // → 始终使用 namespace$path 格式作为 Xaero 目录名（忽略预设映射）
        // 这是关键修复：当 mod 使用新规范路径时，不再使用旧的 DIM{id} 格式
        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring(11); // 移除 "dimensions/"
            String[] parts = remaining.split("/");
            if (parts.length == 2) {
                String namespace = parts[0];
                String path = parts[1];
                String newXaeroFormat = namespace + "$" + path; // Xaero 新格式：namespace$path
                LOGGER.debug("New format detected for {}: using Xaero folder {} (instead of preset)",
                    dimPath, newXaeroFormat);
                return newXaeroFormat;
            }
        }

        // 传统格式路径：不再检查预设映射
        // 如果文件夹名已经是 DIM 格式，直接使用
        if (folderName.startsWith("DIM") || folderName.startsWith("DIM-")) {
            return folderName;
        }

        // 默认：使用路径部分作为 Xaero 目录名
        return getXaeroFolder(dimPath);
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
     * 清除所有检测到的映射（重置为初始状态）
     */
    public void clearDetectedMappings() {
        pathToFolder.clear();
        rebuildReverseMappings();
        LOGGER.info("Cleared all detected dimension mappings");
    }

    /**
     * 获取所有已注册的映射
     */
    public Map<String, String> getAllFolderMappings() {
        return new HashMap<>(pathToFolder);
    }

    public Map<String, String> getAllXaeroMappings() {
        return new HashMap<>(pathToXaero);
    }

    // ========== 自动搜索方法 ==========

    /**
     * 自动搜索维度 region 目录（兼容新旧两种格式）
     */
    public Path autoSearchRegionDir(Path worldRoot, String dimId) {
        return detectRegionDir(worldRoot, dimId);
    }

    /**
     * 扫描世界目录并自动注册所有发现的维度映射
     */
    public int scanAndRegisterDimensions(Path worldRoot) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return 0;
        }

        int newCount = 0;
        try {
            // 1. 扫描 dimensions/ 目录（新格式）
            Path dimensionsDir = worldRoot.resolve("dimensions");
            if (Files.exists(dimensionsDir)) {
                Files.list(dimensionsDir)
                    .filter(Files::isDirectory)
                    .forEach(namespaceDir -> {
                        String namespace = namespaceDir.getFileName().toString();
                        try {
                            Files.list(namespaceDir)
                                .filter(Files::isDirectory)
                                .forEach(dimDir -> {
                                    String dimName = dimDir.getFileName().toString();
                                    Path regionDir = dimDir.resolve("region");
                                    if (Files.exists(regionDir)) {
                                        String dimPath = namespace + ":" + dimName;
                                        if (!pathToFolder.containsKey(dimPath)) {
                                            registerMapping(dimPath, "dimensions/" + namespace + "/" + dimName);
                                            LOGGER.info("Auto-registered dimension (new format): {} → dimensions/{}/{}", dimPath, namespace, dimName);
                                        }
                                    }
                                });
                        } catch (Exception e) {
                            LOGGER.warn("Error scanning namespace directory: {}", namespace, e);
                        }
                    });
            }

            // 2. 扫描 DIM{id} 格式目录（传统格式）
            Files.list(worldRoot)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    String dirName = dir.getFileName().toString();
                    if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
                        Path regionDir = dir.resolve("region");
                        if (Files.exists(regionDir)) {
                            String dimPath = parseDimFolderName(dirName);
                            if (dimPath != null && !pathToFolder.containsKey(dimPath)) {
                                registerMapping(dimPath, dirName);
                                LOGGER.info("Auto-registered dimension (legacy format): {} → {}", dimPath, dirName);
                            }
                        }
                    }
                });

            // 3. 检查主世界（region/ 或 dimensions/minecraft/overworld/region）
            Path overworldRegion = worldRoot.resolve("region");
            if (Files.exists(overworldRegion) && !pathToFolder.containsKey("overworld")) {
                registerMapping("overworld", ".");
                LOGGER.info("Auto-registered overworld (legacy format: region/)");
            }

        } catch (Exception e) {
            LOGGER.warn("Error scanning world directory: {}", e.getMessage());
        }

        return pathToFolder.size();
    }

    /**
     * 解析 DIM 文件夹名称为维度 path
     */
    private String parseDimFolderName(String folderName) {
        // 原版维度
        if ("DIM-1".equals(folderName)) return "the_nether";
        if ("DIM1".equals(folderName)) return "the_end";
        if (".".equals(folderName) || "region".equals(folderName)) return "overworld";

        // DIM{id} 格式 → 尝试从反向映射查找
        String reverseMapped = folderToPath.get(folderName);
        if (reverseMapped != null) {
            return reverseMapped;
        }

        // 不再从预设映射查找
        // 未知的 DIM{id} 格式，返回原始
        if (folderName.startsWith("DIM")) {
            return folderName;
        }

        return folderName;
    }

    /**
     * 获取预设的 Mod 维度映射列表（已移除）
     * @deprecated 预设映射已清理，返回空 Map
     */
    @Deprecated
    public static Map<String, String> getModPresets() {
        return new LinkedHashMap<>();
    }

    /**
     * 获取所有检测到的维度映射（用于保存到配置文件）
     */
    public Map<String, String> getDetectedMappingsForConfig() {
        return new LinkedHashMap<>(pathToFolder);
    }
}