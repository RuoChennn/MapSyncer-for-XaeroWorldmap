package com.mapsyncer.client;

import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.util.HashUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientContributionCollectorTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetCache() {
        ClientTimestampCache.resetInstance();
    }

    @Test
    void uploadsRegionUsingCachedLogicalTimestampWhenHashStillMatches() throws Exception {
        Path serverDir = tempDir.resolve("Multiplayer_test");
        Path region = serverDir.resolve("null").resolve("mw$0").resolve("1_2.zip");
        byte[] data = "client-newer-region".getBytes();
        Files.createDirectories(region.getParent());
        Files.write(region, data);

        String hash = HashUtils.computeHash(data);
        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        cache.update("null/1_2", 200, hash);
        cache.save();

        ContributionRegionMeta meta = new ContributionRegionMeta(
                "null/1_2", 1, 2, "null", Integer.MAX_VALUE, 100, "11111111");

        List<ContributionDataPayload> payloads = ClientContributionCollector.collect(7, meta, serverDir);

        assertEquals(1, payloads.size());
        ContributionDataPayload payload = payloads.get(0);
        assertEquals(7, payload.requestId());
        assertEquals("null/1_2", payload.relativePath());
        assertEquals(100, payload.observedServerTimestampSeconds());
        assertEquals("11111111", payload.observedServerHash());
        assertEquals(200, payload.chunk().timestampSeconds);
        assertEquals("null", payload.chunk().dimension);
        assertEquals(Integer.MAX_VALUE, payload.chunk().caveLayer);
        assertArrayEquals(data, payload.chunk().data);
    }

    @Test
    void skipsRegionWhenHashMatchesServerBaseline() throws Exception {
        Path serverDir = tempDir.resolve("Multiplayer_test");
        Path region = serverDir.resolve("null").resolve("mw$0").resolve("3_4.zip");
        byte[] data = "same-region".getBytes();
        Files.createDirectories(region.getParent());
        Files.write(region, data);

        String hash = HashUtils.computeHash(data);
        ContributionRegionMeta meta = new ContributionRegionMeta(
                "null/3_4", 3, 4, "null", Integer.MAX_VALUE, 100, hash);

        assertTrue(ClientContributionCollector.collect(8, meta, serverDir).isEmpty());
    }

    @Test
    void skipsRegionWhenLogicalTimestampIsNotNewerThanServer() throws Exception {
        Path serverDir = tempDir.resolve("Multiplayer_test");
        Path region = serverDir.resolve("null").resolve("mw$0").resolve("5_6.zip");
        byte[] data = "old-region".getBytes();
        Files.createDirectories(region.getParent());
        Files.write(region, data);

        String hash = HashUtils.computeHash(data);
        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        cache.update("null/5_6", 100, hash);
        cache.save();

        ContributionRegionMeta meta = new ContributionRegionMeta(
                "null/5_6", 5, 6, "null", Integer.MAX_VALUE, 100, "22222222");

        assertTrue(ClientContributionCollector.collect(9, meta, serverDir).isEmpty());
    }
}
