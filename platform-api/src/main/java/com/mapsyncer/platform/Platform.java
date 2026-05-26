package com.mapsyncer.platform;

import com.mapsyncer.mca.DimensionTypeInfo;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Set;

/**
 * 平台抽象接口
 *
 * 定义所有模组加载器平台需要实现的核心功能接口。
 * 业务逻辑通过此接口与平台交互，实现跨平台兼容。
 */
public interface Platform {

    // ===== 平台信息 =====

    /**
     * 获取平台类型
     */
    PlatformType getType();

    /**
     * 获取 Minecraft 版本字符串
     */
    String getMinecraftVersion();

    /**
     * 获取主版本号
     * 例如：20 for 1.20.x, 26 for 26.x
     */
    int getMajorVersion();

    /**
     * 获取平台名称（用于日志和显示）
     */
    String getPlatformName();

    // ===== 方块属性 =====

    /**
     * 通过方块名称获取方块属性
     *
     * @param blockName 方块注册名（如 "minecraft:stone"）
     * @return 方块属性集合
     */
    BlockProperties getBlockProperties(String blockName);

    /**
     * 获取方块名称模式匹配的颜色
     * 用于无法获取实际 BlockState 时的备用方案
     *
     * @param blockName 方块注册名
     * @return 预估的颜色值（RGB）
     */
    int getPatternColor(String blockName);

    // ===== 世界信息 =====

    /**
     * 获取世界最低建筑高度
     * 1.20+: -64, 1.12: 0
     */
    int getDefaultMinBuildHeight();

    /**
     * 获取世界最高建筑高度
     * 1.20+: 320, 1.12: 256
     */
    int getDefaultMaxBuildHeight();

    // ===== 维度信息 =====

    /**
     * 获取维度的 Xaero 目录名称
     *
     * @param dimensionId Minecraft 维度 ID（如 "minecraft:overworld"）
     * @return Xaero 目录名（如 "null", "DIM-1", "DIM1"）
     */
    String getXaeroDimensionPath(String dimensionId);

    /**
     * 获取维度类型信息
     *
     * @param dimensionId Minecraft 维度 ID
     * @return 维度类型信息（光照、高度范围等）
     */
    DimensionTypeInfo getDimensionTypeInfo(String dimensionId);

    // ===== 配置系统 =====

    /**
     * 获取同步速度限制（KB/s）
     */
    int getSyncSpeedLimitKBps();

    /**
     * 获取最大数据包大小（字节）
     */
    int getMaxSyncPacketSize();

    /**
     * 获取最大并发区域数
     */
    int getMaxConcurrentRegions();

    /**
     * 获取是否启用调试日志
     */
    boolean isDebugLoggingEnabled();

    /**
     * 获取增量更新模式
     */
    UpdateMode getIncrementalUpdateMode();

    /**
     * 获取增量更新间隔（ticks）
     */
    int getIncrementalUpdateIntervalTicks();

    /**
     * 获取定时更新小时
     */
    int getScheduledUpdateHour();

    /**
     * 获取定时更新分钟
     */
    int getScheduledUpdateMinute();

    // ===== 文件路径 =====

    /**
     * 获取服务端地图缓存目录
     */
    Path getServerMapCacheDir();

    /**
     * 获取客户端 Xaero World Map 目录
     */
    Path getClientXaeroWorldMapDir();

    /**
     * 获取当前服务器目录名（用于客户端）
     */
    String getCurrentServerDirectoryName();

    // ===== 日志 =====

    /**
     * 获取平台日志器
     */
    Logger getLogger();

    // ===== 工具方法 =====

    /**
     * 检查方块名称是否匹配指定模式
     *
     * @param blockName 方块名称
     * @param pattern 模式（如 "_ore", "stone"）
     * @return 是否匹配
     */
    boolean matchesBlockPattern(String blockName, String pattern);

    /**
     * 解析方块名称中的属性
     *
     * @param blockStateString 方块状态字符串（如 "minecraft:stone[waterlogged=true]"）
     * @return 属性键值对 Map
     */
    java.util.Map<String, String> parseBlockProperties(String blockStateString);

    /**
     * 记录同步更新的区域坐标
     *
     * @param regions 区域坐标集合
     */
    void recordUpdatedRegions(Set<RegionCoord> regions);

    /**
     * 区域坐标记录
     */
    record RegionCoord(int x, int z, int caveLayer) {
        public RegionCoord(int x, int z) {
            this(x, z, Integer.MAX_VALUE);
        }

        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }
}