package com.mapsyncer.util;

import com.mapsyncer.mca.DimensionTypeInfo;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 维度类型辅助类
 *
 * 提供 Minecraft API 到 DimensionTypeInfo 的转换方法。
 * 此类依赖 Minecraft API，只能在平台实现中使用。
 */
public final class DimensionTypeHelper {

    private DimensionTypeHelper() {
        // 私有构造器
    }

    /**
     * 从 Minecraft DimensionType API 创建 DimensionTypeInfo
     *
     * @param dimensionType Minecraft DimensionType 实例
     * @return 对应的维度类型信息
     */
    public static DimensionTypeInfo fromDimensionType(DimensionType dimensionType) {
        return new DimensionTypeInfo(
            dimensionType.hasSkyLight(),
            dimensionType.hasCeiling(),
            dimensionType.minY(),
            dimensionType.height(),
            dimensionType.logicalHeight()
        );
    }
}
