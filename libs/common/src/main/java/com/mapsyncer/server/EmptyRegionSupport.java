package com.mapsyncer.server;

import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 空 region 清理：生成跳过时不写 cache，并删除历史残留的 zip / generation_cache 条目。
 */
public final class EmptyRegionSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmptyRegionSupport.class);

    private EmptyRegionSupport() {}

    public static boolean isEmptyConverted(ConvertedRegion converted) {
        return converted == null || converted.xaeroData() == null || converted.xaeroData().length == 0;
    }

    /**
     * 删除输出 zip 及 generation_cache 中对应条目。
     */
    public static void purgeGeneratedArtifacts(Path outputDir, int regionX, int regionZ,
            String relativePath, GenerationCache genCache) {
        Path zip = outputDir.resolve(regionX + "_" + regionZ + ".zip");
        Path temp = outputDir.resolve(regionX + "_" + regionZ + ".zip.temp");
        try {
            Files.deleteIfExists(zip);
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete empty region zip {}: {}", zip, e.getMessage());
        }
        if (genCache != null && relativePath != null) {
            genCache.remove(relativePath);
        }
        LOGGER.debug("Purged empty region artifacts for {}", relativePath);
    }
}
