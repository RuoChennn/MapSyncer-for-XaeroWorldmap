package com.mapsyncer.server;

import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.util.HashUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionValidatorTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetCache() {
        GenerationCache.resetInstance();
    }

    @Test
    void acceptedInitialContributionKeepsClientRegionTimestamp() throws Exception {
        GenerationCache cache = GenerationCache.getInstance(tempDir);
        long clientRegionTimestamp = 120;
        ContributionRegionMeta expected = new ContributionRegionMeta(
                "null/1_2",
                1,
                2,
                "null",
                Integer.MAX_VALUE,
                0,
                HashUtils.DEFAULT_HASH
        );

        ContributionValidator.Result result = ContributionValidator.validate(
                expected,
                xaeroRegionZip("initial-partial-map"),
                clientRegionTimestamp,
                cache,
                tempDir
        );

        assertTrue(result.accepted());
        assertEquals(clientRegionTimestamp, result.acceptedTimestampSeconds());
    }

    @Test
    void acceptedNewerContributionKeepsClientRegionTimestamp() throws Exception {
        GenerationCache cache = GenerationCache.getInstance(tempDir);
        cache.update("null/1_2", 100, "server01");
        long clientRegionTimestamp = 150;
        ContributionRegionMeta expected = new ContributionRegionMeta(
                "null/1_2",
                1,
                2,
                "null",
                Integer.MAX_VALUE,
                100,
                "server01"
        );

        ContributionValidator.Result result = ContributionValidator.validate(
                expected,
                xaeroRegionZip("newer-complete-map"),
                clientRegionTimestamp,
                cache,
                tempDir
        );

        assertTrue(result.accepted());
        assertEquals(clientRegionTimestamp, result.acceptedTimestampSeconds());
    }

    private static byte[] xaeroRegionZip(String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("region.xaero"));
            zip.write(content.getBytes());
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
