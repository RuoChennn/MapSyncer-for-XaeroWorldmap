package com.mapsyncer.platform;

import com.mapsyncer.config.DimensionScanConfig;
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
     * 当前 Loader 下的服务端命令字面量（不含 /）。
     * Fabric：{@code mapsyncerserver}；Forge / NeoForge：{@code mapsyncer}。
     */
    String getServerCommandPrefix();

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

    /**
     * 检查当前环境是否为客户端
     *
     * @return 如果是客户端环境返回 true
     */
    boolean isClientEnvironment();

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
     * 获取维度扫描配置
     *
     * @param dimensionPath 维度路径（如 "the_nether" 或 "minecraft:the_nether"）
     * @return 维度扫描配置
     */
    DimensionScanConfig getConfigForDimension(String dimensionPath);

    /**
     * 获取同步速度限制（KB/s）
     */
    int getSyncSpeedLimitKBps();

    /**
     * 获取最大数据包大小（字节）
     */
    int getMaxSyncPacketSize();

    /**
     * 获取实际并发区域转换数（已解析：配置 0 为自动）
     */
    int getMaxConcurrentRegions();

    /**
     * 获取是否启用调试日志
     */
    boolean isDebugLoggingEnabled();

    /**
     * 获取客户端哈希计算线程数
     *
     * @return 线程数
     */
    int getClientHashThreads();

    /**
     * 获取客户端每 tick 加载的地图区域数。
     *
     * <p>用于限速同步期间视距外 region 加载到 Xaero MapProcessor：</p>
     * <ul>
     *   <li>-1 = 不限制（同步完成时一次排空队列）</li>
     *   <li>0 = 仅加载视距内 region</li>
     *   <li>1-100 = 每 N 个客户端 tick 向 Xaero 传入 1 个视距外 region</li>
     * </ul>
     *
     * @return 加载间隔（tick 数）
     */
    int getMapRegionLoadIntervalTicks();

    /**
     * 客户端是否启用自动同步（进服/在线周期）。
     */
    boolean isClientAutoSyncEnabled();

    /**
     * 设置客户端自动同步开关并持久化到配置文件。
     */
    void setClientAutoSyncEnabled(boolean enabled);

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

    /**
     * 设置增量更新模式
     */
    void setIncrementalUpdateMode(UpdateMode mode);

    /**
     * 设置增量更新间隔（ticks）
     */
    void setIncrementalUpdateIntervalTicks(int interval);

    /**
     * 设置定时更新小时
     */
    void setScheduledUpdateHour(int hour);

    /**
     * 设置定时更新分钟
     */
    void setScheduledUpdateMinute(int minute);

    /**
     * 保存配置到文件
     */
    void saveConfig();

    /**
     * 从磁盘重新加载服务端配置并清除内部缓存。
     *
     * <p>Fabric 平台会重新读取 properties 文件；
     * NeoForge/Forge 平台仅清除缓存（加载器已自动重载 TOML）。</p>
     */
    void reloadConfig();

    /**
     * 获取维度配置列表（原始字符串格式）
     *
     * @return 维度配置字符串列表
     */
    java.util.List<String> getDimensionConfigs();

    /**
     * 设置维度配置列表（原始字符串格式）
     *
     * @param configs 维度配置字符串列表
     */
    void setDimensionConfigs(java.util.List<String> configs);

    /**
     * 解析维度配置列表为 DimensionScanConfig 对象
     *
     * @return 解析后的维度扫描配置列表
     */
    java.util.List<DimensionScanConfig> parseDimensionConfigs();

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

    /**
     * 获取服务端统一标识名（用于客户端存档目录命名）。
     * 多入口/多 IP 的服务器可配置统一名字，使客户端复用同一份地图缓存。
     * 返回空字符串时客户端回退到 IP 命名（兼容旧行为）。
     */
    default String getServerName() {
        return "";
    }

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
     * 清理平台级方块属性缓存。
     * 在玩家加入或服务器停止时调用，释放内存并确保后续查询获取最新数据。
     */
    void clearBlockPropertiesCache();

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