package com.mapsyncer.util;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.function.Predicate;

/** G3/G4 — {@code Commands.hasPermission(LEVEL_GAMEMASTERS)} API (1.21.11+). */
public final class CommandPermissionHelper {

    private CommandPermissionHelper() {}

    public static Predicate<CommandSourceStack> admin() {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }
}
