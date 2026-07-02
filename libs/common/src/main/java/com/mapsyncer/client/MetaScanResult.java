package com.mapsyncer.client;

import com.mapsyncer.network.payload.ClientMeta;

import java.util.Collections;
import java.util.Map;

/**
 * 客户端区域哈希扫描结果。
 *
 * <p>{@link #success()} 且 meta 为空表示合法的「首次同步 / 无本地文件」场景；
 * {@link #failure()} 表示扫描出错，不应向服务端发送空 meta（否则会触发全量同步）。</p>
 */
public record MetaScanResult(Map<String, ClientMeta> meta, boolean success, int failedFiles, String failureReason) {

    public static MetaScanResult ok(Map<String, ClientMeta> meta) {
        return new MetaScanResult(
                meta != null ? meta : Collections.emptyMap(), true, 0, null);
    }

    public static MetaScanResult failure(String reason, int failedFiles) {
        return new MetaScanResult(Collections.emptyMap(), false, failedFiles, reason);
    }

    public boolean isSuccess() {
        return success;
    }
}
