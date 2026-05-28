package com.mapsyncer.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Xaero 反射辅助类
 *
 * <p>封装所有与 Xaero's World Map 交互的反射逻辑，提供统一的 API。</p>
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>集中管理反射缓存，避免重复反射开销</li>
 *   <li>隔离 Xaero API 变化，便于维护和测试</li>
 *   <li>提供清晰的错误处理和日志记录</li>
 * </ul>
 *
 * <p>使用前必须调用 {@link #initialize()} 进行初始化。</p>
 */
public final class XaeroReflectionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroReflectionHelper.class);

    // ========== Xaero MapRegion loadState 常量 ==========
    // 基于 Xaero MapRegion.java 源码分析：
    // - loadState < 2: 显示加载动画
    // - loadState >= 2: 已加载状态
    // - loadState == 3: 正在处理卸载
    // - loadState == 4: 已清除完毕

    /**
     * 未加载状态 - 区域尚未开始加载
     */
    public static final byte LOAD_STATE_UNLOADED = 0;

    /**
     * 正在加载状态 - 显示加载动画，数据准备中
     */
    public static final byte LOAD_STATE_LOADING = 1;

    /**
     * 已加载状态 - 数据已准备好，可以显示
     */
    public static final byte LOAD_STATE_LOADED = 2;

    /**
     * 正在处理状态 - 准备卸载，处理后将被清除
     */
    public static final byte LOAD_STATE_PROCESSING = 3;

    /**
     * 已清除状态 - 处理结束，所有资源已释放
     */
    public static final byte LOAD_STATE_CLEARED = 4;

    // 反射缓存状态
    private static volatile boolean initialized = false;

    // Xaero 类缓存
    private static Class<?> worldMapSessionClass;
    private static Class<?> mapProcessorClass;
    private static Class<?> mapSaveLoadClass;
    private static Class<?> mapRegionClass;
    private static Class<?> leveledRegionClass;
    private static Class<?> mapWorldClass;
    private static Class<?> mapDimensionClass;
    private static Class<?> layeredRegionManagerClass;
    private static Class<?> mapLayerClass;
    private static Class<?> leveledRegionManagerClass;
    private static Class<?> branchLeveledRegionClass;

    // 反射方法缓存
    private static Method getCurrentSessionMethod;
    private static Method getMapProcessorMethod;
    private static Method getMapSaveLoadMethod;
    private static Method getLeafMapRegionMethod;
    private static Method requestLoadMethod;
    private static Method cancelRefreshMethod;
    private static Method setHasHadTerrainMethod;
    private static Method setRegionDetectionCompleteMethod;
    private static Method getMapWorldMethod;
    private static Method getCurrentDimensionMethod;
    private static Method getLayeredMapRegionsMethod;
    private static Method getLayerMethod;
    private static Method getMapRegionsMethod;

    // 反射字段缓存
    private static Field loadStateField;
    private static Field shouldCacheField;
    private static Field worldIdField;
    private static Field dimIdField;
    private static Field mwIdField;
    private static Field regionXField;
    private static Field regionZField;
    private static Field regionTextureMapField;
    private static Field childrenField;

    // 运行时对象缓存
    private static Object cachedSession;
    private static Object cachedMapProcessor;
    private static Object cachedMapSaveLoad;

    /**
     * 私有构造方法，防止实例化
     */
    private XaeroReflectionHelper() {}

    /**
     * 初始化反射缓存
     *
     * <p>必须在客户端环境中调用，且 Xaero's World Map 已安装。</p>
     *
     * @return true 表示初始化成功
     */
    public static boolean initialize() {
        if (initialized) return true;

        try {
            // 加载 Xaero 类
            worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");
            mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            layeredRegionManagerClass = Class.forName("xaero.map.region.LayeredRegionManager");
            mapLayerClass = Class.forName("xaero.map.region.MapLayer");
            leveledRegionManagerClass = Class.forName("xaero.map.region.LeveledRegionManager");
            branchLeveledRegionClass = Class.forName("xaero.map.region.BranchLeveledRegion");

            // 缓存方法
            getCurrentSessionMethod = worldMapSessionClass.getMethod("getCurrentSession");
            getMapProcessorMethod = worldMapSessionClass.getMethod("getMapProcessor");
            getMapSaveLoadMethod = mapProcessorClass.getMethod("getMapSaveLoad");
            getLeafMapRegionMethod = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            requestLoadMethod = mapSaveLoadClass.getMethod("requestLoad", mapRegionClass, String.class, boolean.class);
            cancelRefreshMethod = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);
            setHasHadTerrainMethod = mapRegionClass.getMethod("setHasHadTerrain");
            setRegionDetectionCompleteMethod = mapSaveLoadClass.getMethod("setRegionDetectionComplete", boolean.class);
            getMapWorldMethod = mapProcessorClass.getMethod("getMapWorld");
            getCurrentDimensionMethod = mapWorldClass.getMethod("getCurrentDimension");
            getLayeredMapRegionsMethod = mapDimensionClass.getMethod("getLayeredMapRegions");
            getLayerMethod = layeredRegionManagerClass.getMethod("getLayer", int.class);
            getMapRegionsMethod = mapLayerClass.getMethod("getMapRegions");

            // 缓存字段
            loadStateField = mapRegionClass.getDeclaredField("loadState");
            loadStateField.setAccessible(true);
            shouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            shouldCacheField.setAccessible(true);
            worldIdField = leveledRegionClass.getDeclaredField("worldId");
            worldIdField.setAccessible(true);
            dimIdField = leveledRegionClass.getDeclaredField("dimId");
            dimIdField.setAccessible(true);
            mwIdField = leveledRegionClass.getDeclaredField("mwId");
            mwIdField.setAccessible(true);
            regionXField = mapRegionClass.getDeclaredField("regionX");
            regionXField.setAccessible(true);
            regionZField = mapRegionClass.getDeclaredField("regionZ");
            regionZField.setAccessible(true);
            regionTextureMapField = leveledRegionManagerClass.getDeclaredField("regionTextureMap");
            regionTextureMapField.setAccessible(true);
            childrenField = branchLeveledRegionClass.getDeclaredField("children");
            childrenField.setAccessible(true);

            initialized = true;
            LOGGER.info("Xaero reflection helper initialized successfully");
            return true;

        } catch (ClassNotFoundException e) {
            LOGGER.warn("Xaero's World Map not found, reflection disabled: {}", e.getMessage());
            return false;
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            LOGGER.error("Xaero API incompatible, reflection initialization failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Xaero reflection helper", e);
            return false;
        }
    }

    /**
     * 获取 WorldMapSession 实例
     *
     * @return WorldMapSession 实例，获取失败返回 null
     */
    public static Object getSession() {
        if (!initialized || getCurrentSessionMethod == null) return null;

        try {
            cachedSession = getCurrentSessionMethod.invoke(null);
            return cachedSession;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Failed to get WorldMapSession: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 MapProcessor 实例
     *
     * @return MapProcessor 实例，获取失败返回 null
     */
    public static Object getMapProcessor() {
        if (!initialized) return null;

        try {
            if (cachedMapProcessor == null) {
                Object session = getSession();
                if (session == null) return null;
                cachedMapProcessor = getMapProcessorMethod.invoke(session);
            }
            return cachedMapProcessor;
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapProcessor: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 MapSaveLoad 实例
     *
     * @return MapSaveLoad 实例，获取失败返回 null
     */
    public static Object getMapSaveLoad() {
        if (!initialized) return null;

        try {
            if (cachedMapSaveLoad == null) {
                Object processor = getMapProcessor();
                if (processor == null) return null;
                cachedMapSaveLoad = getMapSaveLoadMethod.invoke(processor);
            }
            return cachedMapSaveLoad;
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapSaveLoad: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取或创建 MapRegion
     *
     * @param caveLayer 洞穴层编号（地表层使用 Integer.MAX_VALUE）
     * @param regionX 区域 X 坐标
     * @param regionZ 区域 Z 坐标
     * @param createIfMissing 如果不存在是否创建
     * @return MapRegion 实例，获取失败返回 null
     */
    public static Object getLeafMapRegion(int caveLayer, int regionX, int regionZ, boolean createIfMissing) {
        if (!initialized || getLeafMapRegionMethod == null) return null;

        try {
            Object processor = getMapProcessor();
            if (processor == null) return null;
            return getLeafMapRegionMethod.invoke(processor, caveLayer, regionX, regionZ, createIfMissing);
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapRegion ({}, {}) layer={}: {}", regionX, regionZ, caveLayer, e.getMessage());
            return null;
        }
    }

    /**
     * 设置 regionDetectionComplete 标志
     *
     * <p>关键设置，否则 getLeafMapRegion 会返回 null</p>
     *
     * @param value 标志值
     */
    public static void setRegionDetectionComplete(boolean value) {
        if (!initialized || setRegionDetectionCompleteMethod == null) return;

        try {
            Object saveLoad = getMapSaveLoad();
            if (saveLoad == null) return;
            setRegionDetectionCompleteMethod.invoke(saveLoad, value);
            LOGGER.debug("Set regionDetectionComplete={}", value);
        } catch (Exception e) {
            LOGGER.warn("Failed to set regionDetectionComplete: {}", e.getMessage());
        }
    }

    /**
     * 请求加载区域
     *
     * @param mapRegion MapRegion 实例
     * @param reason 加载原因（用于日志）
     * @param prioritize 是否优先加载（插入队头）
     */
    public static void requestLoad(Object mapRegion, String reason, boolean prioritize) {
        if (!initialized || requestLoadMethod == null) return;

        try {
            Object saveLoad = getMapSaveLoad();
            if (saveLoad == null) return;
            requestLoadMethod.invoke(saveLoad, mapRegion, reason, prioritize);
        } catch (Exception e) {
            LOGGER.warn("Failed to request load for region: {}", e.getMessage());
        }
    }

    /**
     * 取消区域刷新
     *
     * @param mapRegion MapRegion 实例
     */
    public static void cancelRefresh(Object mapRegion) {
        if (!initialized || cancelRefreshMethod == null) return;

        try {
            Object processor = getMapProcessor();
            if (processor == null) return;
            cancelRefreshMethod.invoke(mapRegion, processor);
        } catch (Exception e) {
            LOGGER.warn("Failed to cancel refresh for region: {}", e.getMessage());
        }
    }

    /**
     * 设置 loadState 字段
     *
     * @param mapRegion MapRegion 实例
     * @param state 状态值（使用 LOAD_STATE_* 常量）
     */
    public static void setLoadState(Object mapRegion, byte state) {
        if (!initialized || loadStateField == null) return;

        try {
            loadStateField.setByte(mapRegion, state);
        } catch (Exception e) {
            LOGGER.warn("Failed to set loadState: {}", e.getMessage());
        }
    }

    /**
     * 设置 shouldCache 字段
     *
     * @param mapRegion MapRegion 实例
     * @param value 是否缓存
     */
    public static void setShouldCache(Object mapRegion, boolean value) {
        if (!initialized || shouldCacheField == null) return;

        try {
            shouldCacheField.setBoolean(mapRegion, value);
        } catch (Exception e) {
            LOGGER.warn("Failed to set shouldCache: {}", e.getMessage());
        }
    }

    /**
     * 调用 setHasHadTerrain 方法
     *
     * <p>关键设置，否则加载时会跳过完整数据加载</p>
     *
     * @param mapRegion MapRegion 实例
     */
    public static void setHasHadTerrain(Object mapRegion) {
        if (!initialized || setHasHadTerrainMethod == null) return;

        try {
            setHasHadTerrainMethod.invoke(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to set hasHadTerrain: {}", e.getMessage());
        }
    }

    /**
     * 获取区域的 worldId
     *
     * @param mapRegion MapRegion 实例
     * @return worldId 字符串
     */
    public static String getWorldId(Object mapRegion) {
        if (!initialized || worldIdField == null) return null;

        try {
            return (String) worldIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get worldId: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取区域的 dimId
     *
     * @param mapRegion MapRegion 实例
     * @return dimId 字符串
     */
    public static String getDimId(Object mapRegion) {
        if (!initialized || dimIdField == null) return null;

        try {
            return (String) dimIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get dimId: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取区域的 mwId
     *
     * @param mapRegion MapRegion 实例
     * @return mwId 字符串
     */
    public static String getMwId(Object mapRegion) {
        if (!initialized || mwIdField == null) return null;

        try {
            return (String) mwIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get mwId: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查是否已初始化
     *
     * @return true 表示已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 清除缓存
     *
     * <p>在同步完成或离开服务器时调用</p>
     */
    public static void clearCache() {
        initialized = false;
        cachedSession = null;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        LOGGER.info("Xaero reflection cache cleared");
    }

    /**
     * 准备区域加载
     *
     * <p>一次性完成所有准备工作：取消刷新、设置缓存标志、设置地形标志</p>
     *
     * @param mapRegion MapRegion 实例
     */
    public static void prepareRegionLoad(Object mapRegion) {
        cancelRefresh(mapRegion);
        setShouldCache(mapRegion, true);
        setHasHadTerrain(mapRegion);
    }

    // ========== MapWorld/MapDimension 相关方法 ==========

    /**
     * 获取 MapWorld 实例
     *
     * @return MapWorld 实例，获取失败返回 null
     */
    public static Object getMapWorld() {
        if (!initialized || getMapWorldMethod == null) return null;

        try {
            Object processor = getMapProcessor();
            if (processor == null) return null;
            return getMapWorldMethod.invoke(processor);
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapWorld: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取当前维度实例
     *
     * @return MapDimension 实例，获取失败返回 null
     */
    public static Object getCurrentMapDimension() {
        if (!initialized || getCurrentDimensionMethod == null) return null;

        try {
            Object mapWorld = getMapWorld();
            if (mapWorld == null) return null;
            return getCurrentDimensionMethod.invoke(mapWorld);
        } catch (Exception e) {
            LOGGER.warn("Failed to get current dimension: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 LayeredRegionManager 实例
     *
     * @return LayeredRegionManager 实例，获取失败返回 null
     */
    public static Object getLayeredMapRegions() {
        if (!initialized || getLayeredMapRegionsMethod == null) return null;

        try {
            Object dimension = getCurrentMapDimension();
            if (dimension == null) return null;
            return getLayeredMapRegionsMethod.invoke(dimension);
        } catch (Exception e) {
            LOGGER.warn("Failed to get LayeredRegionManager: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取指定层的 MapLayer 实例
     *
     * @param layer 层编号（地表层使用 Integer.MAX_VALUE）
     * @return MapLayer 实例，获取失败返回 null
     */
    public static Object getMapLayer(int layer) {
        if (!initialized || getLayerMethod == null) return null;

        try {
            Object layeredManager = getLayeredMapRegions();
            if (layeredManager == null) return null;
            return getLayerMethod.invoke(layeredManager, layer);
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapLayer for layer {}: {}", layer, e.getMessage());
            return null;
        }
    }

    /**
     * 获取 LeveledRegionManager 实例（从 MapLayer）
     *
     * @param layer 层编号（地表层使用 Integer.MAX_VALUE）
     * @return LeveledRegionManager 实例，获取失败返回 null
     */
    public static Object getLeveledRegionManager(int layer) {
        if (!initialized || getMapRegionsMethod == null) return null;

        try {
            Object mapLayer = getMapLayer(layer);
            if (mapLayer == null) return null;
            return getMapRegionsMethod.invoke(mapLayer);
        } catch (Exception e) {
            LOGGER.warn("Failed to get LeveledRegionManager for layer {}: {}", layer, e.getMessage());
            return null;
        }
    }

    /**
     * 获取区域纹理映射
     *
     * <p>返回的是一个二维 Map 结构：Map<Integer, Map<Integer, Object region></p>
     *
     * @param layer 层编号（地表层使用 Integer.MAX_VALUE）
     * @return regionTextureMap 实例，获取失败返回 null
     */
    public static Object getRegionTextureMap(int layer) {
        if (!initialized || regionTextureMapField == null) return null;

        try {
            Object leveledManager = getLeveledRegionManager(layer);
            if (leveledManager == null) return null;
            return regionTextureMapField.get(leveledManager);
        } catch (Exception e) {
            LOGGER.warn("Failed to get regionTextureMap for layer {}: {}", layer, e.getMessage());
            return null;
        }
    }

    // ========== 区域坐标相关方法 ==========

    /**
     * 获取区域的 X 坐标
     *
     * @param mapRegion MapRegion 实例
     * @return 区域 X 坐标，获取失败返回 -1
     */
    public static int getRegionX(Object mapRegion) {
        if (!initialized || regionXField == null) return -1;

        try {
            return regionXField.getInt(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get regionX: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 获取区域的 Z 坐标
     *
     * @param mapRegion MapRegion 实例
     * @return 区域 Z 坐标，获取失败返回 -1
     */
    public static int getRegionZ(Object mapRegion) {
        if (!initialized || regionZField == null) return -1;

        try {
            return regionZField.getInt(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get regionZ: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 获取区域的加载状态
     *
     * @param mapRegion MapRegion 实例
     * @return loadState 值，获取失败返回 -1
     */
    public static byte getLoadState(Object mapRegion) {
        if (!initialized || loadStateField == null) return -1;

        try {
            return loadStateField.getByte(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get loadState: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 获取 BranchLeveledRegion 的 children 数组
     *
     * @param branchRegion BranchLeveledRegion 实例
     * @return children 二维数组，获取失败返回 null
     */
    public static Object getBranchChildren(Object branchRegion) {
        if (!initialized || childrenField == null) return null;

        try {
            return childrenField.get(branchRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get children: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查对象是否是 MapRegion 类
     *
     * @param obj 要检查的对象
     * @return true 如果是 MapRegion 类
     */
    public static boolean isMapRegion(Object obj) {
        return obj != null && obj.getClass() == mapRegionClass;
    }

    /**
     * 检查对象是否是 BranchLeveledRegion 类
     *
     * @param obj 要检查的对象
     * @return true 如果是 BranchLeveledRegion 类
     */
    public static boolean isBranchLeveledRegion(Object obj) {
        return obj != null && obj.getClass() == branchLeveledRegionClass;
    }
}