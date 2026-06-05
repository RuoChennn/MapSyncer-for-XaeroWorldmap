package com.mapsyncer.config;

/**
 * 缓存配置常量类
 *
 * <p>集中定义所有缓存的上限值，便于管理和调整。</p>
 *
 * <p>缓存上限值的选择依据：</p>
 * <ul>
 *   <li>Region 级缓存：需要覆盖大型服务器可能的所有区域文件数量</li>
 *   <li>方块级缓存：需要覆盖原版 + mod 可能添加的方块类型数量</li>
 * </ul>
 *
 * <p>内存占用估算：</p>
 * <ul>
 *   <li>TimestampHashEntry: 约 50 bytes/条目（路径字符串 + timestamp + hash）</li>
 *   <li>BlockProperties: 约 30 bytes/条目（多个布尔值 + 整数）</li>
 *   <li>颜色值: 约 20 bytes/条目（字符串 + RGB整数）</li>
 * </ul>
 */
public final class CacheConfig {

    // ========== Region 级缓存配置 ==========

    /**
     * Region 元数据缓存上限
     *
     * <p>存储内容：relativePath -> TimestampHashEntry(timestamp, CRC32 hash)</p>
     * <p>用途：GenerationCache 用于同步时比对判断是否需要重传</p>
     *
     * <p>上限选择依据：</p>
     * <ul>
     *   <li>大型服务器可能有多个维度（主世界、下界、末地 + mod维度）</li>
     *   <li>每个维度可能有数千个已探索的 region</li>
     *   <li>50000 region ≈ 覆盖约 2500万 chunks，足够大型服务器</li>
     * </ul>
     *
     * <p>内存占用估算：50000 × 50 bytes ≈ 2.5 MB</p>
     */
    public static final int MAX_REGION_META_CACHE = 50000;

    /**
     * Region 时间戳缓存上限
     *
     * <p>存储内容：dimension -> regionCoord -> timestamp(ms)</p>
     * <p>用途：McaTimestampCache 跟踪 region 文件修改时间，用于增量更新检测</p>
     *
     * <p>上限选择依据：</p>
     * <ul>
     *   <li>与 GenerationCache 保持一致</li>
     *   <li>两者都是 region 级别的缓存，数据量相近</li>
     * </ul>
     *
     * <p>内存占用估算：50000 × 40 bytes ≈ 2 MB</p>
     */
    public static final int MAX_REGION_TIMESTAMP_CACHE = 50000;

    // ========== 方块级缓存配置 ==========

    /**
     * 方块颜色缓存上限
     *
     * <p>存储内容：blockName -> RGB颜色值</p>
     * <p>用途：BlockColorMapper 渲染地图时获取方块颜色</p>
     *
     * <p>上限选择依据：</p>
     * <ul>
     *   <li>Minecraft 原版约 800 种方块</li>
     *   <li>大型 mod 包可能添加 1000-3000 种新方块</li>
     *   <li>5000 足够覆盖绝大多数场景</li>
     *   <li>超出时清空缓存，重新查询即可</li>
     * </ul>
     *
     * <p>内存占用估算：5000 × 20 bytes ≈ 100 KB</p>
     */
    public static final int MAX_BLOCK_COLOR_CACHE = 5000;

    /**
     * 方块属性缓存上限
     *
     * <p>存储内容：blockName -> BlockProperties(透明、流体、光照值等)</p>
     * <p>用途：BlockPropertyResolver 解析方块属性用于渲染判断</p>
     *
     * <p>上限选择依据：</p>
     * <ul>
     *   <li>比颜色缓存稍大，因为同一方块可能有不同属性变体</li>
     *   <li>某些 mod 方块有多种状态（如不同 growth 阶段的作物）</li>
     *   <li>10000 覆盖方块类型 + 主要变体</li>
     * </ul>
     *
     * <p>内存占用估算：10000 × 30 bytes ≈ 300 KB</p>
     */
    public static final int MAX_BLOCK_PROPERTIES_CACHE = 10000;

    /**
     * 私有构造方法，防止实例化
     */
    private CacheConfig() {}
}