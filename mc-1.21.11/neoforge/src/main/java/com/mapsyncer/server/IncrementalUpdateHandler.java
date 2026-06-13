package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge 增量更新处理器 - 薄包装器
 *
 * 委托所有业务逻辑给公共的 IncrementalUpdateHandlerLogic 类。
 * 此类仅负责 NeoForge 特定的事件注册和生命周期管理。
 */
@EventBusSubscriber(value = {Dist.CLIENT, Dist.DEDICATED_SERVER})
public class IncrementalUpdateHandler {

    /**
     * 服务器Tick事件处理
     *
     * 将事件委托给公共逻辑类处理。
     *
     * @param event 服务器Tick后事件
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        IncrementalUpdateHandlerLogic.getInstance().onServerTick();
    }

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