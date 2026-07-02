package com.mapsyncer.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** G3/G4 — {@code level().getServer()} API (1.21.11+). */
public final class PlayerLevelApiHelper {

    private PlayerLevelApiHelper() {}

    public static MinecraftServer getServer(ServerPlayer player) {
        return player.level().getServer();
    }
}
