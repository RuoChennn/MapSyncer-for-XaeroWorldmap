import com.mapsyncer.server.SyncRadiusPolicy;
import com.mapsyncer.server.McaTimestampCache;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.server.RegionScanner.RegionFileEntry;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.SyncRequestPayload;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import com.mapsyncer.client.XaeroMapDataHandler;
import com.mapsyncer.network.payload.ChunkMapData;
import java.io.ByteArrayOutputStream;
import java.util.zip.*;

/** Real production policies and filesystem round trips; no Minecraft process needed. */
public final class SyncFeatureRegression {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(fixtures);
        var random = new Random(719);
        for (int i = 0; i < 5000; i++) {
            int bx = random.nextInt(), bz = random.nextInt(), radius = random.nextInt(100) + 1;
            var policy = SyncRadiusPolicy.atPlayer(radius, bx, bz, "null");
            int cx = Math.floorDiv(bx, 512), cz = Math.floorDiv(bz, 512);
            check(policy.allows("null", cx + radius, cz - radius), "inclusive square edge");
            check(!policy.allows("null", cx + radius + 1, cz), "outside radius");
            check(!policy.allows("DIM-1", cx, cz), "cross-dimension bypass");
        }
        check(SyncRadiusPolicy.atPlayer(0, -1, -513, "null").allows("DIM1", Integer.MAX_VALUE, Integer.MIN_VALUE), "zero disables");
        check(!SyncRadiusPolicy.atPlayer(Integer.MAX_VALUE, 0, 0, "null").allows("null", Integer.MIN_VALUE, 0), "distance overflow");
        check(SyncRadiusPolicy.atPlayer(1, -1, -513, "null").allows("null", -2, -3), "negative floor division");

        var mapping = DimensionPathMapping.getInstance();
        for (int version : new int[]{20, 21, 26}) {
            mapping.initialize(version);
            mapping.clearDetectedMappings();
            Path world = fixtures.resolve("world-" + version);
            for (String id : List.of("pack:moon", "pack:one/two", "minecraft:custom", "minecraft:one/two")) {
                String folder = "dimensions/" + id.replace(':', '/');
                Files.createDirectories(world.resolve(folder).resolve("region"));
                String encoded = mapping.toXaeroDimension(id);
                check(!encoded.contains("/"), "Xaero folder must be flat: " + id);
                check(DimensionPathMapping.isSafeXaeroFolder(encoded), "safe generated folder: " + encoded);
                check(mapping.getPathFromXaero(encoded).equals(id), "dimension round trip: " + id);
                check(mapping.getPathFromFolder(folder).equals(id), "nested filesystem reverse: " + id);
                check(mapping.detectRegionDir(world, id).equals(world.resolve(folder).resolve("region")), "dimension region detection: " + id);
            }
            mapping.scanAndRegisterDimensions(world);
            check(mapping.getAllFolderMappings().containsKey("minecraft:custom"), "minecraft namespace discovery");
            check(mapping.getAllFolderMappings().containsKey("pack:one/two"), "nested dimension discovery");
            check(mapping.toXaeroDimension("minecraft:overworld").equals("null"), "vanilla compatibility");
            check(mapping.toServerDimension("pack$one%two").equals("pack:one/two"), "Xaero reverse conversion");
            check(mapping.getPathFromXaero(mapping.toXaeroDimension("pack:trailing..")).equals("pack:trailing.."), "Xaero trailing dot encoding");
        }
        for (String bad : List.of("..", "../null", "C:\\tmp", "/null", "pack$path/escape", "pack$path\\escape")) {
            check(!DimensionPathMapping.isSafeXaeroFolder(bad), "unsafe path accepted: " + bad);
        }
        Map<String, ClientMeta> meta = new LinkedHashMap<>();
        for (int i = 0; i < 2000; i++) meta.put("pack$" + "x".repeat(350) + "/" + i, new ClientMeta(1, "01234567"));
        var parts = SyncRequestPayload.split(meta, false, "pack$moon", false);
        Set<String> restored = new HashSet<>();
        long requestId = parts[0].requestId();
        for (var part : parts) {
            check(part.hasValidPartBounds(), "split bounds");
            check(part.requestId() == requestId, "request identity differs across parts");
            int bytes = 64 + "pack$moon".length();
            for (var entry : part.clientMeta().entrySet()) bytes += entry.getKey().length() + 8 + entry.getValue().hash().length() + 10;
            check(bytes <= SyncRequestPayload.MAX_PAYLOAD_BYTES, "long metadata packet overflow");
            restored.addAll(part.clientMeta().keySet());
        }
        check(restored.equals(meta.keySet()), "metadata split loss");
        check(!new SyncRequestPayload(Map.of(), -1, 2).hasValidPartBounds(), "negative part index");
        check(!new SyncRequestPayload(Map.of(), 0, Integer.MAX_VALUE).hasValidPartBounds(), "unbounded part count");
        check(!new SyncRequestPayload(Map.of(), 2, 2).hasValidPartBounds(), "out of range index");
        var oversizedKey = SyncRequestPayload.split(Map.of("x".repeat(513), new ClientMeta(0, "01234567")), false, "pack$moon");
        check(oversizedKey.length == 1 && oversizedKey[0].clientMeta().isEmpty(), "oversized key must safely request full eligible data");
        Map<String, ClientMeta> longCache = new HashMap<>();
        for (int i = 0; i < 40000; i++) longCache.put("x".repeat(220) + i, new ClientMeta(0, "01234567"));
        var oversizedCache = SyncRequestPayload.split(longCache, false, "pack$moon");
        check(oversizedCache.length == 1 && oversizedCache[0].clientMeta().isEmpty(), "metadata character budget must agree with server");

        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
            zip.putNextEntry(new ZipEntry("region"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        byte[] zipBytes = zipped.toByteArray();
        Path hostRoot = fixtures.resolve("host-map");
        var surface = new ChunkMapData(-1, 2, "pack$one%two", zipBytes);
        var written = XaeroMapDataHandler.writeChunkData(surface, hostRoot, 123, true);
        check(written != null && written.outputFile().equals(hostRoot.resolve("pack$one%two/-1_2.zip")), "LAN host direct map layout");
        check(Arrays.equals(Files.readAllBytes(written.outputFile()), zipBytes), "host ZIP content");
        written = XaeroMapDataHandler.writeChunkData(surface, hostRoot, 123, true, "mw$chosen");
        check(written != null && written.outputFile().equals(hostRoot.resolve("pack$one%two/mw$chosen/-1_2.zip")), "host selected multiworld layout");
        check(XaeroMapDataHandler.writeChunkData(surface, hostRoot, 123, true, "../escape") == null, "multiworld traversal rejected");
        var cave = new ChunkMapData(-1, 2, "pack$one%two", zipBytes, 1, 3);
        written = XaeroMapDataHandler.writeChunkData(cave, hostRoot, 123, true);
        check(written != null && written.outputFile().equals(hostRoot.resolve("pack$one%two/caves/3/-1_2.zip")), "LAN host cave layout");
        Path remoteRoot = fixtures.resolve("remote-map");
        Files.createDirectories(remoteRoot.resolve("pack$one%two/mw$987"));
        written = XaeroMapDataHandler.writeChunkData(surface, remoteRoot, 123, false);
        check(written != null && written.outputFile().equals(remoteRoot.resolve("pack$one%two/mw$987/-1_2.zip")), "remote existing world layout");
        check(XaeroMapDataHandler.writeChunkData(new ChunkMapData(0, 0, "../escape", zipBytes), hostRoot, 123, true) == null, "writer rejects path traversal");
        check(XaeroMapDataHandler.writeChunkData(new ChunkMapData(0, 0, "null", new byte[]{1}), hostRoot, 123, true) == null, "writer rejects incomplete ZIP");

        Path cacheDir = fixtures.resolve("mtime");
        Files.createDirectories(cacheDir);
        Path mca = cacheDir.resolve("r.0.0.mca");
        Files.write(mca, new byte[]{1});
        long mtime = 1700000000123L;
        Files.setLastModifiedTime(mca, FileTime.fromMillis(mtime));
        McaTimestampCache.resetInstance();
        var cache = McaTimestampCache.getInstance(cacheDir);
        var entries = List.of(new RegionFileEntry(new RegionCoords(0, 0), mca, mtime, 1));
        check(cache.classifyUpdates("pack:one/two", entries).size() == 1, "initial dirty region");
        check(cache.classifyUpdates("pack:one/two", entries).size() == 1, "failed conversion must retry");
        cache.updateTimestampValue("pack:one/two", 0, 0, mtime);
        check(cache.classifyUpdates("pack:one/two", entries).isEmpty(), "successful commit");
        check(cache.classifyUpdates("other:one/two", entries).size() == 1, "namespace cache isolation");
        cache.saveCache();
        McaTimestampCache.resetInstance();
        cache = McaTimestampCache.getInstance(cacheDir);
        check(cache.classifyUpdates("pack:one/two", entries).isEmpty(), "nested dimension millisecond persistence");
        System.out.println("PASS: " + checks + " regression assertions");
    }
}
