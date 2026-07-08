package com.mapsyncer.server;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ContributionDataPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionUploadAssemblerTest {
    @Test
    void rejectsContributionWithTooManyPartsBeforeAllocatingAssembly() {
        ContributionUploadAssembler assembler = new ContributionUploadAssembler();
        ChunkMapData chunk = new ChunkMapData(
                1,
                2,
                "null",
                new byte[] {1},
                100,
                Integer.MAX_VALUE,
                0,
                ContributionUploadAssembler.MAX_PARTS + 1
        );
        ContributionDataPayload payload = new ContributionDataPayload(
                7,
                chunk,
                "null/1_2",
                90,
                "server-hash"
        );

        ContributionUploadAssembler.Result result = assembler.accept(payload);

        assertTrue(result.rejected());
        assertEquals("too_many_parts", result.rejectionReason());
    }

    @Test
    void assemblesMultipartContributionWithinPartLimit() {
        ContributionUploadAssembler assembler = new ContributionUploadAssembler();
        ContributionDataPayload first = payload(8, 0, 2, new byte[] {1, 2});
        ContributionDataPayload second = payload(8, 1, 2, new byte[] {3});

        ContributionUploadAssembler.Result pending = assembler.accept(first);
        ContributionUploadAssembler.Result complete = assembler.accept(second);

        assertFalse(pending.complete());
        assertTrue(complete.complete());
        assertEquals(3, complete.fullData().length);
        assertEquals(1, complete.fullData()[0]);
        assertEquals(3, complete.fullData()[2]);
    }

    private static ContributionDataPayload payload(int requestId, int partIndex, int totalParts, byte[] data) {
        return new ContributionDataPayload(
                requestId,
                new ChunkMapData(1, 2, "null", data, 100, Integer.MAX_VALUE, partIndex, totalParts),
                "null/1_2",
                90,
                "server-hash"
        );
    }
}
