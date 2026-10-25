package com.mapsyncer.platform;

import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** 房主本地增量失效适配器。Xaero 未安装/接口不兼容时由调用者停用，不影响访客收包链路。 */
public final class XaeroHostMapRefresh {
    private static final String REASON = "MapSyncer host source changed";
    private static final ClassValue<ConcurrentHashMap<String, Method>> METHODS = new ClassValue<>() {
        protected ConcurrentHashMap<String, Method> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private XaeroHostMapRefresh() {}

    public record Context(Object processor, Object dimension, String worldId, String dimId, String mwId,
                          Path directory, boolean worldSave, int layer) {}
    public record Result(boolean accepted, Object loadingRegion) {}

    private static Object call(Object object, String name, Object... args) throws ReflectiveOperationException {
        String key = name + java.util.Arrays.toString(java.util.Arrays.stream(args)
                .map(arg -> arg == null ? null : arg.getClass()).toArray());
        Method method = METHODS.get(object.getClass()).get(key);
        if (method == null) {
            for (Method candidate : object.getClass().getMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterCount() == args.length) {
                    boolean matches = true;
                    Class<?>[] parameters = candidate.getParameterTypes();
                    for (int i = 0; i < args.length; i++) {
                        Class<?> type = parameters[i];
                        if (type == int.class) type = Integer.class;
                        if (type == boolean.class) type = Boolean.class;
                        if (type == byte.class) type = Byte.class;
                        if (args[i] != null && !type.isInstance(args[i])) { matches = false; break; }
                    }
                    if (!matches) continue;
                    method = candidate;
                    break;
                }
            }
            if (method == null) throw new NoSuchMethodException(object.getClass().getName() + "." + key);
            METHODS.get(object.getClass()).put(key, method);
        }
        return method.invoke(object, args);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    /** 只使用 Xaero 自身当前视图的真实来源，不从维度/地图名字猜测。 */
    public static Context context(Object processor) throws ReflectiveOperationException {
        if (processor == null || (boolean) call(processor, "isWaitingForWorldUpdate")) return null;
        String world = (String) call(processor, "getCurrentWorldId");
        if (world == null) return null;
        Object mapWorld = call(processor, "getMapWorld");
        if ((boolean) call(mapWorld, "isCacheOnlyMode")) return null;
        Object dimension = call(mapWorld, "getCurrentDimension");
        if (dimension == null || (boolean) call(processor, "isCurrentMapLocked")) return null;
        String dim = (String) call(processor, "getCurrentDimId");
        String mw = (String) call(processor, "getCurrentMWId");
        boolean nativeView = (boolean) call(dimension, "isUsingWorldSave");
        int layer = (int) call(processor, "getCurrentCaveLayer");
        Path directory;
        if (nativeView) {
            Path worldDir = (Path) call(call(processor, "getWorldDataHandler"), "getWorldDir");
            if (worldDir == null) return null;
            directory = worldDir.resolve("region");
        } else {
            directory = (Path) call(call(processor, "getMapSaveLoad"), "getMWSubFolder", world, dim, mw);
            if (layer != Integer.MAX_VALUE) directory = directory.resolve("caves").resolve(Integer.toString(layer));
        }
        return new Context(processor, dimension, world, dim, mw, directory.toAbsolutePath().normalize(), nativeView, layer);
    }

    public static int writingDistance(Context context) throws ReflectiveOperationException {
        Object writer = call(context.processor(), "getMapWriter");
        Class<?> type = writer.getClass();
        Method method;
        while (true) {
            try { method = type.getDeclaredMethod("getWriteDistance"); break; }
            catch (NoSuchMethodException e) {
                type = type.getSuperclass();
                if (type == null) throw e;
            }
        }
        method.setAccessible(true);
        return (int) method.invoke(writer);
    }

    public static boolean reloadPending(Object region) throws ReflectiveOperationException {
        int state = (byte) call(region, "getLoadState");
        return (boolean) call(region, "reloadHasBeenRequested") || state == 1 || state == 3;
    }

    /** 在客户端/渲染主线程调用。持有 Xaero 原生 loadingSync 时只做短操作，忙碌则重试，不阻塞等 IO。 */
    public static Result invalidate(Context context, Path file, int x, int z) throws ReflectiveOperationException {
        return invalidate(context, file, x, z, true);
    }

    public static Result invalidate(Context context, Path file, int x, int z, boolean contentChanged) throws ReflectiveOperationException {
        Object processor = context.processor();
        Object loadingLock = field(processor, "loadingSync");
        synchronized (loadingLock) {
            if ((boolean) field(processor, "isLoading") || !context.equals(context(processor))) {
                return new Result(false, null);
            }
            Object saveLoad = call(processor, "getMapSaveLoad");
            if (!(boolean) call(saveLoad, "isRegionDetectionComplete")) return new Result(false, null);
            Object regions = call(context.dimension(), "getLayeredMapRegions");
            Object layer = call(regions, "getLayer", context.layer());
            Object region = call(regions, "getLeaf", context.layer(), x, z);
            if (region != null && (reloadPending(region) || (boolean) call(region, "isBeingWritten")
                    || (boolean) call(region, "isRefreshing"))) return new Result(false, null);

            Object detection = call(layer, "getRegionDetection", x, z);
            // 启动/重新开启时仅补齐缺失或落后的缓存，不把所有历史区域强制重建一遍。
            if (!contentChanged && (region != null || detection != null)) {
                File cache = (File) call(saveLoad, "getCacheFile", region == null ? detection : region,
                        context.layer(), false, false);
                if (cache != null && cache.isFile() && cache.lastModified() >= file.toFile().lastModified()) {
                    return new Result(true, null);
                }
            }
            if (region == null && detection == null) {
                Class<?> type = Class.forName("xaero.map.file.RegionDetection", true, processor.getClass().getClassLoader());
                detection = type.getConstructor(String.class, String.class, String.class, int.class,
                        int.class, File.class, int.class, boolean.class).newInstance(context.worldId(),
                        context.dimId(), context.mwId(), x, z, file.toFile(), (int) call(processor, "getGlobalVersion"), true);
                call(layer, "addRegionDetection", detection);
            }
            if (detection != null) call(detection, "setShouldCache", true, REASON);

            // 未加载的区域只登记失效，不为每次磁盘写入创建整张地图的像素对象。
            if (region != null) {
                if ((byte) call(region, "getLoadState") == 2) call(region, "clearRegion", processor);
                call(region, "setHasHadTerrain");
                call(region, "setShouldCache", true, REASON);
                call(saveLoad, "requestLoad", region, REASON, false);
            }
            // 沿用原生的父级版本检查，缩小世界地图时也能重新显示新增区域。
            for (int level = 1; level <= 3; level++) {
                Object parent = call(regions, "get", context.layer(), x >> level, z >> level, level);
                if (parent != null) {
                    call(parent, "setShouldCache", true, REASON);
                    call(parent, "setShouldCheckForUpdatesRecursive", true);
                }
            }
            return new Result(true, region);
        }
    }
}
