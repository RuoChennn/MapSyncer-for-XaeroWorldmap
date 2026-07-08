package com.mapsyncer.server;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ContributionDataPayload;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Assembles contribution fragments for a request and region path.
 */
public final class ContributionUploadAssembler {
    private final Map<Key, Assembly> assemblies = new HashMap<>();

    public Result accept(ContributionDataPayload payload) {
        if (payload == null || payload.chunk() == null) {
            return Result.rejected("empty_chunk");
        }
        ChunkMapData chunk = payload.chunk();
        if (chunk.data == null || chunk.data.length == 0) {
            clear(payload);
            return Result.rejected("empty_chunk");
        }

        int totalParts = chunk.totalParts <= 1 ? 1 : chunk.totalParts;
        int partIndex = totalParts == 1 ? 0 : chunk.partIndex;
        if (partIndex < 0 || partIndex >= totalParts) {
            clear(payload);
            return Result.rejected("invalid_part");
        }

        if (totalParts == 1) {
            return Result.complete(Arrays.copyOf(chunk.data, chunk.data.length));
        }

        Key key = new Key(payload.requestId(), payload.relativePath());
        Assembly assembly = assemblies.computeIfAbsent(key, ignored -> new Assembly(totalParts));
        if (assembly.totalParts != totalParts) {
            assemblies.remove(key);
            return Result.rejected("part_count_mismatch");
        }
        if (assembly.parts[partIndex] != null) {
            assemblies.remove(key);
            return Result.rejected("duplicate_part");
        }

        assembly.parts[partIndex] = Arrays.copyOf(chunk.data, chunk.data.length);
        assembly.received++;
        if (assembly.received < assembly.totalParts) {
            return Result.pending();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : assembly.parts) {
            if (part == null) {
                assemblies.remove(key);
                return Result.rejected("missing_part");
            }
            output.writeBytes(part);
        }
        assemblies.remove(key);
        return Result.complete(output.toByteArray());
    }

    public void clear(ContributionDataPayload payload) {
        if (payload != null) {
            assemblies.remove(new Key(payload.requestId(), payload.relativePath()));
        }
    }

    public void clearRequest(int requestId) {
        assemblies.keySet().removeIf(key -> key.requestId == requestId);
    }

    public void clearAll() {
        assemblies.clear();
    }

    public record Result(boolean complete, byte[] fullData, String rejectionReason) {
        public boolean rejected() {
            return rejectionReason != null;
        }

        static Result pending() {
            return new Result(false, null, null);
        }

        static Result complete(byte[] fullData) {
            return new Result(true, fullData, null);
        }

        static Result rejected(String reason) {
            return new Result(false, null, reason);
        }
    }

    private record Key(int requestId, String relativePath) {
    }

    private static final class Assembly {
        private final int totalParts;
        private final byte[][] parts;
        private int received;

        private Assembly(int totalParts) {
            this.totalParts = totalParts;
            this.parts = new byte[totalParts][];
        }
    }
}
