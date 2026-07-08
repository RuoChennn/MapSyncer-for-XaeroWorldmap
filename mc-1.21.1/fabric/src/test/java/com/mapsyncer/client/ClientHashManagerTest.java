package com.mapsyncer.client;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.HashUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientHashManagerTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetCache() {
        ClientHashManager.shutdown();
        ClientTimestampCache.resetInstance();
    }

    @Test
    void usesFileModificationTimeWhenCachedHashDiffersFromCurrentHash() throws Exception {
        Path serverDir = tempDir.resolve("Multiplayer_test");
        Path region = serverDir.resolve("null").resolve("mw$0").resolve("1_2.zip");
        byte[] data = "changed-client-region".getBytes();
        long fileTimestampSeconds = 1_234;
        Files.createDirectories(region.getParent());
        Files.write(region, data);
        Files.setLastModifiedTime(region, FileTime.from(Instant.ofEpochSecond(fileTimestampSeconds)));

        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        cache.update("null/1_2", 9_999, "deadbeef");
        cache.save();

        Map<String, ClientMeta> meta = ClientHashManager.computeMetaForSync(serverDir);

        assertEquals(fileTimestampSeconds, meta.get("null/1_2").timestampSeconds());
        assertEquals(HashUtils.computeHash(data), meta.get("null/1_2").hash());
    }

    @Test
    void keepsCachedLogicalTimestampWhenCachedHashMatchesCurrentHash() throws Exception {
        Path serverDir = tempDir.resolve("Multiplayer_test");
        Path region = serverDir.resolve("null").resolve("mw$0").resolve("3_4.zip");
        byte[] data = "unchanged-client-region".getBytes();
        long cachedTimestampSeconds = 8_888;
        Files.createDirectories(region.getParent());
        Files.write(region, data);
        Files.setLastModifiedTime(region, FileTime.from(Instant.ofEpochSecond(1_234)));

        String hash = HashUtils.computeHash(data);
        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        cache.update("null/3_4", cachedTimestampSeconds, hash);
        cache.save();

        Map<String, ClientMeta> meta = ClientHashManager.computeMetaForSync(serverDir);

        assertEquals(cachedTimestampSeconds, meta.get("null/3_4").timestampSeconds());
        assertEquals(hash, meta.get("null/3_4").hash());
    }
}
