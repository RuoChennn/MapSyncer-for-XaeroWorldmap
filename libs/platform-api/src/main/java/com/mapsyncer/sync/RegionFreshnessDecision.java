package com.mapsyncer.sync;

public record RegionFreshnessDecision(Action action) {
    public enum Action {
        SKIP_NO_DATA,
        SKIP_HASH_MATCH,
        DOWNLOAD_SERVER_NEWER,
        REQUEST_CLIENT_CONTRIBUTION
    }

    public boolean shouldDownloadToClient() {
        return action == Action.DOWNLOAD_SERVER_NEWER;
    }

    public boolean shouldRequestContribution() {
        return action == Action.REQUEST_CLIENT_CONTRIBUTION;
    }
}
