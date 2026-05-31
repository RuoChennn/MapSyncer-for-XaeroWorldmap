package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Xaero's World Map 数据目录自动检测。
 *
 * <p>不同版本的 Xaero 使用不同的存储路径：</p>
 * <ul>
 *   <li>1.20.x: {@code <gameDir>/XaeroWorldMap/}</li>
 *   <li>1.21.x+: {@code <gameDir>/xaero/world-map/}</li>
 * </ul>
 *
 * <p>此类不依赖任何 Minecraft API，可在所有版本和所有 Loader 中使用。</p>
 */
public final class XaeroPathResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroPathResolver.class);

    private XaeroPathResolver() {}

    /**
     * 自动检测 Xaero's World Map 数据目录。
     *
     * <p>优先检查旧版路径（1.20.x），再检查新版路径（1.21.x+），
     * 两者都不存在时默认返回新版路径。</p>
     *
     * @param gameDir 游戏根目录（.minecraft）
     * @return Xaero World Map 数据目录路径
     */
    public static Path getWorldMapDir(Path gameDir) {
        Path legacy = gameDir.resolve("XaeroWorldMap");
        if (Files.isDirectory(legacy)) {
            LOGGER.debug("Detected Xaero WorldMap legacy path: {}", legacy);
            return legacy;
        }
        Path modern = gameDir.resolve("xaero").resolve("world-map");
        if (Files.isDirectory(modern)) {
            LOGGER.debug("Detected Xaero WorldMap modern path: {}", modern);
            return modern;
        }
        LOGGER.debug("No Xaero WorldMap directory found, defaulting to: {}", modern);
        return modern;
    }
}
