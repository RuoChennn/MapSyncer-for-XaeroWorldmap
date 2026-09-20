package com.mapsyncer.client;

import com.mapsyncer.config.ConcurrentRegionsConfig;
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
 * 此类仅在客户端加载。
 */
public class ConfigScreenFactory {

    public static Screen createClientConfigScreen(Screen parentScreen) {
        ModConfig.ClientConfig config = ModConfig.CLIENT();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parentScreen)
            .setTitle(Component.translatable("title.mapsyncer.client_config"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory client = builder.getOrCreateCategory(Component.translatable("category.mapsyncer.client"));

        int maxThreads = Runtime.getRuntime().availableProcessors();
        int defaultThreads = Math.max(1, maxThreads / 2);

        client.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.hash_threads"), config.getHashThreads(), 1, maxThreads)
            .setDefaultValue(defaultThreads)
            .setTooltip(Component.translatable("option.mapsyncer.hash_threads.tooltip"))
            .setSaveConsumer(config::setHashThreads)
            .build());

        client.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.map_region_load_interval"),
                config.getMapRegionLoadIntervalTicks(), -1, 100)
            .setDefaultValue(1)
            .setTooltip(Component.translatable("option.mapsyncer.map_region_load_interval.tooltip"))
            .setSaveConsumer(config::setMapRegionLoadIntervalTicks)
            .build());

        client.addEntry(entryBuilder.startBooleanToggle(
                Component.translatable("option.mapsyncer.auto_sync_enabled"), config.isAutoSyncEnabled())
            .setDefaultValue(true)
            .setTooltip(Component.translatable("option.mapsyncer.auto_sync_enabled.tooltip"))
            .setSaveConsumer(config::setAutoSyncEnabled)
            .build());

        builder.setSavingRunnable(config::save);
        return builder.build();
    }

    public static Screen createServerConfigScreen(Screen parentScreen) {
        ModConfig.ServerConfig config = ModConfig.SERVER();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parentScreen)
            .setTitle(Component.translatable("title.mapsyncer.config"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory general = builder.getOrCreateCategory(Component.translatable("category.mapsyncer.general"));

        general.addEntry(entryBuilder.startBooleanToggle(
                Component.translatable("option.mapsyncer.debug"), config.getEnableDebugLogging())
            .setDefaultValue(false)
            .setTooltip(Component.translatable("option.mapsyncer.debug.tooltip"))
            .setSaveConsumer(config::setEnableDebugLogging)
            .build());

        // 0 = auto（与配置文件一致）；勿用 1–16 默认 4，否则保存会覆盖 auto=0
        general.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.concurrent_regions"),
                config.getMaxConcurrentRegions(), ConcurrentRegionsConfig.AUTO, ConcurrentRegionsConfig.MAX_CONCURRENT)
            .setDefaultValue(ConcurrentRegionsConfig.AUTO)
            .setTooltip(Component.translatable("option.mapsyncer.concurrent_regions.tooltip"))
            .setSaveConsumer(config::setMaxConcurrentRegions)
            .build());

        general.addEntry(entryBuilder.startIntField(
                Component.translatable("option.mapsyncer.packet_size"), config.getMaxSyncPacketSize())
            .setDefaultValue(262144)
            .setMin(65536)
            .setMax(1048576)
            .setTooltip(Component.translatable("option.mapsyncer.packet_size.tooltip"))
            .setSaveConsumer(config::setMaxSyncPacketSize)
            .build());

        general.addEntry(entryBuilder.startIntField(
                Component.translatable("option.mapsyncer.speed_limit"), config.getSyncSpeedLimitKBps())
            .setDefaultValue(1024)
            .setMin(0)
            .setMax(10240)
            .setTooltip(Component.translatable("option.mapsyncer.speed_limit.tooltip"))
            .setSaveConsumer(config::setSyncSpeedLimitKBps)
            .build());

        ConfigCategory incremental = builder.getOrCreateCategory(Component.translatable("category.mapsyncer.incremental"));

        incremental.addEntry(entryBuilder.startSelector(
                Component.translatable("option.mapsyncer.update_mode"),
                UpdateMode.values(),
                config.getIncrementalUpdateMode())
            .setDefaultValue(UpdateMode.DISABLED)
            .setTooltip(Component.translatable("option.mapsyncer.update_mode.tooltip"))
            .setSaveConsumer(config::setIncrementalUpdateMode)
            .build());

        incremental.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.interval_ticks"),
                config.getIncrementalUpdateIntervalTicks(), 2400, 72000)
            .setDefaultValue(6000)
            .setTooltip(Component.translatable("option.mapsyncer.interval_ticks.tooltip"))
            .setSaveConsumer(config::setIncrementalUpdateIntervalTicks)
            .build());

        incremental.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.scheduled_hour"), config.getScheduledUpdateHour(), 0, 23)
            .setDefaultValue(4)
            .setTooltip(Component.translatable("option.mapsyncer.scheduled_hour.tooltip"))
            .setSaveConsumer(config::setScheduledUpdateHour)
            .build());

        incremental.addEntry(entryBuilder.startIntSlider(
                Component.translatable("option.mapsyncer.scheduled_minute"), config.getScheduledUpdateMinute(), 0, 59)
            .setDefaultValue(0)
            .setTooltip(Component.translatable("option.mapsyncer.scheduled_minute.tooltip"))
            .setSaveConsumer(config::setScheduledUpdateMinute)
            .build());

        builder.setSavingRunnable(config::save);
        return builder.build();
    }
}
