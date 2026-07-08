package com.mapsyncer.network.payload;

/**
 * 客户端可贡献 region 的服务端基线元数据。
 *
 * @param relativePath 相对地图缓存路径
 * @param regionX region X 坐标
 * @param regionZ region Z 坐标
 * @param dimension 维度标识符
 * @param caveLayer 洞穴层号，Integer.MAX_VALUE 表示地表层
 * @param serverTimestampSeconds 服务端已观察到的时间戳（秒）
 * @param serverHash 服务端已观察到的哈希
 */
public record ContributionRegionMeta(
        String relativePath,
        int regionX,
        int regionZ,
        String dimension,
        int caveLayer,
        long serverTimestampSeconds,
        String serverHash
) {
}
