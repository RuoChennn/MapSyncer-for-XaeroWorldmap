package com.mapsyncer.config;

/**
 * 超时配置常量类
 *
 * <p>集中定义所有超时相关的常量，便于管理和调整。</p>
 *
 * <p>超时值的选择依据：</p>
 * <ul>
 *   <li>任务超时：单个区域转换可能需要较长时间，60秒足够处理大型区域</li>
 *   <li>保存超时：区块保存涉及磁盘IO，60秒足够完成保存操作</li>
 *   <li>同步超时：网络传输可能因带宽限制较慢，10分钟足够完成大型同步</li>
 *   <li>响应超时：服务器响应应该快速，5秒足够正常情况</li>
 * </ul>
 */
public final class TimeoutConfig {

    // ========== 区域转换超时配置 ==========

    /**
     * 任务超时时间（秒）
     *
     * <p>用于等待单个区域转换任务完成。</p>
     *
     * <p>选择依据：</p>
     * <ul>
     *   <li>单个区域文件可能有 1024 个区块</li>
     *   <li>每个区块需要解析 NBT、计算颜色、写入文件</li>
     *   <li>60秒足够处理大型区域文件</li>
     * </ul>
     */
    public static final long TASK_TIMEOUT_SECONDS = 60;

    // ========== 网络同步超时配置 ==========

    /**
     * 同步过期超时时间（毫秒）
     *
     * <p>用于判断同步是否过期（超过此时间视为过期）。</p>
     *
     * <p>选择依据：</p>
     * <ul>
     *   <li>大型服务器可能需要传输大量区域数据</li>
     *   <li>网络带宽可能受限</li>
     *   <li>10分钟足够完成大型同步</li>
     * </ul>
     */
    public static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000;

    /**
     * 服务器响应超时时间（毫秒）
     *
     * <p>用于等待服务器响应同步请求。</p>
     *
     * <p>选择依据：</p>
     * <ul>
     *   <li>服务器响应应该快速返回</li>
     *   <li>正常情况下响应时间在秒级</li>
     *   <li>5秒足够等待正常响应</li>
     * </ul>
     */
    public static final long SERVER_RESPONSE_TIMEOUT_MS = 5000;

    /**
     * 速度限制周期时间（毫秒）
     *
     * <p>用于控制同步数据发送频率的周期。</p>
     *
     * <p>选择依据：</p>
     * <ul>
     *   <li>每秒检查一次发送速度</li>
     *   <li>1秒周期便于精确控制速率</li>
     * </ul>
     */
    public static final long MAX_SPEED_LIMIT_CYCLE_MS = 1000;

    /**
     * 私有构造方法，防止实例化
     */
    private TimeoutConfig() {}
}