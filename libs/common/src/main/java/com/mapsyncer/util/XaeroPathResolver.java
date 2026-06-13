package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Xaero's World Map 数据目录自动检测。
 *
 * <p>新版 Xaero 统一使用 {@code <gameDir>/xaero/world-map/} 路径，
 * 旧版使用 {@code <gameDir>/XaeroWorldMap/} 作为 fallback。</p>
 *
 * <p>此类不依赖任何 Minecraft API，可在所有版本和所有 Loader 中使用。</p>
 */
public final class XaeroPathResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroPathResolver.class);

    private XaeroPathResolver() {}

    /**
     * 自动检测 Xaero's World Map 数据目录。
     *
     * <p>优先使用新版统一路径 {@code xaero/world-map}，
     * 若不存在则 fallback 到旧版 {@code XaeroWorldMap}，
     * 两者都不存在时默认返回新版路径。</p>
     *
     * @param gameDir 游戏根目录（.minecraft）
     * @return Xaero World Map 数据目录路径
     */
    public static Path getWorldMapDir(Path gameDir) {
        Path modern = gameDir.resolve("xaero").resolve("world-map");
        if (Files.isDirectory(modern)) {
            LOGGER.debug("Detected Xaero WorldMap path: {}", modern);
            return modern;
        }
        Path legacy = gameDir.resolve("XaeroWorldMap");
        if (Files.isDirectory(legacy)) {
            LOGGER.debug("Detected Xaero WorldMap legacy path: {}", legacy);
            return legacy;
        }
        LOGGER.debug("No Xaero WorldMap directory found, defaulting to: {}", modern);
        return modern;
    }
}
