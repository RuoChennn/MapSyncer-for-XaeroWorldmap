package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

@EventBusSubscriber(value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.GAME)
public class IncrementalUpdateHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalUpdateHandler.class);
    private static volatile IncrementalUpdateHandler instance;

    private volatile MinecraftServer server;
    private volatile boolean running = false;
    private final AtomicInteger tickCounter = new AtomicInteger(0);
    private volatile LocalDateTime lastScheduledUpdate = null;

    public static IncrementalUpdateHandler getInstance() {
        if (instance == null) {
            synchronized (IncrementalUpdateHandler.class) {
                if (instance == null) {
                    instance = new IncrementalUpdateHandler();
                }
            }
        }
        return instance;
    }

    public void start(MinecraftServer server) {
        if (running) {
            LOGGER.warn("Incremental update handler already running");
            return;
        }
        this.server = server;
        this.running = true;
        this.tickCounter.set(0);
        this.lastScheduledUpdate = null;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.TICK) {
            LOGGER.info("Incremental update handler started (TICK mode, interval: {} ticks = {} seconds)",
                ModConfig.SERVER.incrementalUpdateIntervalTicks.get(),
                ModConfig.SERVER.incrementalUpdateIntervalTicks.get() / 20);
        } else if (mode == UpdateMode.SCHEDULED) {
            LOGGER.info("Incremental update handler started (SCHEDULED mode, daily at {}:{})",
                ModConfig.SERVER.scheduledUpdateHour.get(),
                ModConfig.SERVER.scheduledUpdateMinute.get());
        }
    }

    public void stop() {
        running = false;
        server = null;
        tickCounter.set(0);
        lastScheduledUpdate = null;
        LOGGER.info("Incremental update handler stopped");
    }

    public boolean isRunning() {
        return running;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        IncrementalUpdateHandler handler = getInstance();
        if (!handler.running || handler.server == null) return;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.DISABLED) return;

        switch (mode) {
            case TICK:
                handler.checkTickMode();
                break;
            case SCHEDULED:
                handler.checkScheduledMode();
                break;
            case DISABLED:
                // Do nothing
                break;
        }
    }

    private void checkTickMode() {
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
        int currentTick = tickCounter.incrementAndGet();

        if (currentTick >= interval) {
            tickCounter.set(0);
            performScheduledUpdate("TICK mode interval");
        }
    }

    private void checkScheduledMode() {
        LocalDateTime now = LocalDateTime.now();
        int targetHour = ModConfig.SERVER.scheduledUpdateHour.get();
        int targetMinute = ModConfig.SERVER.scheduledUpdateMinute.get();
        LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
        LocalTime currentTime = now.toLocalTime();

        // Check if we've reached the scheduled time (within 1 minute window)
        // and haven't already updated today
        if (currentTime.isAfter(targetTime) && currentTime.isBefore(targetTime.plusMinutes(1))) {
            if (lastScheduledUpdate == null || !lastScheduledUpdate.toLocalDate().equals(now.toLocalDate())) {
                lastScheduledUpdate = now;
                performScheduledUpdate("SCHEDULED mode daily update at " + targetHour + ":" + targetMinute);
            }
        }
    }

    private void performScheduledUpdate(String reason) {
        LOGGER.info("Performing incremental update: {}", reason);

        try {
            ConversionOrchestrator.performIncrementalScan(server);
        } catch (Exception e) {
            LOGGER.error("Error during scheduled incremental update", e);
        }
    }

    public String getStatusInfo() {
        if (!running) {
            return "Stopped";
        }

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        switch (mode) {
            case DISABLED:
                return "Running but disabled";
            case TICK:
                int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks.get();
                int remaining = interval - tickCounter.get();
                return String.format("TICK mode: next update in %d ticks (%.1f seconds)",
                    remaining, remaining / 20.0f);
            case SCHEDULED:
                int targetHour = ModConfig.SERVER.scheduledUpdateHour.get();
                int targetMinute = ModConfig.SERVER.scheduledUpdateMinute.get();
                LocalDateTime now = LocalDateTime.now();
                LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
                LocalDateTime nextUpdate = now.toLocalDate().atTime(targetTime);
                if (now.toLocalTime().isAfter(targetTime)) {
                    nextUpdate = nextUpdate.plusDays(1);
                }
                long secondsUntil = java.time.Duration.between(now, nextUpdate).getSeconds();
                return String.format("SCHEDULED mode: next update at %02d:%02d (in %dh %dm)",
                    targetHour, targetMinute, secondsUntil / 3600, (secondsUntil % 3600) / 60);
            default:
                return "Unknown mode";
        }
    }

    /**
     * Reset singleton instance to release memory.
     * Called when server stops to prevent memory leaks on dedicated servers.
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
            LOGGER.info("IncrementalUpdateHandler instance reset");
        }
    }
}