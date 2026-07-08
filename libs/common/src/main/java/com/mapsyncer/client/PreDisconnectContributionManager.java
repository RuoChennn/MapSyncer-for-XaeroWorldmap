package com.mapsyncer.client;

import com.mapsyncer.config.ClientSyncMode;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
import com.mapsyncer.platform.PlatformManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 退出前贡献同步状态机。
 *
 * <p>当玩家在多人服务器正常点击断开连接时，暂停菜单 Mixin 会调用
 * {@link #start(Path, Runnable)} 尝试在断开前先把本地 Xaero 地图贡献给服务端。
 * 该流程只发起 {@link ContributionOnlyRequestPayload}，不接收服务端地图分发。</p>
 *
 * <p>关键 ID 边界：</p>
 * <ul>
 *   <li>{@code activeClientRequestId} 是客户端发起仅贡献请求的相关 ID，只用于
 *       服务端在入队前直接拒绝（not_allowed / no_candidates / queue_full）的终态结果。</li>
 *   <li>一旦服务端成功入队，会通过 {@link ContributionRequestPayload} 下发服务端生成
 *       的会话 ID，此时记录到 {@code activeServerSessionId}，后续结果只能匹配该 ID。</li>
 *   <li>只有在收到 {@code terminal=true} 且 ID 匹配的结果，或本地超时，才会执行原始
 *       断开动作。region 级中间状态（accepted / stale_upload 等）只更新界面状态。</li>
 * </ul>
 *
 * <p>本类的状态通过 volatile 字段发布，{@code collecting} 标志用于确保元数据扫描
 * 只有一个 worker 在运行。元数据扫描在独立线程执行，避免阻塞渲染/菜单线程导致
 * 等待界面和超时无法 tick。</p>
 */
public final class PreDisconnectContributionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreDisconnectContributionManager.class);

    /** 退出前贡献请求的客户端相关 ID 起始值，避开服务端会话 ID 空间。 */
    private static final AtomicInteger NEXT_REQUEST_ID = new AtomicInteger(10_000);

    /** 当前活跃的客户端请求 ID，-1 表示空闲。 */
    private static volatile int activeClientRequestId = -1;
    /** 服务端入队后下发的会话 ID，-1 表示尚未收到服务端会话。 */
    private static volatile int activeServerSessionId = -1;
    /** 原始断开动作，贡献结束（完成/跳过/超时）后执行。 */
    private static volatile Runnable disconnectAction;
    /** 超时截止时间（毫秒）。 */
    private static volatile long deadlineMillis;
    /** 当前界面状态对应的语言 key。 */
    private static volatile String statusKey = "mapsyncer.predisconnect.idle";
    /** 元数据扫描 worker 是否在运行，防止重复启动。 */
    private static final AtomicBoolean collecting = new AtomicBoolean(false);

    private PreDisconnectContributionManager() {
    }

    /**
     * 判断当前是否满足启动退出前贡献同步的前提条件。
     *
     * <p>条件包括：客户端双向同步模式、配置开启、超时大于 0、服务端已安装 MapSyncer、
     * 没有普通同步或普通贡献正在进行。该检查不能保证后续一定有贡献发生，仅用于
     * 决定是否进入等待界面。</p>
     */
    public static boolean canStart() {
        try {
            return PlatformManager.getPlatform().getClientSyncMode() == ClientSyncMode.BIDIRECTIONAL
                    && PlatformManager.getPlatform().isSyncBeforeDisconnect()
                    && PlatformManager.getPlatform().getDisconnectSyncTimeoutSeconds() > 0
                    && MapPacketHandler.isServerInstalled()
                    && !MapPacketHandler.isSyncInProgress()
                    && !MapPacketHandler.isContributionInProgress();
        } catch (IllegalStateException e) {
            // 平台未初始化（例如尚未进入世界），不启动退出前同步。
            LOGGER.debug("canStart: platform not initialized, skipping pre-disconnect sync");
            return false;
        }
    }

    /**
     * 启动退出前贡献同步。
     *
     * <p>如果不满足启动条件，或参数无效，则直接执行原始断开动作。否则进入等待状态，
     * 在后台线程扫描本地地图元数据，扫描完成后分片发送仅贡献请求。</p>
     *
     * @param serverDir              当前服务器的 Xaero 目录（Multiplayer_*）
     * @param originalDisconnectAction 原始断开动作，贡献结束后调用
     */
    public static void start(Path serverDir, Runnable originalDisconnectAction) {
        if (serverDir == null || originalDisconnectAction == null || !canStart()) {
            if (originalDisconnectAction != null) {
                originalDisconnectAction.run();
            }
            return;
        }

        int requestId = NEXT_REQUEST_ID.incrementAndGet();
        activeClientRequestId = requestId;
        activeServerSessionId = -1;
        disconnectAction = originalDisconnectAction;
        deadlineMillis = System.currentTimeMillis()
                + PlatformManager.getPlatform().getDisconnectSyncTimeoutSeconds() * 1000L;
        statusKey = "mapsyncer.predisconnect.collecting";
        LOGGER.info("Starting pre-disconnect contribution sync (clientRequestId={}, timeout={}s)",
                requestId, PlatformManager.getPlatform().getDisconnectSyncTimeoutSeconds());

        if (!collecting.compareAndSet(false, true)) {
            // 已有扫描在运行（理论上不会发生，防御性处理）：直接等待结果。
            LOGGER.warn("Pre-disconnect meta collection already in progress, reusing existing worker");
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                Map<String, ClientMeta> meta = ClientHashManager.computeMetaForSync(serverDir);
                // 扫描期间如果被取消或被新的请求取代，则不再发送。
                if (activeClientRequestId != requestId) {
                    LOGGER.debug("Pre-disconnect meta scan finished but request changed, not sending");
                    return;
                }
                if (meta.isEmpty()) {
                    LOGGER.info("Pre-disconnect meta scan found no regions, finishing");
                    statusKey = "mapsyncer.predisconnect.status.no_candidates";
                    finish();
                    return;
                }
                statusKey = "mapsyncer.predisconnect.uploading";
                var parts = ContributionOnlyRequestPayload.split(requestId, meta, "pre_disconnect");
                for (ContributionOnlyRequestPayload part : parts) {
                    if (activeClientRequestId != requestId) {
                        LOGGER.debug("Pre-disconnect upload interrupted, request changed");
                        return;
                    }
                    NetworkManager.sendToServer(part);
                }
                LOGGER.info("Pre-disconnect contribution request sent ({} parts, clientRequestId={})",
                        parts.size(), requestId);
            } catch (Exception e) {
                LOGGER.error("Failed to collect or send pre-disconnect contribution metadata", e);
                if (activeClientRequestId == requestId) {
                    statusKey = "mapsyncer.predisconnect.status.error";
                    finish();
                }
            } finally {
                collecting.set(false);
            }
        }, "MapSyncer-PreDisconnectMeta");
        worker.setDaemon(true);
        worker.start();
    }

    /** 当前是否有退出前贡献同步正在进行。 */
    public static boolean isActive() {
        return activeClientRequestId >= 0;
    }

    /** 获取当前界面状态对应的语言 key。 */
    public static String getStatusKey() {
        return statusKey;
    }

    /** 是否已经超过配置的超时时间。 */
    public static boolean isTimedOut() {
        return isActive() && System.currentTimeMillis() >= deadlineMillis;
    }

    /**
     * 收到服务端的 {@link ContributionRequestPayload} 时调用。
     *
     * <p>当请求 ID 匹配当前活跃的客户端请求时，记录服务端生成的会话 ID。后续的
     * 贡献结果只能匹配该服务端会话 ID。</p>
     */
    public static void handleContributionRequest(ContributionRequestPayload payload) {
        if (payload == null || !isActive()) {
            return;
        }
        if (activeServerSessionId >= 0) {
            return; // 已有服务端会话，忽略重复请求。
        }
        activeServerSessionId = payload.requestId();
        statusKey = "mapsyncer.predisconnect.uploading";
        LOGGER.info("Pre-disconnect contribution session started (serverSessionId={})",
                activeServerSessionId);
    }

    /**
     * 收到服务端的 {@link ContributionResultPayload} 时调用。
     *
     * <p>只处理 ID 匹配的结果：未收到服务端会话时匹配客户端请求 ID；已收到服务端
     * 会话时匹配服务端会话 ID。{@code terminal=true} 的结果触发原始断开动作；
     * 非终态结果只更新界面状态。</p>
     */
    public static void handleContributionResult(ContributionResultPayload payload) {
        if (payload == null || !isActive()) {
            return;
        }
        boolean matchesClientRequest = activeServerSessionId < 0
                && payload.requestId() == activeClientRequestId;
        boolean matchesServerSession = activeServerSessionId >= 0
                && payload.requestId() == activeServerSessionId;
        if (!matchesClientRequest && !matchesServerSession) {
            return;
        }
        statusKey = "mapsyncer.predisconnect.status." + payload.status();
        LOGGER.info("Pre-disconnect contribution result: status={}, terminal={}, accepted={}, rejected={}",
                payload.status(), payload.terminal(), payload.accepted(), payload.rejected());
        if (payload.terminal()) {
            finish();
        }
    }

    /**
     * 玩家点击“跳过并退出”时调用：立即执行原始断开动作。
     *
     * <p>跳过只停止本地等待，不会发送服务端取消请求；若贡献会话已入队，服务端可能
     * 继续处理，也可能因玩家离线而失败/超时。</p>
     */
    public static void skipAndDisconnect() {
        if (!isActive()) {
            return;
        }
        statusKey = "mapsyncer.predisconnect.skipped";
        LOGGER.info("Pre-disconnect contribution sync skipped by user");
        finish();
    }

    /**
     * 玩家点击“返回游戏”时调用：取消等待，不执行断开动作。
     *
     * <p>注意：如果服务端已经将贡献会话入队，该会话可能在后台继续；本方法只取消
     * 本地的退出意图和断开动作。清除本地活跃状态后，后续到达的结果将被忽略。</p>
     */
    public static void cancel() {
        if (!isActive()) {
            return;
        }
        LOGGER.info("Pre-disconnect contribution sync cancelled by user (queued session may continue server-side)");
        activeClientRequestId = -1;
        activeServerSessionId = -1;
        disconnectAction = null;
        statusKey = "mapsyncer.predisconnect.idle";
    }

    /**
     * 完成退出前贡献同步：清除本地状态并执行原始断开动作。
     */
    private static void finish() {
        Runnable action = disconnectAction;
        activeClientRequestId = -1;
        activeServerSessionId = -1;
        disconnectAction = null;
        if (action != null) {
            action.run();
        }
    }
}
