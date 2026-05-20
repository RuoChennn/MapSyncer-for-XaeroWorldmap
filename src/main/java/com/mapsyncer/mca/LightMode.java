package com.mapsyncer.mca;

/**
 * 光照模式枚举
 *
 * <p>定义两种光照计算模式，模拟 Xaero WorldMap 的光照处理逻辑:</p>
 *
 * <p>地表模式 (SURFACE):</p>
 * <ul>
 *   <li>只使用 BlockLight（方块光照）</li>
 *   <li>SkyLight 完全忽略</li>
 *   <li>适用于普通地表地图渲染</li>
 *   <li>洞穴、地下室等区域显示较暗</li>
 * </ul>
 *
 * <p>洞穴模式 (CAVE):</p>
 * <ul>
 *   <li>同时使用 BlockLight 和 SkyLight</li>
 *   <li>取两者的最大值作为有效光照</li>
 *   <li>当方块光照 < 15 且有 SkyLight 时，考虑天空光照</li>
 *   <li>高于高度图的位置 SkyLight = 15（直接日照）</li>
 *   <li>模拟洞穴中透过水面看到阳光的效果</li>
 * </ul>
 *
 * @see DimensionTypeInfo 用于确定维度的天空光照属性
 * @see ChunkSectionParser.LightData 用于解析光照数据
 */
public enum LightMode {

    /**
     * 地表模式 - 只使用 BlockLight
     *
     * <p>光照计算规则：</p>
     * <ul>
     *   <li>lightLevels = BlockLight 值</li>
     *   <li>SkyLight 完全忽略</li>
     *   <li>发光方块强制 light = 15</li>
     * </ul>
     *
     * <p>适用场景：普通地表地图</p>
     */
    SURFACE,

    /**
     * 洞穴模式 - 取 BlockLight 和 SkyLight 的最大值
     *
     * <p>光照计算规则（参考 Xaero WorldDataReader:537-561）:</p>
     * <ul>
     *   <li>默认 lightLevels = 0, skyLightLevels = 15（有天空的维度）</li>
     *   <li>当 BlockLight < 15 且有 SkyLight 时，记录 SkyLight</li>
     *   <li>最终光照 = max(BlockLight, SkyLight)</li>
     *   <li>高于高度图位置 SkyLight = 15</li>
     *   <li>无 overlay 且 SkyLight > BlockLight 时使用 SkyLight</li>
     * </ul>
     *
     * <p>适用场景：洞穴地图、地下水查看</p>
     */
    CAVE;

    /**
     * 计算有效光照值
     *
     * <p>参考 Xaero WorldDataReader.java 光照处理逻辑:</p>
     * <ul>
     *   <li>第186行：worldHasSkylight = serverWorld.dimensionType().hasSkyLight()</li>
     *   <li>第353行：skyLightLevels[i] = worldHasSkylight ? 15 : 0</li>
     *   <li>第557-559行：cave && dataLight < 15 && worldHasSkylight 时更新 skyLightLevels</li>
     * </ul>
     *
     * <p>末地维度特性：</p>
     * <ul>
     *   <li>worldHasSkylight = false（末地没有天空光照）</li>
     *   <li>skyLightLevels 初始化为 0（而不是 15）</li>
     *   <li>不会在光照计算中使用 skyLight = 15 作为默认值</li>
     * </ul>
     *
     * @param blockLight 方块光照值 (0-15)
     * @param skyLight 天空光照值 (0-15)
     * @param hasSkyAccess 是否有天空访问（位置高于高度图）
     * @param hasOverlay 是否有覆盖层（水、玻璃等透明方块）
     * @param isGlowing 是否为发光方块
     * @param worldHasSkylight 维度是否有天空光照（末地为 false）
     * @return 有效光照值 (0-15)
     */
    public byte calculateEffectiveLight(byte blockLight, byte skyLight,
                                         boolean hasSkyAccess, boolean hasOverlay,
                                         boolean isGlowing, boolean worldHasSkylight) {
        // 发光方块强制光照15
        if (isGlowing) {
            return 15;
        }

        switch (this) {
            case SURFACE:
                // 地表模式：只使用 BlockLight
                return blockLight;

            case CAVE:
                // 洞穴模式：取 max(BlockLight, SkyLight)
                if (blockLight >= 15) {
                    return blockLight;
                }

                // 参考 Xaero: 只有在有天空光照的维度，有天空访问时才使用 SkyLight = 15
                // 末地维度 worldHasSkylight = false，所以不会使用 15
                byte effectiveSkyLight = (hasSkyAccess && worldHasSkylight) ? 15 : skyLight;

                // 无 overlay 且 SkyLight 更亮时使用 SkyLight
                if (!hasOverlay && effectiveSkyLight > blockLight) {
                    return effectiveSkyLight;
                }

                // 否则返回 BlockLight（水下等场景）
                return blockLight;

            default:
                return blockLight;
        }
    }

    /**
     * 获取默认 SkyLight 值
     *
     * <p>根据光照模式和维度属性返回默认的天空光照值:</p>
     * <ul>
     *   <li>地表模式: 返回 0（不使用 SkyLight）</li>
     *   <li>洞穴模式: 如果维度有天空光照返回 15，否则返回 0</li>
     * </ul>
     *
     * @param worldHasSkylight 世界是否有天空光照
     * @return 默认 SkyLight 值（0 或 15）
     */
    public byte getDefaultSkyLight(boolean worldHasSkylight) {
        switch (this) {
            case SURFACE:
                return (byte) 0;  // 地表模式不使用 SkyLight
            case CAVE:
                return worldHasSkylight ? (byte) 15 : (byte) 0;
            default:
                return (byte) 0;
        }
    }

    /**
     * 判断是否需要 SkyLight 数据
     *
     * <p>只有洞穴模式需要 SkyLight 数据进行光照计算</p>
     *
     * @return 如果需要 SkyLight 数据则返回 true
     */
    public boolean needsSkyLightData() {
        return this == CAVE;
    }
}