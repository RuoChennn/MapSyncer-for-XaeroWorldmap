package com.mapsyncer.mca;

import java.util.Map;

/**
 * 方块属性查询接口 - 用于解耦 MCA 转换器与平台特定的方块注册表
 *
 * <p>各平台模块（Fabric/NeoForge）通过实现此接口，
 * 将 Minecraft 运行时的方块属性查询暴露给通用的 MCA 转换器。</p>
 */
public interface BlockPropertyLookup {

    boolean isWater(String blockName);

    boolean isTransparent(String blockName);

    boolean isInvisible(String blockName);

    boolean shouldOverlay(String blockName);

    boolean hasVanillaColor(String blockName);

    boolean isGrassBlock(String blockName);

    boolean isGlowing(String blockName);

    boolean isTranslucentFluid(String blockName);

    boolean isWaterloggedSurface(String blockName, Map<String, String> properties);

    /**
     * 判断方块是否为水生植物（Water-inheriting）
     *
     * <p>水生植物（海草、海带等）在水中生长，但 NBT 中不存储 waterlogged 属性。
     * 它们应继承上方水体的 overlay，渲染为：水 overlay + 植物表面。</p>
     *
     * @param blockName 方块注册表名称
     * @return true 表示是水生植物
     */
    boolean isWaterInheriting(String blockName);

    int getLightBlock(String blockName);
}
