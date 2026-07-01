package com.mapsyncer.config;

import java.nio.file.Path;

/**
 * 生成缓存与 Xaero 目录路径辅助。
 */
public final class ConversionOutputPaths {

    private ConversionOutputPaths() {}

    public static Path outputDir(Path baseOutputDir, int caveLayer) {
        if (caveLayer == Integer.MAX_VALUE) {
            return baseOutputDir;
        }
        return baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
    }

    public static String relativePath(String xaeroDimName, int caveLayer, int regionX, int regionZ) {
        if (caveLayer == Integer.MAX_VALUE) {
            return xaeroDimName + "/" + regionX + "_" + regionZ;
        }
        return xaeroDimName + "/caves/" + caveLayer + "/" + regionX + "_" + regionZ;
    }
}
