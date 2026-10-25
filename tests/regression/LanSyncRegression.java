import com.mapsyncer.server.ServerSyncSession;
import com.mapsyncer.server.ServerMapCache;
import com.mapsyncer.server.RegionSyncPolicy;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.client.ClientTimestampCache;
import com.mapsyncer.client.XaeroMapDataHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** LAN 回归：无数据响应排队时的 worker 清理，以及真实转换 ZIP 的重复同步。 */
public final class LanSyncRegression {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        UUID player = UUID.randomUUID();
        var threads = new ConcurrentHashMap<UUID, Thread>();
        var mainQueue = new ArrayList<Runnable>();
        var delivered = new ArrayList<String>();
        ServerSyncSession.assignVersion(player, 1);
        Thread worker = new Thread(() -> mainQueue.add(() -> {
            if (ServerSyncSession.isCurrent(player, 1)) {
                delivered.add("dim_not_available");
                ServerSyncSession.finalizeSession(player);
            }
        }));
        threads.put(player, worker);
        worker.start();
        worker.join();
        ServerSyncSession.removeCompletedThreads(threads);
        check(threads.isEmpty(), "completed worker reference removed");
        check(ServerSyncSession.isCurrent(player, 1), "queued terminal response remains current");
        mainQueue.forEach(Runnable::run);
        check(delivered.equals(List.of("dim_not_available")), "empty host response delivered");
        check(!ServerSyncSession.isCurrent(player, 1), "terminal callback releases session");

        Path root = Path.of(args[0]);
        // 原生 Convert All Regions 不切换 currentMultiworld：房主仍处于空串表示的存档视图。
        var host = new ServerMapCache.Snapshot(root, true, Map.of("null", ""));
        Path convertedDirectory = root.resolve("null/cm$converted");
        Files.createDirectories(convertedDirectory);
        check(host.regionFiles().isEmpty(), "empty selected map has no regions");
        // 使用现有文件往返回归产生的真实 ZIP，不把 Xaero 缓存或探索文件算作同步数据。
        Path source = root.getParent().resolve("shared-xaero/xaero/world-map/world-A/DIM-1/mw$chosen/0_0.zip");
        for (int x = -2; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Path zip = convertedDirectory.resolve(x + "_" + z + ".zip");
                Files.copy(source, zip);
                Files.setLastModifiedTime(zip, java.nio.file.attribute.FileTime.fromMillis(1700000000000L));
            }
        }
        check(host.dimensionDirectory("null").equals(convertedDirectory),
                "native save view shares Convert All Regions output without switching maps");
        check(!Files.exists(root.resolve("generation_cache.properties")), "native conversion needs no generated hash index");
        Path anotherMap = root.resolve("null/mw$other");
        Files.createDirectories(anotherMap);
        Files.copy(source, anotherMap.resolve("9_9.zip"));
        var explicit = new ServerMapCache.Snapshot(root, true, Map.of("null", "mw$other"));
        check(explicit.regionFiles().equals(List.of(anotherMap.resolve("9_9.zip"))),
                "explicit map selection does not mix in converted save regions");
        var converted = new ServerMapCache.Snapshot(root, true, Map.of("null", "cm$converted"));
        check(new HashSet<>(converted.regionFiles()).equals(new HashSet<>(host.regionFiles())),
                "native save view and explicit converted view share the same ZIPs");
        Files.copy(source, host.dimensionDirectory("null").resolve("0_0 form xaero.zip"));
        Files.copy(source, host.dimensionDirectory("null").resolve("0_0form xaero.zip"));
        var received = new HashMap<String, ClientMeta>();
        for (Path zip : host.regionFiles()) {
            String key = host.regionKey(zip);
            check(!key.contains("cm$converted"), "protocol key omits selected multiworld");
            var meta = host.metadata(zip);
            check(meta.timestampSeconds() == 1700000000L, "native timestamp comes from file, not each request's clock");
            check(RegionSyncPolicy.shouldTransfer(meta.hash(), meta.timestampSeconds(), null), "initial transfer");
            received.put(key, new ClientMeta(meta.timestampSeconds(), meta.hash()));
        }
        check(received.size() == 12, "twelve real regions; malformed filenames ignored");
        int retransfers = 0;
        for (Path zip : host.regionFiles()) {
            var meta = host.metadata(zip);
            if (RegionSyncPolicy.shouldTransfer(meta.hash(), meta.timestampSeconds(), received.get(host.regionKey(zip)))) {
                retransfers++;
            }
        }
        check(retransfers == 0, "unchanged converted maps converge on repeated sync");
        Path guest = root.resolveSibling("Multiplayer_lan-regression");
        PlatformManager.initialize((Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[]{Platform.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getClientXaeroWorldMapDir" -> guest;
                    case "getClientHashThreads" -> 2;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
        ClientTimestampCache cache = ClientTimestampCache.getInstance(guest);
        for (Path zip : host.regionFiles()) {
            String key = host.regionKey(zip);
            var meta = host.metadata(zip);
            String[] coordinates = zip.getFileName().toString().replace(".zip", "").split("_");
            var chunk = new ChunkMapData(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    "null", Files.readAllBytes(zip), meta.timestampSeconds(), Integer.MAX_VALUE);
            check(XaeroMapDataHandler.writeChunkData(chunk, guest, 123, false) != null, "guest writes host ZIP");
            cache.update(key, meta.timestampSeconds(), meta.hash());
        }
        var reported = ClientHashManager.computeMetaForSync(guest.resolve("null/mw$123"));
        check(reported.keySet().equals(received.keySet()), "guest hash scan uses same keys as cm$converted host");
        for (Path zip : host.regionFiles()) {
            var meta = host.metadata(zip);
            check(!RegionSyncPolicy.shouldTransfer(meta.hash(), meta.timestampSeconds(), reported.get(host.regionKey(zip))),
                    "real guest metadata prevents repeat transfer");
        }
        ClientHashManager.shutdown();
        ClientTimestampCache.resetInstance();
        System.out.println("LanSyncRegression: " + checks + " assertions passed");
    }
}
