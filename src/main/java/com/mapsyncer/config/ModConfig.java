package com.mapsyncer.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;

public class ModConfig {

    public static final ModConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;

    public static final ModConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;

    static {
        var commonPair = new ModConfigSpec.Builder().configure(CommonConfig::new);
        COMMON = commonPair.getLeft();
        COMMON_SPEC = commonPair.getRight();

        var serverPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    public static class CommonConfig {
        public final BooleanValue enableDebugLogging;
        public final IntValue maxConcurrentRegions;
        public final IntValue maxSyncPacketSize;
        public final IntValue syncSpeedLimitKBps;
        public final BooleanValue enableResumeSync;

        public CommonConfig(ModConfigSpec.Builder builder) {
            builder.push("general");
            enableDebugLogging = builder
                    .comment("Enable debug logging for map generation")
                    .define("enableDebugLogging", false);
            maxConcurrentRegions = builder
                    .comment("Maximum number of regions to convert concurrently")
                    .defineInRange("maxConcurrentRegions", 4, 1, 16);
            maxSyncPacketSize = builder
                    .comment("Maximum sync packet size in bytes (default 1MB)")
                    .defineInRange("maxSyncPacketSize", 1048576, 65536, 10485760);
            syncSpeedLimitKBps = builder
                    .comment("Sync speed limit in KB/s (0 = unlimited, recommended 500-2000)")
                    .defineInRange("syncSpeedLimitKBps", 0, 0, 10000);
            enableResumeSync = builder
                    .comment("Enable resume sync when player reconnects")
                    .define("enableResumeSync", true);
            builder.pop();
        }
    }

    public enum UpdateMode {
        DISABLED,   // 禁用
        TICK,       // tick周期模式
        SCHEDULED   // 每日定时模式
    }

    public static class ServerConfig {
        public final EnumValue<UpdateMode> incrementalUpdateMode;
        public final IntValue incrementalUpdateIntervalTicks;
        public final IntValue scheduledUpdateHour;
        public final IntValue scheduledUpdateMinute;

        public ServerConfig(ModConfigSpec.Builder builder) {
            builder.push("incremental_update");

            incrementalUpdateMode = builder
                    .comment("Incremental update mode: DISABLED (off), TICK (periodic by ticks), SCHEDULED (daily at specific time)")
                    .defineEnum("incrementalUpdateMode", UpdateMode.DISABLED);

            incrementalUpdateIntervalTicks = builder
                    .comment("Interval in server ticks for TICK mode (20 ticks = 1 second, default 200 = 10 seconds)")
                    .defineInRange("incrementalUpdateIntervalTicks", 200, 20, 72000);

            scheduledUpdateHour = builder
                    .comment("Hour of day for SCHEDULED mode (0-23, uses server's local timezone)")
                    .defineInRange("scheduledUpdateHour", 4, 0, 23);

            scheduledUpdateMinute = builder
                    .comment("Minute of hour for SCHEDULED mode (0-59)")
                    .defineInRange("scheduledUpdateMinute", 0, 0, 59);

            builder.pop();
        }
    }
}
