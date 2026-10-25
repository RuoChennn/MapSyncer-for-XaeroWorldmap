import com.mapsyncer.client.HostMapFileWatcher;
import com.mapsyncer.client.HostMapUpdatePolicy;
import com.mapsyncer.platform.XaeroHostMapRefresh;
import xaero.map.file.RegionDetection;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;

/** 文件系统事件、关闭隔离、视距边界以及 Xaero 加载契约；不伪造网络请求。 */
public final class HostSilentMapRegression {
    static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static HostMapFileWatcher.Change await(HostMapFileWatcher watcher) throws Exception {
        long end = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < end) {
            if (watcher.failure() != null) throw watcher.failure();
            var change = watcher.poll();
            if (change != null) return change;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for real filesystem change");
    }
    static byte[] zip() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("region"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        Random random = new Random(29751);
        for (int i = 0; i < 2000; i++) {
            int x = random.nextInt(), z = random.nextInt();
            int rx = Math.floorDiv(x, 32), rz = Math.floorDiv(z, 32);
            check(!HostMapUpdatePolicy.outsideWritingRange(rx, rz, x, z, 32), "never reload player's native writing region");
            long expectedX = (long) rx * 32;
            check(!HostMapUpdatePolicy.outsideWritingRange(rx, rz, (int) expectedX, z, 0), "inclusive negative-coordinate region border");
        }
        check(!HostMapUpdatePolicy.outsideWritingRange(1, 0, 0, 0, 32), "touching view edge must defer whole region");
        check(HostMapUpdatePolicy.outsideWritingRange(2, 0, 0, 0, 32), "far region can refresh");
        check(HostMapUpdatePolicy.outsideWritingRange(Integer.MAX_VALUE, 0, 0, 0, 32), "coordinate multiplication does not overflow");

        Path saves = root.resolve("world/region");
        try (var watcher = new HostMapFileWatcher(saves, true)) {
            Files.createDirectories(saves);
            Path mca = saves.resolve("r.-2.3.mca");
            Files.write(mca, new byte[12288]);
            var first = await(watcher);
            check(first.file().equals(mca) && first.x() == -2 && first.z() == 3, "discover directory created after listener starts");
            check(watcher.poll() == null, "one pending notification for first save");
            Files.write(mca, new byte[16384]);
            Files.write(mca, new byte[20480]);
            Files.write(mca, new byte[24576]);
            var modified = await(watcher);
            check(modified.file().equals(mca) && modified.contentChanged(), "coalesced successive saves invalidate stale caches");
            check(watcher.poll() == null, "duplicate saves do not flood refresh queue");
            watcher.retry(first);
            check(await(watcher).equals(first), "busy or nearby region retains its pending change");
            Files.writeString(saves.resolve("r.9.9.mca.temp"), "not a completed save");
            Files.writeString(saves.resolve("r.bad.9.mca"), "not a coordinate");
            check(watcher.poll() == null, "temporary files are ignored");
        }
        Path maps = root.resolve("maps/cm$converted/caves/3");
        Files.createDirectories(maps);
        var old = new HostMapFileWatcher(maps, false);
        Path region = maps.resolve("5_-8.zip");
        Files.write(region, new byte[]{'P', 'K', 3, 4});
        Thread.sleep(1250);
        check(old.poll() == null, "partial ZIP never reaches Xaero");
        Path temp = maps.resolve("5_-8.zip.mapsyncer.tmp");
        Files.write(temp, zip());
        Files.move(temp, region, StandardCopyOption.REPLACE_EXISTING);
        check(await(old).file().equals(region), "atomic completed ZIP replacement is detected");
        old.close();
        Files.write(maps.resolve("6_6.zip"), zip());
        check(old.poll() == null, "disabled watcher clears queue and accepts no later changes");
        try (var next = new HostMapFileWatcher(maps, false)) {
            Set<Path> paths = Set.of(await(next).file(), await(next).file());
            check(paths.equals(Set.of(region, maps.resolve("6_6.zip"))), "re-enable discovers changes made while disabled");
        }

        var processor = new Processor(root);
        var context = XaeroHostMapRefresh.context(processor);
        check(context.worldSave() && context.directory().equals(root.resolve("region")), "native view uses MCA source");
        check(XaeroHostMapRefresh.writingDistance(context) == 12, "inherits private Xaero write distance from writer superclass");
        Path source = root.resolve("region/r.-2.3.mca");
        var added = XaeroHostMapRefresh.invalidate(context, source, -2, 3);
        check(added.accepted() && added.loadingRegion() == null, "new far region is registered without eager pixel allocation");
        check(processor.dimension.regions.layer.detection.source.equals(source.toFile()), "native detection refers to MCA, not generated ZIP");
        check(processor.dimension.regions.layer.detection.dirty, "new detection bypasses old texture cache");
        check(processor.dimension.regions.parent.dirty, "zoomed branch cache invalidated");
        processor.dimension.regions.leaf = new Leaf();
        processor.dimension.regions.leaf.writing = true;
        check(!XaeroHostMapRefresh.invalidate(context, source, -2, 3).accepted(), "actively written region never cleared");
        processor.dimension.regions.leaf.writing = false;
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[8193]);
        Path cached = root.resolve("cached.xwmc");
        Files.write(cached, new byte[]{1});
        Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
        processor.save.cache = cached.toFile();
        check(XaeroHostMapRefresh.invalidate(context, source, -2, 3, false).accepted()
                && processor.dimension.regions.leaf.clears == 0, "initial discovery preserves up-to-date historical caches");
        processor.isLoading = true;
        check(!XaeroHostMapRefresh.invalidate(context, source, -2, 3).accepted(), "busy Xaero IO thread is deferred without waiting");
        processor.isLoading = false;
        var refreshed = XaeroHostMapRefresh.invalidate(context, source, -2, 3);
        check(refreshed.accepted() && processor.dimension.regions.leaf.clears == 1, "uses native clearRegion for loaded far region");
        check(processor.save.loads == 1 && processor.dimension.regions.leaf.dirty, "native requestLoad reads source instead of stale texture cache");
        check(XaeroHostMapRefresh.reloadPending(refreshed.loadingRegion()), "one in-flight reload is tracked");
        processor.dimension.nativeView = false;
        processor.mw = "cm$chosen";
        check(!XaeroHostMapRefresh.invalidate(context, source, -2, 3).accepted(), "old view cannot mutate new selection");
        var selected = XaeroHostMapRefresh.context(processor);
        check(selected.directory().equals(root.resolve("maps/cm$chosen")), "ZIP view resolves correct String overload, not Path overload");
        processor.layer = -4;
        check(XaeroHostMapRefresh.context(processor).directory().endsWith("caves/-4"), "selected cave layer watches own ZIP directory");
        processor.cacheOnly = true;
        check(XaeroHostMapRefresh.context(processor) == null, "cache-only Xaero views do not discard cached source data");

        Class<?> configType = Class.forName("com.mapsyncer.config.ModConfig$ClientConfig");
        Path config = root.resolve("config/mapsyncer-client.properties");
        Object settings = configType.getConstructor(Path.class).newInstance(config);
        check((boolean) configType.getMethod("isIntegratedHostSilentUpdateEnabled").invoke(settings), "switch defaults to enabled");
        configType.getMethod("setIntegratedHostSilentUpdates", boolean.class).invoke(settings, false);
        configType.getMethod("save").invoke(settings);
        Object reloaded = configType.getConstructor(Path.class).newInstance(config);
        check(!(boolean) configType.getMethod("isIntegratedHostSilentUpdateEnabled").invoke(reloaded), "disabled switch persists across restart");
        System.out.println("HostSilentMapRegression: " + checks + " assertions passed");
    }

    public static class WriterBase { private int getWriteDistance() { return 12; } }
    public static class Writer extends WriterBase {}
    public static class Processor {
        private final Object loadingSync = new Object();
        private boolean isLoading;
        final Path root;
        final Dimension dimension = new Dimension();
        final Save save = new Save();
        String mw = "";
        int layer = Integer.MAX_VALUE;
        boolean cacheOnly;
        Processor(Path root) { this.root = root; }
        public boolean isWaitingForWorldUpdate() { return false; }
        public boolean isCurrentMapLocked() { return false; }
        public String getCurrentWorldId() { return "world-A"; }
        public String getCurrentDimId() { return "null"; }
        public String getCurrentMWId() { return mw; }
        public Processor getMapWorld() { return this; }
        public boolean isCacheOnlyMode() { return cacheOnly; }
        public Dimension getCurrentDimension() { return dimension; }
        public int getCurrentCaveLayer() { return layer; }
        public Processor getWorldDataHandler() { return this; }
        public Path getWorldDir() { return root; }
        public Save getMapSaveLoad() { return save; }
        public Writer getMapWriter() { return new Writer(); }
        public int getGlobalVersion() { return 10; }
        public final class Save {
            int loads;
            File cache;
            public Path getMWSubFolder(String world, String dim, String mw) { return root.resolve("maps").resolve(mw); }
            public Path getMWSubFolder(String world, Path dim, String mw) { throw new AssertionError("wrong overload"); }
            public boolean isRegionDetectionComplete() { return true; }
            public File getCacheFile(Object region, int layer, boolean outdated, boolean request) { return cache; }
            public void requestLoad(Leaf leaf, String reason, boolean prioritize) { loads++; leaf.reload = true; }
        }
    }
    public static class Dimension {
        boolean nativeView = true;
        final Layers regions = new Layers();
        public boolean isUsingWorldSave() { return nativeView; }
        public Layers getLayeredMapRegions() { return regions; }
    }
    public static class Layers {
        final Layer layer = new Layer();
        final Parent parent = new Parent();
        Leaf leaf;
        public Layer getLayer(int i) { return layer; }
        public Leaf getLeaf(int layer, int x, int z) { return leaf; }
        public Parent get(int layer, int x, int z, int level) { return parent; }
    }
    public static class Layer {
        RegionDetection detection;
        public RegionDetection getRegionDetection(int x, int z) { return detection; }
        public void addRegionDetection(RegionDetection detection) { this.detection = detection; }
    }
    public static class Parent {
        boolean dirty;
        public void setShouldCache(boolean value, String reason) { dirty = value; }
        public void setShouldCheckForUpdatesRecursive(boolean value) {}
    }
    public static class Leaf {
        byte state = 2;
        boolean writing, reload, dirty;
        int clears;
        public byte getLoadState() { return state; }
        public boolean reloadHasBeenRequested() { return reload; }
        public boolean isBeingWritten() { return writing; }
        public boolean isRefreshing() { return false; }
        public void clearRegion(Processor p) { state = 4; clears++; }
        public void setHasHadTerrain() {}
        public void setShouldCache(boolean value, String reason) { dirty = value; }
    }
}
