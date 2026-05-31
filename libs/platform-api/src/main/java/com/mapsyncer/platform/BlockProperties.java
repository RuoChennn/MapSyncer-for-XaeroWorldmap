package com.mapsyncer.platform;

/**
 * 方块属性集合
 *
 * 存储方块的各种属性信息，用于地图渲染判断。
 * 此记录类是平台无关的，各平台实现负责从 BlockState 提取这些属性。
 */
public record BlockProperties(
    boolean isAir,
    boolean isWater,
    boolean isLava,
    boolean isFluid,
    boolean isTransparent,
    boolean isInvisible,
    boolean isFlower,
    boolean isPlant,
    boolean isGrassBlock,
    boolean isGlowing,
    int lightBlock,
    int lightEmission,
    boolean canBeWaterlogged,
    int mapColor
) {
    /**
     * 空属性（默认值）
     */
    public static final BlockProperties EMPTY = new BlockProperties(
        false, false, false, false, false, false, false, false,
        false, false, 15, 0, false, 0x808080
    );

    /**
     * 判断是否应该作为 overlay 处理
     * overlay 方块（水、透明方块）会渲染在下层方块之上
     */
    public boolean shouldOverlay() {
        return isWater || isTransparent;
    }

    /**
     * 判断是否为透明流体（水）
     */
    public boolean isTranslucentFluid() {
        return isWater;
    }

    /**
     * 判断是否为含水方块表面
     *
     * @param waterlogged 是否含水属性为 true
     * @return 如果是含水方块表面返回 true
     */
    public boolean isWaterloggedSurface(boolean waterlogged) {
        return canBeWaterlogged && waterlogged && !isWater && !isAir;
    }
}