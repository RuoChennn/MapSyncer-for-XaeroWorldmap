package com.mapsyncer.network;

/**
 * Payload 上下文包装器
 *
 * 包装平台特定的 IPayloadContext，提供统一的上下文操作接口。
 * 业务代码通过此包装器操作上下文，不直接接触平台类型。
 *
 * 使用方式：
 * 1. 平台实现创建 PayloadContext 包装平台特定的 IPayloadContext
 * 2. 业务代码通过 PayloadContext.enqueueWork() 执行任务
 */
public class PayloadContext {

    /** 平台特定的上下文对象（IPayloadContext） */
    private final Object platformContext;

    public PayloadContext(Object platformContext) {
        this.platformContext = platformContext;
    }

    /**
     * 获取平台特定的上下文对象
     *
     * @return 平台上下文（由平台实现处理）
     */
    public Object getPlatformContext() {
        return platformContext;
    }

    /**
     * 在主线程执行任务
     *
     * @param work 要执行的任务
     */
    public void enqueueWork(Runnable work) {
        NetworkManager.getHandler().enqueueWork(this, work);
    }

    /**
     * 获取服务端玩家对象
     *
     * @return 服务端玩家对象（ServerPlayer），客户端可能返回 null
     */
    public Object getPlayer() {
        return NetworkManager.getHandler().getPlayerFromContext(this);
    }
}