package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 同步收包后的 region zip 异步写盘队列。
 * 文件 IO 与时间戳缓存更新在后台线程执行；Xaero 反射重载仍由调用方在主线程调度。
 */
public final class ClientSyncWriteQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSyncWriteQueue.class);

    private static final int IO_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(IO_THREADS, r -> {
        Thread t = new Thread(r, "mapsyncer-sync-io");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicInteger pendingWrites = new AtomicInteger(0);

    private ClientSyncWriteQueue() {}

    public static boolean hasPendingWrites() {
        return pendingWrites.get() > 0;
    }

    /**
     * 异步写入 region zip，并在 IO 线程更新内存中的时间戳缓存。
     *
     * @param callback 写盘完成回调（在 IO 线程调用）
     */
    public static void submit(ChunkMapData chunk, Path serverDir, int worldId,
            ClientTimestampCache tsCache, Consumer<XaeroMapDataHandler.RegionWriteResult> callback) {
        pendingWrites.incrementAndGet();
        EXECUTOR.execute(() -> {
            XaeroMapDataHandler.RegionWriteResult result = null;
            try {
                result = XaeroMapDataHandler.writeChunkData(chunk, serverDir, worldId);
                if (result != null) {
                    if (tsCache != null) {
                        tsCache.update(
                                XaeroMapDataHandler.buildRelativePathForCache(chunk),
                                chunk.timestampSeconds,
                                result.crc32Hash());
                    }
                    XaeroMapDataHandler.clearRegionCacheFiles(
                            result.mwDir(),
                            new XaeroMapDataHandler.RegionCoord(chunk.regionX, chunk.regionZ, chunk.caveLayer));
                }
            } catch (Exception e) {
                LOGGER.error("Async region write failed for ({}, {})", chunk.regionX, chunk.regionZ, e);
            } finally {
                pendingWrites.decrementAndGet();
                try {
                    callback.accept(result);
                } catch (Exception e) {
                    LOGGER.error("Sync write callback failed for ({}, {})", chunk.regionX, chunk.regionZ, e);
                }
            }
        });
    }

    /** 异步持久化时间戳缓存（避免主线程阻塞）。 */
    public static void saveTimestampCacheAsync(ClientTimestampCache tsCache) {
        if (tsCache == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                tsCache.save();
            } catch (Exception e) {
                LOGGER.warn("Async timestamp cache save failed", e);
            }
        });
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
        pendingWrites.set(0);
        LOGGER.debug("ClientSyncWriteQueue shutdown");
    }
}
