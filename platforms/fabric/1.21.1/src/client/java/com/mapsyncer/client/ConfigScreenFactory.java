package com.mapsyncer.client;

import com.mapsyncer.config.ModConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.mapsyncer.platform.UpdateMode;

/**
 * 配置界面工厂 - 客户端专用
 *
 * 使用 Cloth Config API 创建配置界面。
 */
public class ConfigScreenFactory {

    /**
     * 创建客户端配置界面
     *
     * @param parentScreen 父屏幕
     * @return 配置屏幕
     */
    public static Screen createClientConfigScreen(Screen parentScreen) {
        ModConfig.ClientConfig config = ModConfig.CLIENT();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parentScreen)
            .setTitle(Component.translatable("title.mapsyncer.client_config"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // 客户端设置类别
        ConfigCategory client = builder.getOrCreateCategory(Component.translatable("category.mapsyncer.client"));

        int maxThreads = Runtime.getRuntime().availableProcessors();
        int defaultThreads = Math.max(1, maxThreads / 2);

        client.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.hash_threads"), config.getHashThreads(), 1, maxThreads)
            .setDefaultValue(defaultThreads)
            .setSaveConsumer(config::setHashThreads)
            .build());

        builder.setSavingRunnable(config::save);

        return builder.build();
    }

    /**
     * 创建服务端配置界面
     *
     * @param parentScreen 父屏幕
     * @return 配置屏幕
     */
    public static Screen createServerConfigScreen(Screen parentScreen) {
        ModConfig.ServerConfig config = ModConfig.SERVER();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parentScreen)
            .setTitle(Component.translatable("title.mapsyncer.config"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // 通用设置类别
        ConfigCategory general = builder.getOrCreateCategory(Component.translatable("category.mapsyncer.general"));

        general.addEntry(entryBuilder.startBooleanToggle(
                Component.translatable("option.mapsyncer.debug"), config.getEnableDebugLogging())
            .setDefaultValue(false)
            .setSaveConsumer(config::setEnableDebugLogging)
            .build());

        general.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.concurrent_regions"), config.getMaxConcurrentRegions(), 1, 16)
            .setDefaultValue(4)
            .setSaveConsumer(config::setMaxConcurrentRegions)
            .build());

        general.addEntry(entryBuilder.startIntField(
                Component.translatable("option.mapsyncer.packet_size"), config.getMaxSyncPacketSize())
            .setDefaultValue(262144)
            .setMin(65536)
            .setMax(1048576)
            .setSaveConsumer(config::setMaxSyncPacketSize)
            .build());

        general.addEntry(entryBuilder.startIntField(
                Component.translatable("option.mapsyncer.speed_limit"), config.getSyncSpeedLimitKBps())
            .setDefaultValue(1024)
            .setMin(0)
            .setMax(10240)
            .setSaveConsumer(config::setSyncSpeedLimitKBps)
            .build());

        // 增量更新设置类别
        ConfigCategory incremental = builder.getOrCreateCategory(Component.translatable("category.mapsyncer.incremental"));

        incremental.addEntry(entryBuilder.startSelector(
                Component.translatable("option.mapsyncer.update_mode"),
                UpdateMode.values(),
                config.getIncrementalUpdateMode())
            .setDefaultValue(UpdateMode.DISABLED)
            .setSaveConsumer(config::setIncrementalUpdateMode)
            .build());

        incremental.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.interval_ticks"), config.getIncrementalUpdateIntervalTicks(), 20, 72000)
            .setDefaultValue(200)
            .setSaveConsumer(config::setIncrementalUpdateIntervalTicks)
            .build());

        incremental.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.scheduled_hour"), config.getScheduledUpdateHour(), 0, 23)
            .setDefaultValue(4)
            .setSaveConsumer(config::setScheduledUpdateHour)
            .build());

        incremental.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.scheduled_minute"), config.getScheduledUpdateMinute(), 0, 59)
            .setDefaultValue(0)
            .setSaveConsumer(config::setScheduledUpdateMinute)
            .build());

        builder.setSavingRunnable(config::save);

        return builder.build();
    }
}
