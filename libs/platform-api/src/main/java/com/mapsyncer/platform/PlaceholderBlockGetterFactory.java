package com.mapsyncer.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * PlaceholderBlockGetter 工厂类
 *
 * <p>提供 BlockGetter 接口的占位实现，用于调用需要 BlockGetter 参数的方块 API 方法。</p>
 *
 * <p>使用 Java 动态代理实现，避免 platform-api 模块对 Minecraft 的直接依赖。</p>
 *
 * <p>返回的 BlockGetter 实现：</p>
 * <ul>
 *   <li>getBlockEntity() → null</li>
 *   <li>getBlockState() → Blocks.AIR.defaultBlockState()</li>
 *   <li>getFluidState() → Fluids.EMPTY.defaultFluidState()</li>
 *   <li>getHeight() → 256</li>
 *   <li>getMinBuildHeight() → -64</li>
 * </ul>
 */
public final class PlaceholderBlockGetterFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaceholderBlockGetterFactory.class);

    /** 缓存的 BlockGetter 类（延迟加载） */
    private static volatile Class<?> blockGetterClass;

    /** 缓存的 Blocks 类（延迟加载） */
    private static volatile Class<?> blocksClass;

    /** 缓存的 Fluids 类（延迟加载） */
    private static volatile Class<?> fluidsClass;

    /** 缓存的空气方块默认状态 */
    private static volatile Object airDefaultState;

    /** 缓存的空流体默认状态 */
    private static volatile Object emptyFluidState;

    /** 单例 BlockGetter 实例 */
    private static volatile Object instance;

    /**
     * 私有构造方法，防止实例化
     */
    private PlaceholderBlockGetterFactory() {}

    /**
     * 获取 PlaceholderBlockGetter 单例实例
     *
     * <p>首次调用时通过反射创建动态代理实例，后续调用返回缓存实例。</p>
     *
     * @return BlockGetter 占位实例
     */
    public static Object getInstance() {
        if (instance == null) {
            synchronized (PlaceholderBlockGetterFactory.class) {
                if (instance == null) {
                    instance = createProxyInstance();
                }
            }
        }
        return instance;
    }

    /**
     * 创建动态代理实例
     *
     * @return BlockGetter 代理实例
     */
    private static Object createProxyInstance() {
        try {
            // 延迟加载 Minecraft 类
            initializeClasses();

            if (blockGetterClass == null) {
                LOGGER.warn("BlockGetter class not found, returning null proxy");
                return null;
            }

            // 创建 InvocationHandler
            InvocationHandler handler = new PlaceholderBlockGetterHandler();

            // 创建动态代理
            return Proxy.newProxyInstance(
                blockGetterClass.getClassLoader(),
                new Class<?>[] { blockGetterClass },
                handler
            );

        } catch (Exception e) {
            LOGGER.error("Failed to create PlaceholderBlockGetter proxy", e);
            return null;
        }
    }

    /**
     * 延迟加载 Minecraft 类
     *
     * <p>在首次使用时加载 BlockGetter、Blocks、Fluids 类。</p>
     */
    private static void initializeClasses() {
        if (blockGetterClass != null) return;

        try {
            // 加载 BlockGetter 接口
            blockGetterClass = Class.forName("net.minecraft.world.level.BlockGetter");

            // 加载 Blocks 类
            blocksClass = Class.forName("net.minecraft.world.level.block.Blocks");

            // 加载 Fluids 类
            fluidsClass = Class.forName("net.minecraft.world.level.material.Fluids");

            // 获取 AIR 方块的默认状态
            Object airBlock = blocksClass.getField("AIR").get(null);
            Method defaultBlockStateMethod = airBlock.getClass().getMethod("defaultBlockState");
            airDefaultState = defaultBlockStateMethod.invoke(airBlock);

            // 获取 EMPTY 流体的默认状态
            Object emptyFluid = fluidsClass.getField("EMPTY").get(null);
            Method defaultFluidStateMethod = emptyFluid.getClass().getMethod("defaultFluidState");
            emptyFluidState = defaultFluidStateMethod.invoke(emptyFluid);

            LOGGER.debug("PlaceholderBlockGetter classes initialized successfully");

        } catch (ClassNotFoundException e) {
            LOGGER.warn("Minecraft classes not found, PlaceholderBlockGetter will not work: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize PlaceholderBlockGetter classes", e);
        }
    }

    /**
     * InvocationHandler 实现
     *
     * <p>处理 BlockGetter 接口的所有方法调用，返回预定义的占位值。</p>
     */
    private static class PlaceholderBlockGetterHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            switch (methodName) {
                case "getBlockEntity":
                    // 返回 null（无方块实体）
                    return null;

                case "getBlockState":
                    // 返回 AIR 方块的默认状态
                    return airDefaultState;

                case "getFluidState":
                    // 返回 EMPTY 流体的默认状态
                    return emptyFluidState;

                case "getHeight":
                    // 返回世界高度
                    return 256;

                case "getMinBuildHeight":
                    // 返回最小建筑高度
                    return -64;

                case "toString":
                    return "PlaceholderBlockGetter";

                case "hashCode":
                    return System.identityHashCode(proxy);

                case "equals":
                    return proxy == args[0];

                default:
                    LOGGER.warn("Unknown BlockGetter method called: {}", methodName);
                    return null;
            }
        }
    }

    /**
     * 清除缓存
     *
     * <p>用于测试或重新初始化。</p>
     */
    public static void clearCache() {
        blockGetterClass = null;
        blocksClass = null;
        fluidsClass = null;
        airDefaultState = null;
        emptyFluidState = null;
        instance = null;
        LOGGER.debug("PlaceholderBlockGetter cache cleared");
    }

    /**
     * 检查是否已初始化
     *
     * @return true 表示已成功初始化
     */
    public static boolean isInitialized() {
        return instance != null;
    }
}