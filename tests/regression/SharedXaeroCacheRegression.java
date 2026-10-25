import com.mapsyncer.server.ServerMapCache;
import com.mapsyncer.server.XaeroWriter;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** 用真实 ZIP 和多世界目录验证原地复用、更新检测及强制生成的清理范围。 */
public final class SharedXaeroCacheRegression {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]).toAbsolutePath();
        Path root = fixtures.resolve("xaero/world-map/world-A");
        Path saveA = fixtures.resolve("saves/world-A");
        var layout = new ServerMapCache.Snapshot(root, true,
                Map.of("null", "cm$converted", "DIM-1", "mw$chosen"));
        check(layout.dimensionDirectory("pack$group%moon").equals(root.resolve("pack$group%moon/cm$converted")),
                "generation in an unvisited dimension uses the native converted directory");
        Path surface = layout.dimensionDirectory("null").resolve("-1_2.zip");
        Path cave = layout.dimensionDirectory("null").resolve("caves/3/-1_2.zip");
        Path nether = layout.dimensionDirectory("DIM-1").resolve("0_0.zip");
        Path custom = layout.dimensionDirectory("pack$group%moon").resolve("1_0.zip");
        for (Path file : List.of(surface, cave, nether, custom)) {
            XaeroWriter.writeRegionFile(file.getParent(), new ConvertedRegion(
                    file.equals(nether) ? 0 : file.equals(custom) ? 1 : -1,
                    file.equals(nether) || file.equals(custom) ? 0 : 2, new byte[]{1, 2, 3}));
        }
        Files.createDirectories(root.resolve("null/mw$unselected"));
        Path otherMap = root.resolve("null/mw$unselected/-1_2.zip");
        Path directMap = root.resolve("null/-1_2.zip");
        Files.copy(surface, otherMap);
        Files.copy(surface, directMap);
        Files.writeString(root.resolve("null/dim_config.txt"), "preserve dimension config");
        Files.writeString(surface.getParent().resolve("map_config.txt"), "preserve selected map config");
        Path nativeTemp = surface.resolveSibling("-1_2.zip.temp");
        Files.writeString(nativeTemp, "Xaero owns this temporary file");
        byte[] original = Files.readAllBytes(surface);
        long fileCount;
        try (var files = Files.walk(root)) { fileCount = files.filter(Files::isRegularFile).count(); }

        check(new HashSet<>(layout.regionFiles()).equals(Set.of(surface, cave, nether, custom)), "selected maps only");
        check(layout.regionKey(surface).equals("null/-1_2"), "converted world surface key");
        check(layout.regionKey(cave).equals("null/caves/3/-1_2"), "converted world cave key");
        check(layout.regionKey(nether).equals("DIM-1/0_0"), "selected mw key");
        check(layout.regionKey(otherMap) == null && layout.regionKey(directMap) == null, "no duplicate coordinates");
        check(layout.regionKey(fixtures.resolve("outside/0_0.zip")) == null, "outside root rejected");
        check(layout.regionKey(root.resolve("null/cm$converted/misc/0_0.zip")) == null, "unknown tree rejected");
        ServerMapCache.configure(root, saveA);
        check(ServerMapCache.snapshot().dimensionDirectory("null").equals(root.resolve("null/cm$converted")),
                "generation before the first client selection capture uses the native converted directory");
        ServerMapCache.capture(saveA, root, layout.selections());
        check(ServerMapCache.snapshot().root().equals(root), "reuse actual Xaero root");
        check(!Files.exists(saveA.resolve("server_map_cache")), "no second cache directory");
        check(Arrays.equals(Files.readAllBytes(surface), original), "reading leaves native map intact");
        try (var files = Files.walk(root)) {
            check(files.filter(Files::isRegularFile).count() == fileCount, "no duplicate map files");
        }
        ServerMapCache.capture(fixtures.resolve("saves/old-world"), fixtures.resolve("old-map"), Map.of());
        check(ServerMapCache.snapshot().root().equals(root), "ignore stale world capture");
        Files.setLastModifiedTime(surface, FileTime.fromMillis(1700000000000L));
        var oldMeta = layout.metadata(surface);
        XaeroWriter.writeRegionFile(surface.getParent(), new ConvertedRegion(-1, 2, new byte[]{4, 5, 6}));
        Files.setLastModifiedTime(surface, FileTime.fromMillis(1800000000000L));
        var newMeta = layout.metadata(surface);
        check(!oldMeta.hash().equals(newMeta.hash()), "detect native content change");
        check(newMeta.timestampSeconds() == 1800000000L, "use native source timestamp");
        check(layout.lastModifiedSeconds() == 1800000000L, "join announcement sees native generation");
        check(Files.readString(nativeTemp).equals("Xaero owns this temporary file"), "writer preserves Xaero temp");
        try (var files = Files.walk(root)) {
            check(files.noneMatch(p -> p.toString().endsWith(".mapsyncer.tmp")), "writer cleans own temp files");
        }
        layout.clearDimensionRegions("null");
        check(!Files.exists(surface) && !Files.exists(cave), "force clears selected region files");
        check(Files.exists(otherMap) && Files.exists(directMap) && Files.exists(nether), "force preserves other maps");
        check(Files.exists(root.resolve("null/dim_config.txt")), "force preserves Xaero dimension config");
        check(Files.exists(surface.getParent().resolve("map_config.txt")), "force preserves selected map config");
        for (String bad : List.of("../escape", "a/b", "a\\b", "..", "C:escape")) {
            try {
                new ServerMapCache.Snapshot(root, true, Map.of("null", bad));
                throw new AssertionError("unsafe selection accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }
        ServerMapCache.configure(fixtures.resolve("dedicated/server_map_cache"), null);
        check(!ServerMapCache.snapshot().sharedXaero(), "dedicated cache remains separate");
        check(ServerMapCache.snapshot().dimensionDirectory("null").equals(
                ServerMapCache.snapshot().root().resolve("null")), "dedicated generation keeps the direct dimension directory");
        check(ServerMapCache.snapshot().selections().isEmpty(), "no stale map selections");
        System.out.println("SharedXaeroCacheRegression: " + checks + " assertions passed");
    }
}
