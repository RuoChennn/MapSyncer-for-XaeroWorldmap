package com.mapsyncer.util;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record UuidWhitelistFile(Set<UUID> allowedContributors) {
    private static final String EMPTY_WHITELIST_JSON = "{\n  \"allowedContributors\": []\n}\n";
    private static final Pattern ALLOWED_CONTRIBUTORS_PATTERN = Pattern.compile(
            "\"allowedContributors\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");

    public UuidWhitelistFile {
        allowedContributors = Collections.unmodifiableSet(new LinkedHashSet<>(allowedContributors));
    }

    public boolean contains(UUID uuid) {
        return allowedContributors.contains(uuid);
    }

    public static UuidWhitelistFile loadOrCreate(Path file) {
        try {
            if (!Files.exists(file)) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try {
                    Files.writeString(file, EMPTY_WHITELIST_JSON, StandardOpenOption.CREATE_NEW);
                    return new UuidWhitelistFile(Set.of());
                } catch (FileAlreadyExistsException ignored) {
                    // Another reader created the file first; read the existing content below.
                }
            }
            String json = Files.readString(file);
            Set<UUID> uuids = new LinkedHashSet<>();
            Matcher arrayMatcher = ALLOWED_CONTRIBUTORS_PATTERN.matcher(json);
            if (!arrayMatcher.find()) {
                return new UuidWhitelistFile(Set.of());
            }
            Matcher uuidMatcher = UUID_PATTERN.matcher(arrayMatcher.group(1));
            while (uuidMatcher.find()) {
                try {
                    uuids.add(UUID.fromString(uuidMatcher.group(1)));
                } catch (IllegalArgumentException ignored) {
                    // UUID shape is regex-filtered; keep this guard for defensive parsing.
                }
            }
            return new UuidWhitelistFile(uuids);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read contributor whitelist: " + file, e);
        }
    }
}
