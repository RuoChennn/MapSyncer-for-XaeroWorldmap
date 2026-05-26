package com.mapsyncer.platform.impl;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.server.BlockPropertyResolver;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.util.BlockColorMapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Fabric 平台实现
 */
public class FabricPlatform implements Platform {

    private MinecraftServer server;

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String getPlatformName() {
        return "Fabric 1.20";
    }

    @Override
    public Path getWorldDataPath() {
        if (server == null) return null;
        return server.getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
    }

    @Override
    public Path getCacheDirectory() {
        return ConversionOrchestrator.CACHE_DIR;
    }

    @Override
    public UpdateMode getIncrementalUpdateMode() {
        return ModConfig.SERVER.incrementalUpdateMode.get();
    }

    @Override
    public int getIncrementalUpdateInterval() {
        return ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
    }

    @Override
    public int getMaxSyncPacketSize() {
        return ModConfig.SERVER.maxSyncPacketSize.get();
    }

    @Override
    public int getSyncSpeedLimitKBps() {
        return ModConfig.SERVER.syncSpeedLimitKBps.get();
    }

    @Override
    public boolean isDebugLoggingEnabled() {
        return ModConfig.SERVER.enableDebugLogging.get();
    }

    @Override
    public Set<String> getEnabledDimensions() {
        Set<String> dimensions = new HashSet<>();
        for (var config : ModConfig.SERVER.parseDimensionConfigs()) {
            dimensions.add(config.dimension());
        }
        return dimensions;
    }

    @Override
    public void clearBlockColorCache() {
        BlockColorMapper.clearCache();
    }

    @Override
    public void clearBlockPropertyCache() {
        BlockPropertyResolver.clearCache();
    }
}