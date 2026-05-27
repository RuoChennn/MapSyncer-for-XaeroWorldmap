package com.mapsyncer.server;

import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Xaero地图文件写入器 - 将转换后的区域数据写入Xaero兼容的zip文件
 *
 * 输出格式：{outputDir}/{regionX}_{regionZ}.zip，包含一个"region.xaero"条目。
 * 使用临时文件+原子替换的方式写入，确保文件完整性。
 */
public class XaeroWriter {

    /**
     * 将转换后的区域数据写入zip文件
     *
     * @param outputDir 输出目录路径
     * @param region 转换后的区域数据
     * @return 写入的zip文件路径
     * @throws IOException 如果写入过程中发生IO错误
     */
    public static Path writeRegionFile(Path outputDir, ConvertedRegion region) throws IOException {
        Files.createDirectories(outputDir);

        String fileName = region.regionX() + "_" + region.regionZ();
        Path tempFile = outputDir.resolve(fileName + ".zip.temp");
        Path finalFile = outputDir.resolve(fileName + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempFile))) {
            ZipEntry entry = new ZipEntry("region.xaero");
            zos.putNextEntry(entry);
            zos.write(region.xaeroData());
            zos.closeEntry();
        }

        Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
        return finalFile;
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
