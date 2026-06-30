package com.mapsyncer.server;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.HashUtils;

/**
 * 服务端 region 同步决策：hash 对齐或客户端版本不旧于服务端时跳过传输。
 */
public final class RegionSyncPolicy {

    private RegionSyncPolicy() {}

    /**
     * @param serverHash   服务端 zip CRC（genCache 或现场计算）
     * @param serverTs     服务端生成时间戳（秒）
     * @param clientMeta   客户端上报元数据，可为 null
     * @return true 表示需要向客户端发送该 region
     */
    public static boolean shouldTransfer(String serverHash, long serverTs, ClientMeta clientMeta) {
        if (clientMeta == null) {
            return true;
        }
        String clientHash = clientMeta.hash();
        if (!HashUtils.isValidHash(clientHash)) {
            return true;
        }
        if (serverHash.equals(clientHash)) {
            return false;
        }
        if (clientMeta.timestampSeconds() >= serverTs) {
            return false;
        }
        return true;
    }
}
