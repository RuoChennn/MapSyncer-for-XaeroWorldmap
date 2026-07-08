package com.mapsyncer.server;

import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

/**
 * Validates a fully assembled client contribution before the server writes it.
 */
public final class ContributionValidator {
    private ContributionValidator() {
    }

    public record Result(boolean accepted, String reason, long acceptedTimestampSeconds) {
    }

    public static Result validate(
            ContributionRegionMeta expected,
            byte[] fullData,
            long candidateTimestampSeconds,
            GenerationCache cache,
            Path cacheDir
    ) {
        if (expected == null) {
            return reject("unexpected_region");
        }
        if (fullData == null || fullData.length == 0) {
            return reject("empty_data");
        }
        if (!isSafeRelativePath(expected.relativePath(), cacheDir)) {
            return reject(expected.relativePath() == null || expected.relativePath().isBlank()
                    ? "empty_path"
                    : "unsafe_path");
        }
        if (!expected.relativePath().endsWith(expected.regionX() + "_" + expected.regionZ())) {
            return reject("path_coord_mismatch");
        }

        String actualHash = HashUtils.computeHash(fullData);
        if (!HashUtils.isValidHash(actualHash)) {
            return reject("invalid_hash");
        }
        if (!isValidXaeroZip(fullData)) {
            return reject("invalid_zip");
        }

        TimestampHashEntry current = cache.getMeta(expected.relativePath());
        if (!matchesObservedServerState(current, expected)) {
            return reject("server_changed");
        }
        if (current != null && current.hash().equals(actualHash)) {
            return reject("same_hash");
        }
        if (current != null && candidateTimestampSeconds <= current.timestampSeconds()) {
            return reject("not_newer");
        }

        long acceptedTimestampSeconds = Math.max(
                System.currentTimeMillis() / 1000,
                current == null ? 1 : current.timestampSeconds() + 1
        );
        return new Result(true, "accepted", acceptedTimestampSeconds);
    }

    private static Result reject(String reason) {
        return new Result(false, reason, 0);
    }

    private static boolean matchesObservedServerState(TimestampHashEntry current, ContributionRegionMeta expected) {
        if (current == null) {
            return expected.serverTimestampSeconds() == 0 && HashUtils.DEFAULT_HASH.equals(expected.serverHash());
        }
        return current.timestampSeconds() == expected.serverTimestampSeconds()
                && current.hash().equals(expected.serverHash());
    }

    private static boolean isSafeRelativePath(String relativePath, Path cacheDir) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.contains("\\")) {
            return false;
        }
        if (relativePath.contains("..")) {
            return false;
        }
        if (cacheDir == null) {
            return true;
        }
        Path root = cacheDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath + ".zip").normalize();
        return resolved.startsWith(root);
    }

    private static boolean isValidXaeroZip(byte[] data) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                if ("region.xaero".equals(entry.getName())) {
                    return true;
                }
                entry = zip.getNextEntry();
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
