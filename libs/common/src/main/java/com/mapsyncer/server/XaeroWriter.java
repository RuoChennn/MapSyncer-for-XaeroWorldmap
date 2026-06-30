package com.mapsyncer.server;

import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CheckedOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xaero地图文件写入器 - 将转换后的区域数据写入Xaero兼容的zip文件
 *
 * 输出格式：{outputDir}/{regionX}_{regionZ}.zip，包含一个"region.xaero"条目。
 * 使用临时文件+原子替换的方式写入，确保文件完整性。
 */
public class XaeroWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroWriter.class);

    /** 超过此时间的 .temp 残留文件视为可安全删除（毫秒） */
    private static final long TEMP_FILE_MAX_AGE_MS = 24 * 60 * 60 * 1000;

    /**
     * 清理残留的 .zip.temp 文件。
     *
     * <p>正常写入通过原子 rename 完成，JVM 崩溃后 .temp 文件会残留。
     * 此方法删除超过 {@link #TEMP_FILE_MAX_AGE_MS} 的残留文件，
     * 确保不会误删正在进行的写入。</p>
     *
     * @param rootDir 缓存根目录（会递归扫描）
     * @return 清理的文件数量
     */
    public static int cleanStaleTempFiles(Path rootDir) {
        if (!Files.exists(rootDir)) return 0;
        long cutoff = System.currentTimeMillis() - TEMP_FILE_MAX_AGE_MS;
        int[] count = {0};
        try (var stream = Files.walk(rootDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".zip.temp"))
                  .forEach(p -> {
                      try {
                          if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                              Files.deleteIfExists(p);
                              count[0]++;
                              LOGGER.debug("Cleaned stale temp file: {}", p);
                          }
                      } catch (IOException ignored) {
                          // 文件可能已被其他进程删除
                      }
                  });
        } catch (IOException e) {
            LOGGER.warn("Failed to scan for stale temp files in {}", rootDir, e);
        }
        if (count[0] > 0) {
            LOGGER.info("Cleaned {} stale .temp files from {}", count[0], rootDir);
        }
        return count[0];
    }

    /**
     * 区域 zip 写入结果（路径 + 写入时计算的 CRC32，与 {@link com.mapsyncer.util.HashUtils} 读盘结果一致）。
     */
    public record RegionWriteResult(Path path, String crc32Hash) {}

    /**
     * 将转换后的区域数据写入 zip 文件，并在单次写盘中计算 CRC32。
     *
     * @param outputDir 输出目录路径
     * @param region 转换后的区域数据
     * @return 最终文件路径与 CRC32 哈希（8 位十六进制）
     * @throws IOException 如果写入过程中发生 IO 错误
     */
    public static RegionWriteResult writeRegionFile(Path outputDir, ConvertedRegion region) throws IOException {
        Files.createDirectories(outputDir);

        String fileName = region.regionX() + "_" + region.regionZ();
        Path tempFile = outputDir.resolve(fileName + ".zip.temp");
        Path finalFile = outputDir.resolve(fileName + ".zip");

        CRC32 crc32 = new CRC32();
        try (OutputStream fileOut = Files.newOutputStream(tempFile);
             CheckedOutputStream checkedOut = new CheckedOutputStream(fileOut, crc32);
             ZipOutputStream zos = new ZipOutputStream(checkedOut)) {
            ZipEntry entry = new ZipEntry("region.xaero");
            zos.putNextEntry(entry);
            zos.write(region.xaeroData());
            zos.closeEntry();
        }

        Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
        return new RegionWriteResult(finalFile, String.format("%08x", crc32.getValue()));
    }

    /**
     * 检查区域文件是否已存在
     *
     * @param outputDir 输出目录路径
     * @param regionX 区域X坐标
     * @param regionZ 区域Z坐标
     * @return true表示文件存在，false表示不存在
     */
    public static boolean regionFileExists(Path outputDir, int regionX, int regionZ) {
        Path zipFile = outputDir.resolve(regionX + "_" + regionZ + ".zip");
        return Files.exists(zipFile);
    }
}
