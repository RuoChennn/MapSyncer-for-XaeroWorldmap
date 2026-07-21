package com.terraformersmc.modmenu.api;

/**
 * Compile-only stub matching Mod Menu's public API.
 * Not packaged into the mod JAR; at runtime Mod Menu provides the real class.
 */
public interface ModMenuApi {

    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}
