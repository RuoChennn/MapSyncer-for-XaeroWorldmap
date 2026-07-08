package com.mapsyncer.server;

import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationCacheTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetCache() {
        GenerationCache.resetInstance();
    }

    @Test
    void getAllReturnsUnmodifiableSnapshotInsteadOfLiveView() {
        GenerationCache cache = GenerationCache.getInstance(tempDir);
        cache.update("null/1_2", 100, "hash-a");

        Map<String, TimestampHashEntry> snapshot = cache.getAll();
        cache.update("null/3_4", 101, "hash-b");

        assertTrue(snapshot.containsKey("null/1_2"));
        assertFalse(snapshot.containsKey("null/3_4"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("null/5_6", new TimestampHashEntry(102, "hash-c")));
    }
}
