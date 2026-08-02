package com.mapsyncer.client;

import java.nio.file.Path;

public final class XaeroMapDataHandlerPathCheck {
    public static void main(String[] args) {
        Path root = Path.of("build", "safe-path-check").toAbsolutePath().normalize();

        assert XaeroMapDataHandler.resolveDimensionDirectory(root, "DIM-1")
                .equals(root.resolve("DIM-1"));
        assert XaeroMapDataHandler.resolveDimensionDirectory(root, "../escape") == null;
        assert XaeroMapDataHandler.resolveDimensionDirectory(root, "nested/path") == null;
        assert XaeroMapDataHandler.resolveDimensionDirectory(root, "bad\u0000path") == null;
    }
}
