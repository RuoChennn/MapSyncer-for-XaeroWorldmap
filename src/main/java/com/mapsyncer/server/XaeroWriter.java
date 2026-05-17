package com.mapsyncer.server;

import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes converted region data as Xaero-compatible .zip files.
 * Output format: {outputDir}/{regionX}_{regionZ}.zip containing a "region.xaero" entry.
 */
public class XaeroWriter {

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

    public static boolean regionFileExists(Path outputDir, int regionX, int regionZ) {
        Path zipFile = outputDir.resolve(regionX + "_" + regionZ + ".zip");
        return Files.exists(zipFile);
    }

    public static byte[] readRegionFile(Path outputDir, int regionX, int regionZ) throws IOException {
        Path zipFile = outputDir.resolve(regionX + "_" + regionZ + ".zip");
        if (!Files.exists(zipFile)) {
            return null;
        }
        return Files.readAllBytes(zipFile);
    }
}
