package com.mapsyncer.server;

import com.mapsyncer.network.payload.ContributionRegionMeta;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * State for one server-issued client contribution request.
 */
public final class ContributionSession {
    private final int requestId;
    private final ServerPlayer player;
    private final UUID playerId;
    private final String playerName;
    private final Map<String, ContributionRegionMeta> expectedRegions;
    private int accepted;
    private int rejected;
    private boolean complete;
    private String completionStatus = "done";

    public ContributionSession(int requestId, ServerPlayer player, List<ContributionRegionMeta> regions) {
        this.requestId = requestId;
        this.player = player;
        this.playerId = player.getUUID();
        this.playerName = player.getName().getString();
        this.expectedRegions = new LinkedHashMap<>();
        for (ContributionRegionMeta region : regions) {
            this.expectedRegions.put(region.relativePath(), region);
        }
    }

    public int requestId() {
        return requestId;
    }

    public ServerPlayer player() {
        return player;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public Map<String, ContributionRegionMeta> expectedRegions() {
        return Collections.unmodifiableMap(expectedRegions);
    }

    public ContributionRegionMeta expectedRegion(String relativePath) {
        return expectedRegions.get(relativePath);
    }

    public boolean isComplete() {
        return complete;
    }

    public void markComplete() {
        markComplete("done");
    }

    public void markComplete(String status) {
        complete = true;
        completionStatus = status;
    }

    public String completionStatus() {
        return completionStatus;
    }

    public void markAccepted() {
        accepted++;
    }

    public void markRejected() {
        rejected++;
    }

    public int accepted() {
        return accepted;
    }

    public int rejected() {
        return rejected;
    }
}
