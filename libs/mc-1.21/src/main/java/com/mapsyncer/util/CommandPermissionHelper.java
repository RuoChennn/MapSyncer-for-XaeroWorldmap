package com.mapsyncer.util;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/** G2 — {@code hasPermission(4)} API (1.20.x / 1.21.1). */
public final class CommandPermissionHelper {

    private CommandPermissionHelper() {}

    public static Predicate<CommandSourceStack> admin() {
        return source -> source.hasPermission(4);
    }
}
