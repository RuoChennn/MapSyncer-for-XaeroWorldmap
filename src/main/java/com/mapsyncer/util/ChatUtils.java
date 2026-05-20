package com.mapsyncer.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 聊天消息工具类
 *
 * 统一的聊天消息样式和前缀创建方法
 * 合并 CacheGenerateCommand 和 MapSyncerCommand 中的重复实现
 */
public final class ChatUtils {

    /** 模组前缀颜色（金色） */
    public static final int PREFIX_COLOR = 0xFFE55E;

    /** 成功消息颜色（绿色） */
    public static final int SUCCESS_COLOR = 0x55FF55;

    /** 错误消息颜色（红色） */
    public static final int ERROR_COLOR = 0xFF5555;

    /** 普通文本颜色（白色） */
    public static final int NORMAL_COLOR = 0xFFFFFF;

    /** 描述文本颜色（灰色） */
    public static final int DESC_COLOR = 0xAAAAAA;

    /** 标题颜色（黄色） */
    public static final int HEADER_COLOR = 0xFFFF55;

    /**
     * 私有构造方法，防止实例化
     */
    private ChatUtils() {
        // 工具类不允许实例化
    }

    /**
     * 创建带颜色的模组前缀组件
     *
     * @return 金色前缀组件 "MapSyncer"
     */
    public static MutableComponent prefix() {
        return Component.translatable("mapsyncer.prefix").withStyle(style -> style.withColor(PREFIX_COLOR));
    }

    /**
     * 创建带前缀的成功消息
     *
     * @param key 翻译键
     * @return 前缀 + 成功消息组件
     */
    public static MutableComponent success(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(SUCCESS_COLOR)));
    }

    /**
     * 创建带前缀的成功消息（带参数）
     *
     * @param key 翻译键
     * @param args 参数
     * @return 前缀 + 成功消息组件
     */
    public static MutableComponent success(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(SUCCESS_COLOR)));
    }

    /**
     * 创建带前缀的错误消息
     *
     * @param key 翻译键
     * @return 前缀 + 错误消息组件
     */
    public static MutableComponent error(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(ERROR_COLOR)));
    }

    /**
     * 创建带前缀的错误消息（带参数）
     *
     * @param key 翻译键
     * @param args 参数
     * @return 前缀 + 错误消息组件
     */
    public static MutableComponent error(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(ERROR_COLOR)));
    }

    /**
     * 创建带前缀的普通消息
     *
     * @param key 翻译键
     * @return 前缀 + 消息组件
     */
    public static MutableComponent message(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(NORMAL_COLOR)));
    }

    /**
     * 创建带前缀的普通消息（带参数）
     *
     * @param key 翻译键
     * @param args 参数
     * @return 前缀 + 消息组件
     */
    public static MutableComponent message(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(NORMAL_COLOR)));
    }

    /**
     * 创建描述文本组件（不带前缀）
     *
     * @param key 翻译键
     * @return 灰色文本组件
     */
    public static MutableComponent desc(String key) {
        return Component.translatable(key).withStyle(style -> style.withColor(DESC_COLOR));
    }

    /**
     * 创建描述文本组件（不带前缀，带参数）
     *
     * @param key 翻译键
     * @param args 参数
     * @return 灰色文本组件
     */
    public static MutableComponent desc(String key, Object... args) {
        return Component.translatable(key, args).withStyle(style -> style.withColor(DESC_COLOR));
    }

    /**
     * 创建标题组件（不带前缀）
     *
     * @param key 翻译键
     * @return 黄色标题组件
     */
    public static MutableComponent header(String key) {
        return Component.translatable(key).withStyle(style -> style.withColor(HEADER_COLOR));
    }
}