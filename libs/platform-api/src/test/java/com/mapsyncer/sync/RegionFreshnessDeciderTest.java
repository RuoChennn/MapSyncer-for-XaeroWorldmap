package com.mapsyncer.sync;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFreshnessDeciderTest {
    @Test
    void missingBothSidesSkipsNoData() {
        RegionFreshnessDecision decision = RegionFreshnessDecider.decide(null, null);

        assertEquals(RegionFreshnessDecision.Action.SKIP_NO_DATA, decision.action());
        assertFalse(decision.shouldDownloadToClient());
        assertFalse(decision.shouldRequestContribution());
    }

    @Test
    void missingServerWithClientRequestsContribution() {
        ClientMeta client = new ClientMeta(200, "client");
        RegionFreshnessDecision decision = RegionFreshnessDecider.decide(null, client);

        assertEquals(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION, decision.action());
        assertFalse(decision.shouldDownloadToClient());
        assertTrue(decision.shouldRequestContribution());
    }

    @Test
    void missingClientDownloadsServerRegion() {
        TimestampHashEntry server = new TimestampHashEntry(100, "server");
        RegionFreshnessDecision decision = RegionFreshnessDecider.decide(server, null);

        assertEquals(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER, decision.action());
        assertTrue(decision.shouldDownloadToClient());
        assertFalse(decision.shouldRequestContribution());
    }

    @Test
    void hashMatchSkipsBothDirections() {
        TimestampHashEntry server = new TimestampHashEntry(100, "abc");
        ClientMeta client = new ClientMeta(200, "abc");
        RegionFreshnessDecision decision = RegionFreshnessDecider.decide(server, client);

        assertEquals(RegionFreshnessDecision.Action.SKIP_HASH_MATCH, decision.action());
        assertFalse(decision.shouldDownloadToClient());
        assertFalse(decision.shouldRequestContribution());
    }

    @Test
    void invalidMatchingHashesDoNotSkipFreshnessCheck() {
        TimestampHashEntry server = new TimestampHashEntry(100, HashUtils.DEFAULT_HASH);
        ClientMeta client = new ClientMeta(200, HashUtils.DEFAULT_HASH);
        RegionFreshnessDecision decision = RegionFreshnessDecider.decide(server, client);

        assertEquals(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION, decision.action());
        assertTrue(decision.shouldRequestContribution());
    }

    @Test
    void invalidOneSidedHashFallsBackToServerWhenClientIsNotNewer() {
        TimestampHashEntry server = new TimestampHashEntry(200, "server");
        ClientMeta olderClient = new ClientMeta(100, HashUtils.DEFAULT_HASH);
        ClientMeta sameAgeClient = new ClientMeta(200, HashUtils.DEFAULT_HASH);

        assertEquals(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER,
                RegionFreshnessDecider.decide(server, olderClient).action());
        assertEquals(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER,
                RegionFreshnessDecider.decide(server, sameAgeClient).action());
    }

    @Test
    void newerClientRequestsContribution() {
        TimestampHashEntry server = new TimestampHashEntry(100, "server");
        ClientMeta client = new ClientMeta(200, "client");
        RegionFreshnessDecision decision = RegionFreshnessDecider.decide(server, client);

        assertEquals(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION, decision.action());
        assertFalse(decision.shouldDownloadToClient());
        assertTrue(decision.shouldRequestContribution());
    }

    @Test
    void clientNotNewerDownloadsServerRegion() {
        TimestampHashEntry server = new TimestampHashEntry(200, "server");
        ClientMeta olderClient = new ClientMeta(100, "client");
        ClientMeta sameAgeClient = new ClientMeta(200, "client");

        assertEquals(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER,
                RegionFreshnessDecider.decide(server, olderClient).action());
        assertEquals(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER,
                RegionFreshnessDecider.decide(server, sameAgeClient).action());
    }
}
