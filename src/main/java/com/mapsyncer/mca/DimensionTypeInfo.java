package com.mapsyncer.mca;

/**
 * 维度类型信息记录
 *
 * <p>存储 Minecraft 维度类型的核心属性，用于光照计算和世界高度范围确定。</p>
 *
 * <p>参考 Minecraft Wiki 维度类型：<a href="https://minecraft.wiki/w/Dimension_type">https://minecraft.wiki/w/Dimension_type</a></p>
 *
 * <p>关键属性：</p>
 * <ul>
 *   <li>hasSkylight: 是否有天空光照（影响光照计算）</li>
 *   <li>hasCeiling: 是否有顶棚（地狱有顶棚）</li>
 *   <li>minY: 最小建筑高度（世界底部 Y 坐标）</li>
 *   <li>height: 维度总高度（minY + height = 最大建筑高度）</li>
 *   <li>logicalHeight: 逻辑高度（实际可操作高度，可能小于 height）</li>
 * </ul>
 *
 * <p>原版维度默认值：</p>
 * <table border="1">
 *   <tr><th>维度</th><th>hasSkylight</th><th>hasCeiling</th><th>minY</th><th>height</th></tr>
 *   <tr><td>Overworld</td><td>true</td><td>false</td><td>-64</td><td>384</td></tr>
 *   <tr><td>Nether</td><td>false</td><td>true</td><td>0</td><td>256</td></tr>
 *   <tr><td>End</td><td>false</td><td>false</td><td>0</td><td>256</td></tr>
 * </table>
 *
 * @param hasSkylight 是否有天空光照（影响光照计算）
 * @param hasCeiling 是否有顶棚（地狱有顶棚，影响洞穴扫描）
 * @param minY 最小建筑高度（世界底部 Y 坐标）
 * @param height 维度总高度
 * @param logicalHeight 逻辑高度（实际可操作高度）
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
     *
     * @return 维度的最大建筑高度（世界顶部 Y 坐标）
     */
    public int maxY() {
        return minY + height;
    }

    /**
     * 创建默认的主世界维度类型信息
     *
     * <p>主世界特性：有天空光照、无顶棚、高度范围 -64 到 320</p>
     *
     * @return 主世界的维度类型信息实例
     */
    public static DimensionTypeInfo overworld() {
        return new DimensionTypeInfo(true, false, -64, 384, 384);
    }

    /**
     * 创建默认的地狱维度类型信息
     *
     * <p>地狱特性：无天空光照、有顶棚、高度范围 0 到 256</p>
     *
     * @return 地狱的维度类型信息实例
     */
    public static DimensionTypeInfo nether() {
        return new DimensionTypeInfo(false, true, 0, 256, 256);
    }

    /**
     * 创建默认的末地维度类型信息
     *
     * <p>末地特性：无天空光照、无顶棚、高度范围 0 到 256</p>
     *
     * @return 末地的维度类型信息实例
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
     * <p>参考 Xaero WorldDataReader:353 行:</p>
     * <ul>
     *   <li>有天空光照的维度：skyLightLevels[i] = 15</li>
     *   <li>无天空光照的维度：skyLightLevels[i] = 0</li>
     * </ul>
     *
     * @return 默认 SkyLight 值（有天空光照返回15，否则返回0）
     */
    public byte getDefaultSkyLight() {
        return hasSkylight ? (byte) 15 : (byte) 0;
    }

    /**
     * 判断是否为洞穴型维度（有顶棚）
     *
     * <p>洞穴型维度通常需要使用 CAVE 模式扫描</p>
     * <p>地狱是典型的洞穴型维度</p>
     *
     * @return 如果维度有顶棚则返回true
     */
    public boolean isCaveDimension() {
        return hasCeiling;
    }

    /**
     * 计算洞穴扫描的推荐起始高度
     *
     * <p>对于有顶棚的维度（地狱），推荐从 ceiling 下方开始</p>
     * <p>对于普通维度，推荐从 sea level (63) 开始</p>
     *
     * @return 推荐的洞穴扫描起始高度（世界 Y 坐标）
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
     *
     * <p>格式："hasSkylight|hasCeiling|minY|height|logicalHeight"</p>
     * <p>用于配置文件存储和传输</p>
     *
     * @return 配置字符串表示形式
     */
    public String toConfigString() {
        return hasSkylight + "|" + hasCeiling + "|" + minY + "|" + height + "|" + logicalHeight;
    }

    /**
     * 从配置字符串解析维度类型信息
     *
     * <p>格式："hasSkylight|hasCeiling|minY|height|logicalHeight"</p>
     * <p>如果字符串无效或格式不正确，返回主世界默认值</p>
     *
     * @param configStr 配置字符串
     * @return 解析后的维度类型信息实例
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

    /**
     * 获取维度类型信息的字符串表示形式
     *
     * @return 格式化的字符串，包含所有属性值
     */
    @Override
    public String toString() {
        return String.format("DimensionTypeInfo[hasSkylight=%s, hasCeiling=%s, minY=%d, height=%d, maxY=%d]",
            hasSkylight, hasCeiling, minY, height, maxY());
    }
}