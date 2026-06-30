package com.mapsyncer.client;

import com.mapsyncer.platform.XaeroReflectionHelper;
import com.mapsyncer.util.ModLogConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 region 追踪同步流水线耗时：收包 → 写盘 → 反射请求加载 → Xaero 上屏。
 *
 * <p>启用方式（任一即可）：</p>
 * <ul>
 *   <li>JVM 参数 {@code -Dmapsyncer.regionPipelineTrace=true}</li>
 *   <li>客户端配置 {@code enableDebugLogging=true} 且已调用 {@link ModLogConfig#applyDebugLogging()}</li>
 * </ul>
 */
public final class RegionPipelineTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionPipelineTracker.class);

    /** 等待 Xaero loadState=LOADED 的最长时间 */
    private static final long ON_SCREEN_POLL_TIMEOUT_MS = 5 * 60 * 1000L;

    public record RegionKey(int x, int z, int caveLayer) {
        public String label() {
            String layer = caveLayer == Integer.MAX_VALUE ? "surf" : String.valueOf(caveLayer);
            return x + "," + z + " L" + layer;
        }
    }

    private static final class Entry {
        volatile long packetReceivedNs;
        volatile long writeSubmitNs;
        volatile long writeDoneNs;
        volatile long reflectStartNs;
        volatile long reflectDoneNs;
        volatile long onScreenNs;
        volatile boolean reflectionRequested;
        volatile boolean writeOnly;
        volatile boolean completed;
        volatile int dataBytes;
        volatile byte lastLoadState = -1;
    }

    private record CompletedTiming(
            RegionKey key,
            long packetToWriteMs,
            long writeToReflectMs,
            long reflectToScreenMs,
            long totalMs,
            boolean writeOnly) {}

    private static volatile boolean forceEnabled = false;
    private static volatile boolean sessionActive = false;
    private static volatile boolean syncPipelineComplete = false;

    private static final ConcurrentHashMap<RegionKey, Entry> active = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap.KeySetView<RegionKey, Boolean> awaitingOnScreen =
            ConcurrentHashMap.newKeySet();
    private static final CopyOnWriteArrayList<CompletedTiming> completed = new CopyOnWriteArrayList<>();
    private static final AtomicInteger completedCount = new AtomicInteger();

    private RegionPipelineTracker() {}

    public static void setForceEnabled(boolean enabled) {
        forceEnabled = enabled;
    }

    public static boolean isEnabled() {
        return forceEnabled
                || Boolean.getBoolean("mapsyncer.regionPipelineTrace")
                || ModLogConfig.isDebugEnabled();
    }

    public static void beginSession() {
        if (!isEnabled()) {
            return;
        }
        active.clear();
        awaitingOnScreen.clear();
        completed.clear();
        completedCount.set(0);
        syncPipelineComplete = false;
        sessionActive = true;
        LOGGER.info("[RegionPipeline] session started");
    }

    public static void clear() {
        sessionActive = false;
        syncPipelineComplete = false;
        active.clear();
        awaitingOnScreen.clear();
    }

    /** 网络收包与写盘阶段已全部完成，等待剩余 region 上屏后输出汇总 */
    public static void markSyncPipelineComplete() {
        if (!isEnabled()) {
            return;
        }
        syncPipelineComplete = true;
        maybeFinishSession();
    }

    public static void onPacketReceived(int regionX, int regionZ, int caveLayer, int dataBytes) {
        if (!isEnabled()) {
            return;
        }
        if (!sessionActive) {
            beginSession();
        }
        RegionKey key = new RegionKey(regionX, regionZ, caveLayer);
        Entry entry = active.computeIfAbsent(key, k -> new Entry());
        entry.packetReceivedNs = System.nanoTime();
        entry.dataBytes = dataBytes;
    }

    public static void onWriteSubmitted(int regionX, int regionZ, int caveLayer) {
        if (!isEnabled()) {
            return;
        }
        Entry entry = active.get(new RegionKey(regionX, regionZ, caveLayer));
        if (entry != null) {
            entry.writeSubmitNs = System.nanoTime();
        }
    }

    public static void onWriteComplete(int regionX, int regionZ, int caveLayer, boolean success) {
        if (!isEnabled()) {
            return;
        }
        RegionKey key = new RegionKey(regionX, regionZ, caveLayer);
        Entry entry = active.get(key);
        if (entry == null) {
            return;
        }
        entry.writeDoneNs = System.nanoTime();
        if (!success) {
            finishEntry(key, entry, true, "write_failed");
        }
    }

    /** 已写盘但跳过反射（维度层不匹配等） */
    public static void onWriteOnlyComplete(int regionX, int regionZ, int caveLayer) {
        if (!isEnabled()) {
            return;
        }
        RegionKey key = new RegionKey(regionX, regionZ, caveLayer);
        Entry entry = active.get(key);
        if (entry == null) {
            return;
        }
        entry.writeOnly = true;
        finishEntry(key, entry, false, "write_only");
    }

    public static void onDeferredLoadQueued(int regionX, int regionZ, int caveLayer) {
        if (!isEnabled()) {
            return;
        }
        RegionKey key = new RegionKey(regionX, regionZ, caveLayer);
        Entry entry = active.get(key);
        if (entry != null) {
            entry.reflectionRequested = true;
        }
    }

    public static void onReflectionLoadStart(int regionX, int regionZ, int caveLayer) {
        if (!isEnabled()) {
            return;
        }
        RegionKey key = new RegionKey(regionX, regionZ, caveLayer);
        Entry entry = active.computeIfAbsent(key, k -> new Entry());
        entry.reflectionRequested = true;
        entry.reflectStartNs = System.nanoTime();
    }

    public static void onReflectionLoadDone(int regionX, int regionZ, int caveLayer, boolean success) {
        if (!isEnabled()) {
            return;
        }
        RegionKey key = new RegionKey(regionX, regionZ, caveLayer);
        Entry entry = active.get(key);
        if (entry == null) {
            return;
        }
        entry.reflectDoneNs = System.nanoTime();
        if (!success) {
            finishEntry(key, entry, true, "reflection_failed");
            return;
        }
        awaitingOnScreen.add(key);
    }

    /**
     * 由 ClientTick 调用，轮询 Xaero loadState 判定上屏完成。
     */
    public static void onClientTick() {
        if (!isEnabled() || awaitingOnScreen.isEmpty()) {
            return;
        }
        for (RegionKey key : List.copyOf(awaitingOnScreen)) {
            Entry entry = active.get(key);
            if (entry == null) {
                awaitingOnScreen.remove(key);
                continue;
            }
            byte loadState = queryLoadState(key);
            entry.lastLoadState = loadState;
            if (isDisplayedOnScreen(key)) {
                entry.onScreenNs = System.nanoTime();
                awaitingOnScreen.remove(key);
                finishEntry(key, entry, false, "on_screen");
                continue;
            }
            long waitedMs = msSince(entry.reflectDoneNs > 0 ? entry.reflectDoneNs : entry.writeDoneNs);
            if (waitedMs > ON_SCREEN_POLL_TIMEOUT_MS) {
                awaitingOnScreen.remove(key);
                finishEntry(key, entry, true, "on_screen_timeout(state=" + loadState + ")");
            }
        }
        maybeFinishSession();
    }

    private static void maybeFinishSession() {
        if (!sessionActive || !syncPipelineComplete) {
            return;
        }
        if (!active.isEmpty() || !awaitingOnScreen.isEmpty()) {
            return;
        }
        endSession();
    }

    public static void endSession() {
        if (!isEnabled() || !sessionActive) {
            return;
        }
        for (RegionKey key : List.copyOf(awaitingOnScreen)) {
            Entry entry = active.get(key);
            if (entry != null) {
                finishEntry(key, entry, true, "session_end_pending");
            }
        }
        logSummary();
        sessionActive = false;
        syncPipelineComplete = false;
        active.clear();
        awaitingOnScreen.clear();
    }

    private static void finishEntry(RegionKey key, Entry entry, boolean incomplete, String reason) {
        if (entry.completed) {
            return;
        }
        entry.completed = true;
        awaitingOnScreen.remove(key);
        active.remove(key);

        long packetNs = entry.packetReceivedNs;
        long writeDoneNs = entry.writeDoneNs > 0 ? entry.writeDoneNs : entry.writeSubmitNs;
        long reflectStartNs = entry.reflectStartNs;
        long reflectDoneNs = entry.reflectDoneNs > 0 ? entry.reflectDoneNs : reflectStartNs;
        long onScreenNs = entry.onScreenNs;

        long packetToWriteMs = diffMs(packetNs, writeDoneNs);
        long writeToReflectMs = entry.writeOnly ? 0 : diffMs(writeDoneNs, reflectStartNs);
        long reflectToScreenMs = entry.writeOnly ? 0 : diffMs(reflectDoneNs, onScreenNs > 0 ? onScreenNs : System.nanoTime());
        long totalMs = diffMs(packetNs, onScreenNs > 0 ? onScreenNs : System.nanoTime());

        CompletedTiming timing = new CompletedTiming(
                key, packetToWriteMs, writeToReflectMs, reflectToScreenMs, totalMs, entry.writeOnly);
        completed.add(timing);
        completedCount.incrementAndGet();

        if (incomplete) {
            LOGGER.warn("[RegionPipeline] ({}) incomplete: {} | packet→write={}ms write→reflect={}ms "
                            + "reflect→screen={}ms total={}ms bytes={} lastLoadState={}",
                    key.label(), reason, packetToWriteMs, writeToReflectMs, reflectToScreenMs,
                    totalMs, entry.dataBytes, entry.lastLoadState);
        } else {
            LOGGER.info("[RegionPipeline] ({}) packet→write={}ms write→reflect={}ms reflect→screen={}ms "
                            + "total={}ms bytes={}{}",
                    key.label(), packetToWriteMs, writeToReflectMs, reflectToScreenMs, totalMs,
                    entry.dataBytes, entry.writeOnly ? " [write-only]" : "");
        }
        maybeFinishSession();
    }

    private static void logSummary() {
        int count = completedCount.get();
        if (count == 0) {
            LOGGER.info("[RegionPipeline] session ended: no regions tracked");
            return;
        }

        long sumPacketToWrite = 0;
        long sumWriteToReflect = 0;
        long sumReflectToScreen = 0;
        long sumTotal = 0;
        int writeOnlyCount = 0;
        for (CompletedTiming t : completed) {
            sumPacketToWrite += t.packetToWriteMs();
            sumWriteToReflect += t.writeToReflectMs();
            sumReflectToScreen += t.reflectToScreenMs();
            sumTotal += t.totalMs();
            if (t.writeOnly()) {
                writeOnlyCount++;
            }
        }

        List<CompletedTiming> byTotal = new ArrayList<>(completed);
        byTotal.sort(Comparator.comparingLong(CompletedTiming::totalMs).reversed());
        int slowN = Math.min(5, byTotal.size());
        StringBuilder slowest = new StringBuilder();
        for (int i = 0; i < slowN; i++) {
            CompletedTiming t = byTotal.get(i);
            if (i > 0) {
                slowest.append(", ");
            }
            slowest.append(String.format(Locale.ROOT, "%s=%dms", t.key().label(), t.totalMs()));
        }

        LOGGER.info("[RegionPipeline] session summary: regions={} writeOnly={} avg packet→write={}ms "
                        + "write→reflect={}ms reflect→screen={}ms total={}ms | slowest: {}",
                count, writeOnlyCount,
                sumPacketToWrite / count,
                sumWriteToReflect / Math.max(1, count - writeOnlyCount),
                sumReflectToScreen / Math.max(1, count - writeOnlyCount),
                sumTotal / count,
                slowest);
    }

    private static byte queryLoadState(RegionKey key) {
        if (!XaeroReflectionHelper.isInitialized()) {
            return -1;
        }
        Object mapRegion = XaeroReflectionHelper.getLeafMapRegion(key.caveLayer(), key.x(), key.z(), false);
        if (mapRegion == null) {
            return -1;
        }
        return XaeroReflectionHelper.getLoadState(mapRegion);
    }

    /** loadState=LOADED 且不在刷新中视为已上屏 */
    private static boolean isDisplayedOnScreen(RegionKey key) {
        if (!XaeroReflectionHelper.isInitialized()) {
            return false;
        }
        Object mapRegion = XaeroReflectionHelper.getLeafMapRegion(key.caveLayer(), key.x(), key.z(), false);
        if (mapRegion == null) {
            return false;
        }
        byte loadState = XaeroReflectionHelper.getLoadState(mapRegion);
        return loadState == XaeroReflectionHelper.LOAD_STATE_LOADED
                && !XaeroReflectionHelper.isRefreshing(mapRegion);
    }

    private static long diffMs(long startNs, long endNs) {
        if (startNs <= 0 || endNs <= 0 || endNs < startNs) {
            return 0;
        }
        return (endNs - startNs) / 1_000_000L;
    }

    private static long msSince(long startNs) {
        if (startNs <= 0) {
            return 0;
        }
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
