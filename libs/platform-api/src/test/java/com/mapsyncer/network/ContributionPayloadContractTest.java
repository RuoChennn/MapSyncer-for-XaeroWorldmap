package com.mapsyncer.network;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.network.payload.ContributionRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionPayloadContractTest {
    @BeforeEach
    @AfterEach
    void resetNetworkManager() throws Exception {
        Field instance = NetworkManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void contributionPayloadsExposeStableNetworkIdsAndFields() {
        assertEquals("contribution_request", NetworkHandler.CONTRIBUTION_REQUEST_ID);
        assertEquals("contribution_data", NetworkHandler.CONTRIBUTION_DATA_ID);
        assertEquals("contribution_complete", NetworkHandler.CONTRIBUTION_COMPLETE_ID);
        assertEquals("contribution_result", NetworkHandler.CONTRIBUTION_RESULT_ID);
        assertEquals("contribution_only_request", NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID);

        ContributionRegionMeta meta = new ContributionRegionMeta(
                "DIM0/region/r.1.2.mca",
                1,
                2,
                "minecraft:overworld",
                Integer.MAX_VALUE,
                123456789L,
                "server-hash"
        );
        ContributionRequestPayload request = new ContributionRequestPayload(42, List.of(meta), "request");

        assertEquals(NetworkHandler.CONTRIBUTION_REQUEST_ID, ContributionRequestPayload.ID);
        assertEquals(42, request.requestId());
        assertSame(meta, request.regions().get(0));
        assertEquals("request", request.status());
        assertEquals("DIM0/region/r.1.2.mca", meta.relativePath());
        assertEquals(1, meta.regionX());
        assertEquals(2, meta.regionZ());
        assertEquals("minecraft:overworld", meta.dimension());
        assertEquals(Integer.MAX_VALUE, meta.caveLayer());
        assertEquals(123456789L, meta.serverTimestampSeconds());
        assertEquals("server-hash", meta.serverHash());

        ChunkMapData chunk = new ChunkMapData(1, 2, "minecraft:overworld", new byte[] {1, 2, 3});
        ContributionDataPayload data = new ContributionDataPayload(
                42,
                chunk,
                "DIM0/region/r.1.2.mca",
                123456790L,
                "observed-hash"
        );

        assertEquals(NetworkHandler.CONTRIBUTION_DATA_ID, ContributionDataPayload.ID);
        assertEquals(42, data.requestId());
        assertSame(chunk, data.chunk());
        assertEquals("DIM0/region/r.1.2.mca", data.relativePath());
        assertEquals(123456790L, data.observedServerTimestampSeconds());
        assertEquals("observed-hash", data.observedServerHash());

        ContributionCompletePayload complete = new ContributionCompletePayload(42, 3, "complete");
        assertEquals(NetworkHandler.CONTRIBUTION_COMPLETE_ID, ContributionCompletePayload.ID);
        assertEquals(42, complete.requestId());
        assertEquals(3, complete.sentRegions());
        assertEquals("complete", complete.status());

        ContributionResultPayload result = new ContributionResultPayload(42, 2, 1, "done");
        assertEquals(NetworkHandler.CONTRIBUTION_RESULT_ID, ContributionResultPayload.ID);
        assertEquals(42, result.requestId());
        assertEquals(2, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals("done", result.status());
        assertFalse(result.terminal());

        ContributionResultPayload terminalResult = new ContributionResultPayload(43, 0, 1, "timeout", true);
        assertEquals(43, terminalResult.requestId());
        assertEquals(0, terminalResult.accepted());
        assertEquals(1, terminalResult.rejected());
        assertEquals("timeout", terminalResult.status());
        assertTrue(terminalResult.terminal());

        Map<String, ClientMeta> clientMeta = Map.of(
                "DIM0/region/r.1.2.mca",
                new ClientMeta(123456790L, "client-hash")
        );
        ContributionOnlyRequestPayload contributionOnly = new ContributionOnlyRequestPayload(
                42,
                0,
                1,
                clientMeta,
                "client_exit"
        );
        assertEquals(NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID, ContributionOnlyRequestPayload.ID);
        assertEquals(42, contributionOnly.requestId());
        assertEquals(0, contributionOnly.partIndex());
        assertEquals(1, contributionOnly.totalParts());
        assertEquals(clientMeta, contributionOnly.clientMeta());
        assertEquals("client_exit", contributionOnly.reason());
    }

    @Test
    void contributionOnlyRequestSplitReusesSyncRequestMetadataParts() {
        Map<String, ClientMeta> clientMeta = new LinkedHashMap<>();
        for (int index = 0; index < 300; index++) {
            clientMeta.put(
                    "DIM0/region/r." + index + "." + (index + 1) + ".mca",
                    new ClientMeta(index, "hash-" + index)
            );
        }

        SyncRequestPayload[] syncParts = SyncRequestPayload.split(clientMeta);
        List<ContributionOnlyRequestPayload> contributionOnlyParts =
                ContributionOnlyRequestPayload.split(99, clientMeta, "client_exit");

        assertEquals(syncParts.length, contributionOnlyParts.size());
        for (int index = 0; index < syncParts.length; index++) {
            ContributionOnlyRequestPayload contributionOnly = contributionOnlyParts.get(index);
            assertEquals(99, contributionOnly.requestId());
            assertEquals(syncParts[index].partIndex(), contributionOnly.partIndex());
            assertEquals(syncParts[index].totalParts(), contributionOnly.totalParts());
            assertEquals(syncParts[index].clientMeta(), contributionOnly.clientMeta());
            assertEquals("client_exit", contributionOnly.reason());
        }
    }

    @Test
    void networkManagerDelegatesContributionSendMethods() {
        FakeNetworkHandler handler = new FakeNetworkHandler();
        NetworkManager.initialize(handler);

        ContributionDataPayload data = new ContributionDataPayload(
                7,
                new ChunkMapData(1, 2, "minecraft:overworld", new byte[] {4}),
                "DIM0/region/r.1.2.mca",
                11L,
                "hash"
        );
        ContributionCompletePayload complete = new ContributionCompletePayload(7, 1, "complete");
        ContributionRequestPayload request = new ContributionRequestPayload(7, List.of(), "request");
        ContributionResultPayload result = new ContributionResultPayload(7, 1, 0, "ok", true);
        ContributionOnlyRequestPayload contributionOnly = new ContributionOnlyRequestPayload(7, 0, 1, Map.of(), "exit");

        NetworkManager.sendToServer(data);
        NetworkManager.sendToServer(complete);
        NetworkManager.sendToServer(contributionOnly);
        NetworkManager.sendToPlayer("player-a", request);
        NetworkManager.sendToPlayer("player-b", result);

        assertSame(data, handler.sentContributionData);
        assertSame(complete, handler.sentContributionComplete);
        assertSame(contributionOnly, handler.sentContributionOnlyRequest);
        assertEquals("player-a", handler.contributionRequestPlayer);
        assertSame(request, handler.sentContributionRequest);
        assertEquals("player-b", handler.contributionResultPlayer);
        assertSame(result, handler.sentContributionResult);
    }

    @Test
    void networkManagerDelegatesContributionHandlerRegistrations() {
        FakeNetworkHandler handler = new FakeNetworkHandler();
        NetworkManager.initialize(handler);

        BiConsumer<ContributionRequestPayload, PayloadContext> requestHandler = (payload, context) -> {};
        BiConsumer<ContributionDataPayload, PayloadContext> dataHandler = (payload, context) -> {};
        BiConsumer<ContributionCompletePayload, PayloadContext> completeHandler = (payload, context) -> {};
        BiConsumer<ContributionResultPayload, PayloadContext> resultHandler = (payload, context) -> {};
        BiConsumer<ContributionOnlyRequestPayload, PayloadContext> contributionOnlyHandler = (payload, context) -> {};

        NetworkManager.registerContributionRequestHandler(requestHandler);
        NetworkManager.registerContributionDataHandler(dataHandler);
        NetworkManager.registerContributionCompleteHandler(completeHandler);
        NetworkManager.registerContributionResultHandler(resultHandler);
        NetworkManager.registerContributionOnlyRequestHandler(contributionOnlyHandler);

        assertSame(requestHandler, handler.contributionRequestHandler);
        assertSame(dataHandler, handler.contributionDataHandler);
        assertSame(completeHandler, handler.contributionCompleteHandler);
        assertSame(resultHandler, handler.contributionResultHandler);
        assertSame(contributionOnlyHandler, handler.contributionOnlyRequestHandler);
    }

    private static final class FakeNetworkHandler implements NetworkHandler<Object, Object> {
        private ContributionDataPayload sentContributionData;
        private ContributionCompletePayload sentContributionComplete;
        private ContributionOnlyRequestPayload sentContributionOnlyRequest;
        private Object contributionRequestPlayer;
        private ContributionRequestPayload sentContributionRequest;
        private Object contributionResultPlayer;
        private ContributionResultPayload sentContributionResult;
        private BiConsumer<ContributionRequestPayload, PayloadContext> contributionRequestHandler;
        private BiConsumer<ContributionDataPayload, PayloadContext> contributionDataHandler;
        private BiConsumer<ContributionCompletePayload, PayloadContext> contributionCompleteHandler;
        private BiConsumer<ContributionResultPayload, PayloadContext> contributionResultHandler;
        private BiConsumer<ContributionOnlyRequestPayload, PayloadContext> contributionOnlyRequestHandler;

        @Override
        public void registerHandlers(Object event) {
        }

        @Override
        public void sendToServer(SyncRequestPayload payload) {
        }

        @Override
        public void sendToServer(ContributionDataPayload payload) {
            sentContributionData = payload;
        }

        @Override
        public void sendToServer(ContributionCompletePayload payload) {
            sentContributionComplete = payload;
        }

        @Override
        public void sendToServer(ContributionOnlyRequestPayload payload) {
            sentContributionOnlyRequest = payload;
        }

        @Override
        public void sendToPlayer(Object player, SyncResponsePayload payload) {
        }

        @Override
        public void sendToPlayer(Object player, SyncProgressPayload payload) {
        }

        @Override
        public void sendToPlayer(Object player, ServerInstalledPayload payload) {
        }

        @Override
        public void sendToPlayer(Object player, ContributionRequestPayload payload) {
            contributionRequestPlayer = player;
            sentContributionRequest = payload;
        }

        @Override
        public void sendToPlayer(Object player, ContributionResultPayload payload) {
            contributionResultPlayer = player;
            sentContributionResult = payload;
        }

        @Override
        public void registerSyncResponseHandler(BiConsumer<SyncResponsePayload, PayloadContext> handler) {
        }

        @Override
        public void registerSyncProgressHandler(BiConsumer<SyncProgressPayload, PayloadContext> handler) {
        }

        @Override
        public void registerServerInstalledHandler(BiConsumer<ServerInstalledPayload, PayloadContext> handler) {
        }

        @Override
        public void registerSyncRequestHandler(BiConsumer<SyncRequestPayload, PayloadContext> handler) {
        }

        @Override
        public void registerContributionRequestHandler(
                BiConsumer<ContributionRequestPayload, PayloadContext> handler
        ) {
            contributionRequestHandler = handler;
        }

        @Override
        public void registerContributionDataHandler(BiConsumer<ContributionDataPayload, PayloadContext> handler) {
            contributionDataHandler = handler;
        }

        @Override
        public void registerContributionCompleteHandler(
                BiConsumer<ContributionCompletePayload, PayloadContext> handler
        ) {
            contributionCompleteHandler = handler;
        }

        @Override
        public void registerContributionResultHandler(BiConsumer<ContributionResultPayload, PayloadContext> handler) {
            contributionResultHandler = handler;
        }

        @Override
        public void registerContributionOnlyRequestHandler(
                BiConsumer<ContributionOnlyRequestPayload, PayloadContext> handler
        ) {
            contributionOnlyRequestHandler = handler;
        }

        @Override
        public void enqueueWork(PayloadContext context, Runnable work) {
        }

        @Override
        public Object getPlayerFromContext(PayloadContext context) {
            return null;
        }
    }
}
