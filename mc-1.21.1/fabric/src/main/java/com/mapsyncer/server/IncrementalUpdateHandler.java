package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;

/**
 * Fabric 增量更新处理器 - 薄包装器
 *
 * 委托所有业务逻辑给公共的 IncrementalUpdateHandlerLogic 类。
 * Tick 注册由 {@code MapSyncer.registerServerEvents()} 统一处理，避免重复注册。
 */
public class IncrementalUpdateHandler {

    /**
     * 启动增量更新处理器
     *
     * @param server Minecraft服务器实例
     */
    public static void start(MinecraftServer server) {
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    /**
     * 停止增量更新处理器
     */
    public static void stop() {
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    /**
     * 检查处理器是否正在运行
     *
     * @return true表示正在运行，false表示已停止
     */
    public static boolean isRunning() {
        return IncrementalUpdateHandlerLogic.getInstance().isRunning();
    }

    /**
     * 获取当前tick计数
     *
     * @return tick计数器值
     */
    public static int getTickCounter() {
        return IncrementalUpdateHandlerLogic.getInstance().getTickCounter();
    }

    /**
     * 获取处理器状态信息
     *
     * @return 状态信息字符串
     */
    public static String getStatusInfo() {
        return IncrementalUpdateHandlerLogic.getInstance().getStatusInfo();
    }

    /**
     * 重置单例实例以释放内存
     *
     * 在服务器停止时调用，防止专用服务器重启时的内存泄漏。
     */
    public static void resetInstance() {
        IncrementalUpdateHandlerLogic.resetInstance();
    }
}