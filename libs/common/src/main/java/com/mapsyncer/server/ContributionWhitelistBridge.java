package com.mapsyncer.server;

import com.mojang.authlib.GameProfile;
import com.mapsyncer.config.ContributionScope;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.UuidWhitelistFile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Resolves world-level contribution permissions for server-side upload requests.
 */
public final class ContributionWhitelistBridge {
    private static final String FILE_NAME = "mapsyncer-contributors.json";

    private ContributionWhitelistBridge() {
    }

    public static boolean isWhitelisted(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return isWhitelisted(player, player.getUUID());
    }

    public static boolean isWhitelisted(ServerPlayer player, UUID uuid) {
        MinecraftServer server = getServer(player);
        if (server == null || uuid == null) {
            return false;
        }
        Path whitelistPath = server.getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig")
                .resolve(FILE_NAME);
        return UuidWhitelistFile.loadOrCreate(whitelistPath).contains(uuid);
    }

    public static boolean isContributionAllowed(ServerPlayer player) {
        if (!PlatformManager.isInitialized() || player == null) {
            return false;
        }
        ContributionScope scope = PlatformManager.getPlatform().getContributionScope();
        if (scope == null || scope == ContributionScope.DISABLED) {
            return false;
        }
        if (scope == ContributionScope.ALL) {
            return true;
        }

        MinecraftServer server = getServer(player);
        boolean isOp = isOp(server, player);
        return switch (scope) {
            case OPS -> isOp;
            case WHITELIST -> isWhitelisted(player);
            case OPS_AND_WHITELIST -> isOp || isWhitelisted(player);
            default -> false;
        };
    }

    private static MinecraftServer getServer(ServerPlayer player) {
        if (player == null || player.level() == null) {
            return null;
        }
        return player.level().getServer();
    }

    private static boolean isOp(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return false;
        }
        Object playerList = server.getPlayerList();
        try {
            Method isOp = playerList.getClass().getMethod("isOp", GameProfile.class);
            return Boolean.TRUE.equals(isOp.invoke(playerList, player.getGameProfile()));
        } catch (NoSuchMethodException ignored) {
            // Minecraft 1.21.11+ uses NameAndId instead of GameProfile.
        } catch (ReflectiveOperationException e) {
            return false;
        }

        try {
            Method nameAndIdMethod = player.getClass().getMethod("nameAndId");
            Object nameAndId = nameAndIdMethod.invoke(player);
            Method isOp = playerList.getClass().getMethod("isOp", nameAndId.getClass());
            return Boolean.TRUE.equals(isOp.invoke(playerList, nameAndId));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
