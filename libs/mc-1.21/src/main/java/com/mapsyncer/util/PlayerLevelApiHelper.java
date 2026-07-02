package com.mapsyncer.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** G1/G2 — {@code serverLevel().getServer()} API. */
public final class PlayerLevelApiHelper {

    private PlayerLevelApiHelper() {}

    public static MinecraftServer getServer(ServerPlayer player) {
        return player.serverLevel().getServer();
    }
}
