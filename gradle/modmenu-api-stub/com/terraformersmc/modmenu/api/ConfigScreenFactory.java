package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/**
 * Compile-only stub matching Mod Menu's public API.
 * Not packaged into the mod JAR; at runtime Mod Menu provides the real class.
 */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}
