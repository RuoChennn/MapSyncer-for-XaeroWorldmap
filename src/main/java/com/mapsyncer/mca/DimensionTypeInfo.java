package com.mapsyncer.mca;

/**
 * 维度类型信息
 *
 * 存储 Minecraft 维度类型的核心属性，用于光照计算和世界高度范围确定。
 *
 * 参考 Minecraft Wiki 维度类型：
 * https://minecraft.wiki/w/Dimension_type
 *
 * 关键属性：
 * - hasSkylight: 是否有天空光照（影响光照计算）
 * - hasCeiling: 是否有顶棚（地狱有顶棚）
 * - minY: 最小建筑高度（世界底部 Y 坐标）
 * - height: 维度总高度（minY + height = 最大建筑高度）
 * - logicalHeight: 逻辑高度（实际可操作高度，可能小于 height）
 *
 * 原版维度默认值：
 * | 维度      | hasSkylight | hasCeiling | minY | height |
 * |-----------|-------------|------------|------|--------|
 * | Overworld | true        | false      | -64  | 384    |
 * | Nether    | false       | true       | 0    | 256    |
 * | End       | false       | false      | 0    | 256    |
 */
public record DimensionTypeInfo(
    boolean hasSkylight,      // 是否有天空光照
    boolean hasCeiling,       // 是否有顶棚
    int minY,                 // 最小建筑高度（世界底部 Y）
    int height,               // 维度总高度
    int logicalHeight         // 逻辑高度
) {

    /**
     * 获取最大建筑高度（minY + height）
     */
    public int maxY() {
        return minY + height;
    }

    /**
     * 获取世界高度范围（height）
     */
    public int worldHeightRange() {
        return height;
    }

    /**
     * 创建默认的主世界维度类型信息
     */
    public static DimensionTypeInfo overworld() {
        return new DimensionTypeInfo(true, false, -64, 384, 384);
    }

    /**
     * 创建默认的地狱维度类型信息
     */
    public static DimensionTypeInfo nether() {
        return new DimensionTypeInfo(false, true, 0, 256, 256);
    }

    /**
     * 创建默认的末地维度类型信息
     */
    public static DimensionTypeInfo theEnd() {
        return new DimensionTypeInfo(false, false, 0, 256, 256);
    }

    /**
     * 根据维度 ID 获取预设的维度类型信息
     *
     * @param dimensionId 维度 ID（如 "minecraft:overworld", "minecraft:the_nether", "the_end"）
     * @return 对应的维度类型信息，未知维度返回主世界默认值
     */
    public static DimensionTypeInfo fromDimensionId(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return overworld();
        }

        String normalized = dimensionId
            .replace("minecraft:", "")
            .toLowerCase();

        switch (normalized) {
            case "overworld":
                return overworld();
            case "the_nether":
                return nether();
            case "the_end":
                return theEnd();
            default:
                // 未知维度使用主世界默认值
                return overworld();
        }
    }

    /**
     * 从 Minecraft DimensionType API 创建
     * （需要运行时环境，用于服务端地图生成）
     *
     * @param dimensionType Minecraft DimensionType 实例
     * @return 对应的维度类型信息
     */
    public static DimensionTypeInfo fromDimensionType(net.minecraft.world.level.dimension.DimensionType dimensionType) {
        return new DimensionTypeInfo(
            dimensionType.hasSkyLight(),
            dimensionType.hasCeiling(),
            dimensionType.minY(),
            dimensionType.height(),
            dimensionType.logicalHeight()
        );
    }

    /**
     * 获取默认 SkyLight 值
     *
     * 参考 Xaero WorldDataReader:353
     * - 有天空光照的维度：skyLightLevels[i] = 15
     * - 无天空光照的维度：skyLightLevels[i] = 0
     *
     * @return 默认 SkyLight 值（0-15）
     */
    public byte getDefaultSkyLight() {
        return hasSkylight ? (byte) 15 : (byte) 0;
    }

    /**
     * 是否为洞穴型维度（有顶棚）
     *
     * 洞穴型维度通常需要使用 CAVE 模式扫描
     * 地狱是典型的洞穴型维度
     */
    public boolean isCaveDimension() {
        return hasCeiling;
    }

    /**
     * 是否需要使用 SkyLight 数据进行光照计算
     *
     * 参考 Xaero WorldDataReader:557-559
     * - 只有在有天空光照的维度才考虑 SkyLight
     * - 末地维度 hasSkylight = false，不使用 SkyLight = 15 作为默认值
     */
    public boolean needsSkyLightForLighting() {
        return hasSkylight;
    }

    /**
     * 计算洞穴扫描的推荐起始高度
     *
     * 对于有顶棚的维度（地狱），推荐从 ceiling 下方开始
     * 对于普通维度，推荐从 sea level (63) 开始
     */
    public int getRecommendedCaveStart() {
        if (hasCeiling) {
            // 地狱：ceiling 约在 Y=128，推荐从 63 开始向下扫描
            return Math.max(minY + 32, (minY + height) / 2 - 32);
        }
        // 普通维度：从 sea level 开始
        return Math.max(minY, 63);
    }

    /**
     * 转换为配置字符串格式
     * 格式："hasSkylight|hasCeiling|minY|height|logicalHeight"
     */
    public String toConfigString() {
        return hasSkylight + "|" + hasCeiling + "|" + minY + "|" + height + "|" + logicalHeight;
    }

    /**
     * 从配置字符串解析
     * 格式："hasSkylight|hasCeiling|minY|height|logicalHeight"
     */
    public static DimensionTypeInfo fromConfigString(String configStr) {
        if (configStr == null || configStr.isEmpty()) {
            return overworld();
        }

        String[] parts = configStr.split("\\|");
        if (parts.length < 4) {
            return overworld();
        }

        try {
            boolean hasSkylight = Boolean.parseBoolean(parts[0]);
            boolean hasCeiling = Boolean.parseBoolean(parts[1]);
            int minY = Integer.parseInt(parts[2]);
            int height = Integer.parseInt(parts[3]);
            int logicalHeight = parts.length > 4 ? Integer.parseInt(parts[4]) : height;

            return new DimensionTypeInfo(hasSkylight, hasCeiling, minY, height, logicalHeight);
        } catch (NumberFormatException e) {
            return overworld();
        }
    }

    @Override
    public String toString() {
        return String.format("DimensionTypeInfo[hasSkylight=%s, hasCeiling=%s, minY=%d, height=%d, maxY=%d]",
            hasSkylight, hasCeiling, minY, height, maxY());
    }
}