package com.mapsyncer.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 版本适配 — ResourceKey API 差异封装。
 * MC 1.21.11+ 使用 {@code identifier()} 替代 {@code location()}。
 */
public final class DimensionApiHelper {

    private DimensionApiHelper() {}

    public static String getDimId(ResourceKey<Level> key) {
        return key.identifier().toString();
    }
}
