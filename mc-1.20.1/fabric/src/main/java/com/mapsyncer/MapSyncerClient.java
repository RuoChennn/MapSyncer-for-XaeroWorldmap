package com.mapsyncer;

import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.client.MapSyncerCommand;
import com.mapsyncer.client.SyncProgressTracker;
import com.mapsyncer.network.impl.FabricNetworkHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer瀹㈡埛绔垵濮嬪寲绫?- Fabric 1.20.1 鐗堟湰
 *
 * 瀹炵幇 ClientModInitializer 鎺ュ彛锛屽湪瀹㈡埛绔垵濮嬪寲鏃舵敞鍐岀綉缁滄帴鏀跺櫒鍜屽懡浠ゃ€?
 */
public class MapSyncerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerClient.class);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing MapSyncer client...");

        // 娉ㄥ唽瀹㈡埛绔綉缁滄帴鏀跺櫒
        FabricNetworkHandler networkHandler = MapSyncer.getNetworkHandler();
        if (networkHandler != null) {
            networkHandler.registerClientHandlers();
            LOGGER.info("Client network handlers registered");
        }

        // 娉ㄥ唽瀹㈡埛绔懡浠?
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            MapSyncerCommand.registerClientCommands(dispatcher);
            LOGGER.info("Client commands registered");
        });

        // 娉ㄥ唽瀹㈡埛绔繛鎺ヤ簨浠?
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LOGGER.info("Client joined server, checking sync state...");
            // 娉ㄥ唽缃戠粶鎺ユ敹鍣?
            MapPacketReceiver.register();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Client disconnected from server, resetting state...");
            // 閲嶇疆鏈嶅姟绔畨瑁呯姸鎬?
            MapPacketReceiver.resetServerStatus();
            MapPacketReceiver.clearSyncData();
            // 娓呯悊 XaeroMapIntegrator 鍖哄煙杩借釜
            com.mapsyncer.client.XaeroMapDataHandler.clearRegionTracking();
            // 鍏抽棴杩涘害杩借釜鍣ㄧ殑绾跨▼姹狅紙闃叉鍐呭瓨娉勬紡锛?
            SyncProgressTracker.shutdown();
        });

        LOGGER.info("MapSyncer client initialized");
    }
}