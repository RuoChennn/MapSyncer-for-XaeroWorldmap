package com.mapsyncer.network.payload;

/**
 * 客户端元数据记录 - 平台无关版本
 *
 * 用于同步请求包，包含区域文件的时间戳和CRC32哈希值。
 *
 * @param timestampSeconds 区域文件的时间戳（秒）
 * @param hash 区域文件的CRC32哈希值（8位十六进制字符串）
 */
public record ClientMeta(long timestampSeconds, String hash) {}