package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * 哈希计算工具类
 *
 * 统一的CRC32哈希计算方法，合并 ClientHashManager 和 GenerationCache 中的重复实现
 */
public final class HashUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(HashUtils.class);

    /** 文件不存在或读取失败时返回的默认哈希值 */
    public static final String DEFAULT_HASH = "00000000";

    private HashUtils() {
        // 工具类不允许实例化
    }

    /**
     * 计算文件的CRC32哈希值（流式读取，避免内存峰值）
     *
     * <p>使用8KB固定缓冲区逐块读取文件，避免Files.readAllBytes导致的内存峰值。</p>
     *
     * @param filePath 文件路径
     * @return CRC32哈希值（8位十六进制字符串），文件不存在或读取失败返回 "00000000"
     */
    public static String computeFileHash(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) {
            return DEFAULT_HASH;
        }

        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[8192];  // 8KB 固定缓冲区

        try (InputStream is = Files.newInputStream(filePath)) {
            int len;
            while ((len = is.read(buffer)) != -1) {
                crc32.update(buffer, 0, len);
            }
            return String.format("%08x", crc32.getValue());
        } catch (IOException e) {
            LOGGER.warn("Failed to compute hash for {}", filePath, e);
            return DEFAULT_HASH;
        }
    }

    /**
     * 计算字节数组的CRC32哈希值
     *
     * @param data 字节数组
     * @return CRC32哈希值（8位十六进制字符串）
     */
    public static String computeHash(byte[] data) {
        if (data == null || data.length == 0) {
            return DEFAULT_HASH;
        }

        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return String.format("%08x", crc32.getValue());
    }

    /**
     * 检查哈希值是否有效（非默认值）
     *
     * @param hash 哈希值
     * @return true 如果哈希值有效
     */
    public static boolean isValidHash(String hash) {
        return hash != null && !hash.isEmpty() && !DEFAULT_HASH.equals(hash);
    }
}