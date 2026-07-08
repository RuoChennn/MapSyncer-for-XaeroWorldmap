package com.mapsyncer.sync;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;

import java.util.Objects;

public final class RegionFreshnessDecider {
    private RegionFreshnessDecider() {
    }

    public static RegionFreshnessDecision decide(TimestampHashEntry serverMeta, ClientMeta clientMeta) {
        if (serverMeta == null && clientMeta == null) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.SKIP_NO_DATA);
        }
        if (serverMeta == null) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION);
        }
        if (clientMeta == null) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER);
        }
        if (HashUtils.isValidHash(serverMeta.hash())
                && HashUtils.isValidHash(clientMeta.hash())
                && Objects.equals(serverMeta.hash(), clientMeta.hash())) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.SKIP_HASH_MATCH);
        }
        if (clientMeta.timestampSeconds() > serverMeta.timestampSeconds()) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION);
        }
        return new RegionFreshnessDecision(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER);
    }
}
