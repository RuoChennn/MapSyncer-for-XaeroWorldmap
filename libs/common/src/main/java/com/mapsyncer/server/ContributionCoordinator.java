package com.mapsyncer.server;

import com.mapsyncer.config.ContributionScope;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.HashUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serializes client map contributions so only one upload session can write server cache files at a time.
 */
public final class ContributionCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContributionCoordinator.class);
    private static final long SESSION_TIMEOUT_MILLIS = 120_000L;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<ContributionSession> QUEUE = new ArrayDeque<>();
    private static final ContributionUploadAssembler ASSEMBLER = new ContributionUploadAssembler();
    private static final AtomicInteger NEXT_REQUEST_ID = new AtomicInteger(1);

    private static ContributionSession activeSession;
    private static boolean draining;
    private static boolean shutdown;

    private ContributionCoordinator() {
    }

    public static boolean enqueueSession(ServerPlayer player, List<ContributionRegionMeta> candidates) {
        if (player == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            if (shutdown) {
                shutdown = false;
            }
            int maxQueueSize = Math.max(1, PlatformManager.getPlatform().getMaxContributionQueueSize());
            if (QUEUE.size() >= maxQueueSize) {
                return false;
            }
            ContributionSession session = new ContributionSession(
                    NEXT_REQUEST_ID.getAndIncrement(),
                    player,
                    candidates
            );
            QUEUE.addLast(session);
            startDrainLoopLocked();
            LOCK.notifyAll();
            return true;
        }
    }

    public static void handleData(
            ServerPlayer player,
            ContributionDataPayload payload,
            Path cacheDir,
            GenerationCache cache
    ) {
        if (player == null || payload == null || cacheDir == null || cache == null) {
            return;
        }

        ContributionSession session;
        ContributionRegionMeta expected;
        synchronized (LOCK) {
            session = activeSession;
            if (session == null || session.requestId() != payload.requestId()) {
                sendResult(player, new ContributionResultPayload(payload.requestId(), 0, 1, "inactive_request"));
                return;
            }
            if (!session.playerId().equals(player.getUUID())) {
                sendResult(player, new ContributionResultPayload(payload.requestId(), 0, 1, "wrong_player"));
                return;
            }
            expected = session.expectedRegion(payload.relativePath());
            if (expected == null) {
                session.markRejected();
                sendSessionResult(session, "unexpected_region");
                return;
            }
            if (!isContributionStillEnabled()) {
                session.markRejected();
                sendSessionResult(session, "permission_changed");
                return;
            }
            if (!matchesObservedServerState(expected, payload) || !matchesExpectedChunk(expected, payload.chunk())) {
                ASSEMBLER.clear(payload);
                session.markRejected();
                sendSessionResult(session, "stale_upload");
                return;
            }

            ContributionUploadAssembler.Result assembled = ASSEMBLER.accept(payload);
            if (assembled.rejected()) {
                session.markRejected();
                sendSessionResult(session, assembled.rejectionReason());
                return;
            }
            if (!assembled.complete()) {
                return;
            }

            ContributionValidator.Result validation = ContributionValidator.validate(
                    expected,
                    assembled.fullData(),
                    payload.chunk().timestampSeconds,
                    cache,
                    cacheDir
            );
            if (!validation.accepted()) {
                session.markRejected();
                sendSessionResult(session, validation.reason());
                return;
            }

            try {
                writeAcceptedRegion(cacheDir, expected.relativePath(), assembled.fullData());
                String acceptedHash = HashUtils.computeHash(assembled.fullData());
                cache.update(expected.relativePath(), validation.acceptedTimestampSeconds(), acceptedHash);
                cache.save();
                session.markAccepted();
                sendSessionResult(session, "accepted");
            } catch (IOException e) {
                LOGGER.warn("Failed to write contribution {} from {}", expected.relativePath(), session.playerName(), e);
                session.markRejected();
                sendSessionResult(session, "write_failed");
            }
        }
    }

    public static void handleComplete(ServerPlayer player, ContributionCompletePayload payload) {
        if (player == null || payload == null) {
            return;
        }
        synchronized (LOCK) {
            ContributionSession session = activeSession;
            if (session == null || session.requestId() != payload.requestId()) {
                sendResult(player, new ContributionResultPayload(payload.requestId(), 0, 1, "inactive_request"));
                return;
            }
            if (!session.playerId().equals(player.getUUID())) {
                sendResult(player, new ContributionResultPayload(payload.requestId(), 0, 1, "wrong_player"));
                return;
            }
            ASSEMBLER.clearRequest(payload.requestId());
            session.markComplete(payload.status() == null || payload.status().isBlank() ? "done" : payload.status());
            LOCK.notifyAll();
        }
    }

    public static void cancelPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        synchronized (LOCK) {
            QUEUE.removeIf(session -> session.playerId().equals(playerId));
            if (activeSession != null && activeSession.playerId().equals(playerId)) {
                activeSession.markComplete("player_left");
                ASSEMBLER.clearRequest(activeSession.requestId());
                LOCK.notifyAll();
            }
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            shutdown = true;
            QUEUE.clear();
            ASSEMBLER.clearAll();
            if (activeSession != null) {
                activeSession.markComplete("shutdown");
            }
            LOCK.notifyAll();
        }
    }

    private static void startDrainLoopLocked() {
        if (draining) {
            return;
        }
        draining = true;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "MapSyncer-ContributionQueue");
            thread.setDaemon(true);
            return thread;
        };
        factory.newThread(ContributionCoordinator::drainLoop).start();
    }

    private static void drainLoop() {
        try {
            while (true) {
                ContributionSession session;
                synchronized (LOCK) {
                    if (shutdown) {
                        draining = false;
                        activeSession = null;
                        return;
                    }
                    session = QUEUE.pollFirst();
                    if (session == null) {
                        draining = false;
                        return;
                    }
                    activeSession = session;
                }

                sendRequest(session);
                waitForSessionEnd(session);
                String status;
                synchronized (LOCK) {
                    status = session.isComplete() ? session.completionStatus() : "timeout";
                    ASSEMBLER.clearRequest(session.requestId());
                    if (!"shutdown".equals(status)) {
                        sendSessionResult(session, status);
                    }
                    activeSession = null;
                    LOCK.notifyAll();
                }
                waitCooldown();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Contribution queue stopped unexpectedly", e);
            synchronized (LOCK) {
                draining = false;
                activeSession = null;
                LOCK.notifyAll();
            }
        }
    }

    private static void waitForSessionEnd(ContributionSession session) {
        long deadline = System.currentTimeMillis() + SESSION_TIMEOUT_MILLIS;
        synchronized (LOCK) {
            while (!shutdown && activeSession == session && !session.isComplete()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    session.markComplete("timeout");
                    return;
                }
                waitLocked(remaining);
            }
        }
    }

    private static void waitCooldown() {
        long cooldownMillis = Math.max(0, PlatformManager.getPlatform().getContributionQueueCooldownSeconds()) * 1000L;
        if (cooldownMillis <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + cooldownMillis;
        synchronized (LOCK) {
            while (!shutdown) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return;
                }
                waitLocked(remaining);
            }
        }
    }

    private static void waitLocked(long millis) {
        try {
            LOCK.wait(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendRequest(ContributionSession session) {
        sendToPlayer(session.player(), new ContributionRequestPayload(
                session.requestId(),
                List.copyOf(session.expectedRegions().values()),
                "request"
        ));
    }

    private static void sendSessionResult(ContributionSession session, String status) {
        sendToPlayer(session.player(), new ContributionResultPayload(
                session.requestId(),
                session.accepted(),
                session.rejected(),
                status
        ));
    }

    private static void sendResult(ServerPlayer player, ContributionResultPayload payload) {
        sendToPlayer(player, payload);
    }

    private static void sendToPlayer(ServerPlayer player, ContributionResultPayload payload) {
        var server = player.level().getServer();
        if (server != null) {
            server.execute(() -> NetworkManager.sendToPlayer(player, payload));
        } else {
            NetworkManager.sendToPlayer(player, payload);
        }
    }

    private static void sendToPlayer(ServerPlayer player, ContributionRequestPayload payload) {
        var server = player.level().getServer();
        if (server != null) {
            server.execute(() -> NetworkManager.sendToPlayer(player, payload));
        } else {
            NetworkManager.sendToPlayer(player, payload);
        }
    }

    private static boolean isContributionStillEnabled() {
        return PlatformManager.isInitialized()
                && PlatformManager.getPlatform().getContributionScope() != ContributionScope.DISABLED;
    }

    private static boolean matchesObservedServerState(ContributionRegionMeta expected, ContributionDataPayload payload) {
        return expected.serverTimestampSeconds() == payload.observedServerTimestampSeconds()
                && expected.serverHash().equals(payload.observedServerHash());
    }

    private static boolean matchesExpectedChunk(ContributionRegionMeta expected, ChunkMapData chunk) {
        return chunk != null
                && expected.regionX() == chunk.regionX
                && expected.regionZ() == chunk.regionZ
                && expected.dimension().equals(chunk.dimension)
                && expected.caveLayer() == chunk.caveLayer;
    }

    private static void writeAcceptedRegion(Path cacheDir, String relativePath, byte[] data) throws IOException {
        Path root = cacheDir.toAbsolutePath().normalize();
        Path target = root.resolve(relativePath + ".zip").normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Unsafe contribution path: " + relativePath);
        }
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".uploading");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
