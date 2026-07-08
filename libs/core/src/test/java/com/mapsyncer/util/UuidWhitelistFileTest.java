package com.mapsyncer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UuidWhitelistFileTest {
    @TempDir Path tempDir;

    @Test
    void createsEmptyWhitelistWhenMissing() throws Exception {
        Path file = tempDir.resolve("nested").resolve("mapsyncer-contributors.json");

        var whitelist = UuidWhitelistFile.loadOrCreate(file);

        assertTrue(Files.exists(file));
        assertTrue(whitelist.allowedContributors().isEmpty());
        assertEquals("{\n  \"allowedContributors\": []\n}\n", Files.readString(file));
    }

    @Test
    void readsUuidValuesAndIgnoresInvalidValues() throws Exception {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID outsideArray = UUID.fromString("99999999-9999-9999-9999-999999999999");
        Path file = tempDir.resolve("mapsyncer-contributors.json");
        Files.writeString(file, """
            {
              "lastEditor": "99999999-9999-9999-9999-999999999999",
              "allowedContributors": [
                "11111111-2222-3333-4444-555555555555",
                "not-a-uuid"
              ]
            }
            """);

        var whitelist = UuidWhitelistFile.loadOrCreate(file);

        assertTrue(whitelist.contains(uuid));
        assertFalse(whitelist.contains(outsideArray));
        assertEquals(1, whitelist.allowedContributors().size());
    }

    @Test
    void constructorCopiesContributorsAsImmutableInsertionOrderedSet() {
        UUID first = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID second = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID third = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Set<UUID> source = new LinkedHashSet<>(List.of(first, second));

        var whitelist = new UuidWhitelistFile(source);
        source.add(third);

        assertEquals(List.of(first, second), List.copyOf(whitelist.allowedContributors()));
        assertFalse(whitelist.contains(third));
        assertThrows(UnsupportedOperationException.class, () -> whitelist.allowedContributors().add(third));
    }
}
