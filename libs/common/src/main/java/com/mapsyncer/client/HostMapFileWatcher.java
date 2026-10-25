package com.mapsyncer.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** 仅监听当前地图的数据目录；后台合并落盘事件，不保存世界、不生成地图、不发送网络包。 */
public final class HostMapFileWatcher implements AutoCloseable {
    public record Change(Path file, int x, int z, boolean contentChanged) {}
    private record Pending(Change change, long after) {}
    private static final int LIMIT = 4096;
    private static final long SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(1000);
    private final Path directory;
    private final boolean worldSave;
    private final Pattern filename;
    private final WatchService watcher;
    private final Thread thread;
    private final Map<Path, Pending> pending = new LinkedHashMap<>();
    private final Map<Path, Change> ready = new LinkedHashMap<>();
    private volatile boolean closed;
    private volatile IOException failure;
    private Path watched;
    private WatchKey key;
    private boolean rescan = true;
    private boolean rescanChanges;
    private boolean scanChanges;
    private DirectoryStream<Path> scan;
    private Iterator<Path> scanFiles;

    public HostMapFileWatcher(Path directory, boolean worldSave) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.worldSave = worldSave;
        this.filename = Pattern.compile(worldSave ? "r\\.(-?\\d+)\\.(-?\\d+)\\.mc[ar]" : "(-?\\d+)_(-?\\d+)\\.zip");
        watcher = FileSystems.getDefault().newWatchService();
        thread = new Thread(this::run, "mapsyncer-host-map-watch");
        thread.setDaemon(true);
        thread.start();
    }

    public IOException failure() { return failure; }

    /** 从客户端主线程调用；不执行磁盘 IO。 */
    public synchronized Change poll() {
        var iterator = ready.entrySet().iterator();
        if (!iterator.hasNext()) return null;
        Change change = iterator.next().getValue();
        iterator.remove();
        return change;
    }

    /** Xaero 忙碌或区域仍在近处：保留变更，不能覆盖后来到达的新事件。 */
    public synchronized void retry(Change change) {
        if (!closed && !pending.containsKey(change.file()) && !ready.containsKey(change.file())) {
            pending.put(change.file(), new Pending(change, System.nanoTime() + SETTLE_NANOS));
        }
    }

    private synchronized void mark(Path file, boolean changed) {
        var match = filename.matcher(file.getFileName().toString());
        if (!match.matches()) return;
        if (!pending.containsKey(file) && !ready.containsKey(file) && pending.size() + ready.size() >= LIMIT) {
            rescan = true;
            rescanChanges |= changed;
            return;
        }
        try {
            Pending previous = pending.get(file);
            Change previousReady = ready.get(file);
            changed |= previous != null && previous.change().contentChanged();
            changed |= previousReady != null && previousReady.contentChanged();
            Change change = new Change(file, Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), changed);
            ready.remove(file);
            pending.put(file, new Pending(change, System.nanoTime() + SETTLE_NANOS));
        } catch (NumberFormatException ignored) { /* 非法坐标文件不进入原生地图加载器。 */ }
    }

    private void updateRegistration() throws IOException {
        Path target = directory;
        while (target != null && !Files.isDirectory(target)) target = target.getParent();
        if (target == null || (target.equals(watched) && key != null && key.isValid())) return;
        if (key != null) key.cancel();
        watched = target;
        key = target.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        rescan = true;
    }

    private void scanSome() throws IOException {
        if (scan == null && rescan && directory.equals(watched)) {
            rescan = false;
            scanChanges = rescanChanges;
            rescanChanges = false;
            scan = Files.newDirectoryStream(directory);
            scanFiles = scan.iterator();
        }
        if (scan == null) return;
        for (int i = 0; i < 64; i++) {
            synchronized (this) { if (pending.size() + ready.size() >= LIMIT) return; }
            if (!scanFiles.hasNext()) {
                scan.close();
                scan = null;
                scanFiles = null;
                return;
            }
            mark(scanFiles.next(), scanChanges);
        }
    }

    private void settleSome() {
        List<Pending> candidates;
        synchronized (this) {
            long now = System.nanoTime();
            candidates = pending.values().stream().filter(p -> p.after() <= now).limit(32).toList();
        }
        for (Pending item : candidates) {
            Path file = item.change().file();
            boolean exists = Files.isRegularFile(file);
            boolean valid = false;
            boolean waitingForZip = false;
            try {
                if (exists && worldSave) valid = Files.size(file) > 8192;
                else if (exists) {
                    waitingForZip = true;
                    try (ZipFile zip = new ZipFile(file.toFile())) { valid = zip.size() > 0; }
                }
            } catch (IOException ignored) { /* 等待文件写完或原子替换结束。 */ }
            synchronized (this) {
                if (pending.get(file) != item) continue;
                pending.remove(file);
                if (valid) ready.put(file, item.change());
                // 空 MCA 常由预生成器提前创建；只在下一次写事件后重查，避免长期空轮询。
                else if (exists && waitingForZip) pending.put(file, new Pending(item.change(), System.nanoTime() + SETTLE_NANOS));
            }
        }
    }

    private void run() {
        try {
            while (!closed) {
                updateRegistration();
                WatchKey eventKey = watcher.poll(100, TimeUnit.MILLISECONDS);
                if (eventKey != null) {
                    for (WatchEvent<?> event : eventKey.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) { rescan = true; rescanChanges = true; continue; }
                        if (eventKey != key) continue;
                        if (!directory.equals(watched)) { rescan = true; continue; }
                        Path file = directory.resolve((Path) event.context());
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            synchronized (this) { pending.remove(file); ready.remove(file); }
                        } else mark(file, true);
                    }
                    eventKey.reset();
                }
                scanSome();
                settleSome();
            }
        } catch (ClosedWatchServiceException | InterruptedException ignored) {
        } catch (IOException | RuntimeException e) {
            if (!closed) failure = e instanceof IOException io ? io : new IOException(e);
        } finally {
            try { if (scan != null) scan.close(); } catch (IOException ignored) {}
            try { watcher.close(); } catch (IOException ignored) {}
        }
    }

    @Override public synchronized void close() {
        closed = true;
        pending.clear();
        ready.clear();
        try { watcher.close(); } catch (IOException ignored) {}
        thread.interrupt();
    }
}
