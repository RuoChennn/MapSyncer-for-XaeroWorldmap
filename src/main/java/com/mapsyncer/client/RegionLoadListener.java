package com.mapsyncer.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Region 加载状态监听器。
 * 使用客户端 tick 事件监听视距内 region 的加载状态，
 * 当所有 region 都加载完成（loadState=2）时解除写保护。
 *
 * <p>相比轮询方式，tick 监听更高效且在游戏主线程中执行。</p>
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class RegionLoadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionLoadListener.class);

    /** 等待加载的 region 列表 */
    private static volatile List<Object> pendingRegions = new ArrayList<>();

    /** 监听是否激活 */
    private static volatile boolean isActive = false;

    /** 开始监听时间（用于超时） */
    private static volatile long startTime = 0;

    /** 超时时间（毫秒） */
    private static final long TIMEOUT_MS = 15000;

    /** 检查间隔（tick 数，约每 10 tick 检查一次，约 500ms） */
    private static final int CHECK_INTERVAL_TICKS = 10;

    /** 当前 tick 计数 */
    private static volatile int tickCounter = 0;

    /**
     * 开始监听 region 加载状态。
     *
     * @param regions 需要监听的 region 列表
     * @param processor MapProcessor 实例（不再使用，保留参数兼容性）
     */
    public static void startListening(List<Object> regions, Object processor) {
        if (regions == null || regions.isEmpty()) {
            LOGGER.info("No regions to listen for");
            return;
        }

        pendingRegions = new ArrayList<>(regions);
        isActive = true;
        startTime = System.currentTimeMillis();
        tickCounter = 0;

        LOGGER.info("Started listening for {} regions to load", pendingRegions.size());
    }

    /**
     * 停止监听。
     */
    public static void stopListening() {
        isActive = false;
        pendingRegions.clear();
        tickCounter = 0;
    }

    /**
     * 客户端 tick 事件处理。
     * 检查 region 加载状态，满足条件时解除写保护。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!isActive || pendingRegions.isEmpty()) {
            return;
        }

        tickCounter++;

        // 每 CHECK_INTERVAL_TICKS tick 检查一次
        if (tickCounter % CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        // 检查超时
        long elapsed = System.currentTimeMillis() - startTime;
        LOGGER.info("[DEBUG-Tick] 检查 region 加载状态, elapsed={}ms, tickCounter={}", elapsed, tickCounter);

        if (elapsed > TIMEOUT_MS) {
            LOGGER.warn("[DEBUG-Tick] 超时 {}ms，强制恢复 {} 个 region", elapsed, pendingRegions.size());
            resumeAllRegions();
            return;
        }

        try {
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            Field loadStateField = mapRegionClass.getDeclaredField("loadState");
            Field beingWrittenField = mapRegionClass.getDeclaredField("beingWritten");
            loadStateField.setAccessible(true);
            beingWrittenField.setAccessible(true);

            // 使用 getRegionX() 和 getRegionZ() 方法获取坐标
            Method getRegionX = mapRegionClass.getMethod("getRegionX");
            Method getRegionZ = mapRegionClass.getMethod("getRegionZ");

            int readyCount = 0;
            for (Object region : pendingRegions) {
                byte loadState = loadStateField.getByte(region);
                boolean beingWritten = beingWrittenField.getBoolean(region);

                // 获取 region 坐标用于 debug
                int rx = (int) getRegionX.invoke(region);
                int rz = (int) getRegionZ.invoke(region);
                LOGGER.info("[DEBUG-Tick] region ({}, {}) loadState={}, beingWritten={}", rx, rz, loadState, beingWritten);

                // loadState == 2 且 !beingWritten 表示已加载完成
                if (loadState == 2 && !beingWritten) {
                    readyCount++;
                }
            }

            LOGGER.info("[DEBUG-Tick] readyCount={} / {}, elapsed={}ms", readyCount, pendingRegions.size(), elapsed);

            // 所有 region 都满足条件时解除写保护
            if (readyCount == pendingRegions.size()) {
                LOGGER.info("[DEBUG-Tick] 所有 {} 个 region 加载完成，解除写保护", readyCount);
                resumeAllRegions();
            }

        } catch (Exception e) {
            LOGGER.error("[DEBUG-Tick] 检查 region 状态出错", e);
            // 出错时也解除保护，避免卡住
            resumeAllRegions();
        }
    }

    /**
     * 解除所有 region 的写保护。
     */
    private static void resumeAllRegions() {
        LOGGER.info("[DEBUG-Resume] 开始解除 {} 个 region 的写保护", pendingRegions.size());
        try {
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            Method popWriterPause = mapRegionClass.getMethod("popWriterPause");
            Method getRegionX = mapRegionClass.getMethod("getRegionX");
            Method getRegionZ = mapRegionClass.getMethod("getRegionZ");

            int resumedCount = 0;
            for (Object region : pendingRegions) {
                try {
                    // 解除 region 写保护
                    popWriterPause.invoke(region);

                    // 获取坐标用于 debug
                    int rx = (int) getRegionX.invoke(region);
                    int rz = (int) getRegionZ.invoke(region);
                    LOGGER.info("[DEBUG-Resume] region ({}, {}) 已恢复写入", rx, rz);
                    resumedCount++;
                } catch (Exception e) {
                    LOGGER.warn("[DEBUG-Resume] 恢复 region 失败: {}", e.getMessage());
                }
            }

            LOGGER.info("[DEBUG-Resume] 完成: {} 个 region 已恢复写入", resumedCount);
        } catch (Exception e) {
            LOGGER.error("[DEBUG-Resume] 恢复 region 写入失败", e);
        }

        stopListening();
    }
}