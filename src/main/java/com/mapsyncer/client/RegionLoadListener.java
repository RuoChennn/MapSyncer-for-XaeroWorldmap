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
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class RegionLoadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionLoadListener.class);

    private static volatile List<Object> pendingRegions = new ArrayList<>();
    private static volatile boolean isActive = false;
    private static volatile long startTime = 0;
    private static final long TIMEOUT_MS = 15000;
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static volatile int tickCounter = 0;

    /**
     * 开始监听 region 加载状态。
     *
     * @param regions 需要监听的 region 列表
     * @param processor MapProcessor 实例（保留参数兼容性，当前未使用）
     */
    public static void startListening(List<Object> regions, Object processor) {
        if (regions == null || regions.isEmpty()) {
            LOGGER.debug("没有需要监听的 region");
            return;
        }

        pendingRegions = new ArrayList<>(regions);
        isActive = true;
        startTime = System.currentTimeMillis();
        tickCounter = 0;

        LOGGER.info("开始监听 {} 个 region 的加载状态", pendingRegions.size());
    }

    /**
     * 停止监听。
     */
    public static void stopListening() {
        isActive = false;
        pendingRegions.clear();
        tickCounter = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!isActive || pendingRegions.isEmpty()) {
            return;
        }

        tickCounter++;

        if (tickCounter % CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startTime;

        if (elapsed > TIMEOUT_MS) {
            LOGGER.warn("Region 加载监听超时 {}ms，强制恢复 {} 个 region", elapsed, pendingRegions.size());
            resumeAllRegions();
            return;
        }

        try {
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            Field loadStateField = mapRegionClass.getDeclaredField("loadState");
            Field beingWrittenField = mapRegionClass.getDeclaredField("beingWritten");
            loadStateField.setAccessible(true);
            beingWrittenField.setAccessible(true);

            Method getRegionX = mapRegionClass.getMethod("getRegionX");
            Method getRegionZ = mapRegionClass.getMethod("getRegionZ");

            int readyCount = 0;
            for (Object region : pendingRegions) {
                byte loadState = loadStateField.getByte(region);
                boolean beingWritten = beingWrittenField.getBoolean(region);

                int rx = (int) getRegionX.invoke(region);
                int rz = (int) getRegionZ.invoke(region);
                LOGGER.debug("region ({}, {}) loadState={}, beingWritten={}", rx, rz, loadState, beingWritten);

                if (loadState == 2 && !beingWritten) {
                    readyCount++;
                }
            }

            LOGGER.debug("readyCount={} / {}, elapsed={}ms", readyCount, pendingRegions.size(), elapsed);

            if (readyCount == pendingRegions.size()) {
                LOGGER.info("所有 {} 个 region 加载完成，解除写保护", readyCount);
                resumeAllRegions();
            }

        } catch (Exception e) {
            LOGGER.error("检查 region 状态出错", e);
            resumeAllRegions();
        }
    }

    private static void resumeAllRegions() {
        try {
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            Method popWriterPause = mapRegionClass.getMethod("popWriterPause");
            Method getRegionX = mapRegionClass.getMethod("getRegionX");
            Method getRegionZ = mapRegionClass.getMethod("getRegionZ");

            int resumedCount = 0;
            for (Object region : pendingRegions) {
                try {
                    popWriterPause.invoke(region);

                    int rx = (int) getRegionX.invoke(region);
                    int rz = (int) getRegionZ.invoke(region);
                    LOGGER.debug("region ({}, {}) 已恢复写入", rx, rz);
                    resumedCount++;
                } catch (Exception e) {
                    LOGGER.warn("恢复 region 失败: {}", e.getMessage());
                }
            }

            LOGGER.info("完成: {} 个 region 已恢复写入", resumedCount);
        } catch (Exception e) {
            LOGGER.error("恢复 region 写入失败", e);
        }

        stopListening();
    }
}