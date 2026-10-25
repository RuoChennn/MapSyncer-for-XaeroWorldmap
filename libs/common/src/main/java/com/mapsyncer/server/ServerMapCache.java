package com.mapsyncer.server;

import com.mapsyncer.util.DimensionPathMapping;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 服务端地图目录快照。内置服直接使用 Xaero 文件，不建立地图副本。 */
public final class ServerMapCache {
    private static volatile Snapshot current = new Snapshot(Path.of("server_map_cache"), false, Map.of());
    private static Path integratedSave;

    private ServerMapCache() {}

    public static Snapshot snapshot() { return current; }

    public static synchronized void configure(Path root, Path save) {
        integratedSave = save == null ? null : save.toAbsolutePath().normalize();
        current = new Snapshot(root.toAbsolutePath().normalize(), save != null, Map.of());
        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
    }

    /** 仅接收当前存档的客户端主线程快照；旧世界不能重新绑定新会话。 */
    public static synchronized void capture(Path save, Path root, Map<String, String> selections) {
        if (integratedSave == null || !integratedSave.equals(save.toAbsolutePath().normalize())) return;
        Snapshot next = new Snapshot(root.toAbsolutePath().normalize(), true, selections);
        if (!current.root().equals(next.root())) {
            GenerationCache.resetInstance();
            McaTimestampCache.resetInstance();
        }
        current = next;
    }

    public record Snapshot(Path root, boolean sharedXaero, Map<String, String> selections) {
        public Snapshot {
            selections = Map.copyOf(selections);
            for (var entry : selections.entrySet()) {
                if (!DimensionPathMapping.isSafeXaeroFolder(entry.getKey()) || !safeSelection(entry.getValue())) {
                    throw new IllegalArgumentException("Unsafe Xaero map selection");
                }
            }
        }

        private static boolean safeSelection(String name) {
            return name != null && (name.isEmpty() || (!name.isBlank() && !name.equals(".")
                    && !name.equals("..") && !name.endsWith(".") && !name.endsWith(" ")
                    && !name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")));
        }

        public Path dimensionDirectory(String dimension) {
            if (!DimensionPathMapping.isSafeXaeroFolder(dimension)) {
                throw new IllegalArgumentException("Unsafe dimension folder");
            }
            Path directory = root.resolve(dimension);
            if (!sharedXaero) return directory;
            String selection = selections.getOrDefault(dimension, "");
            // 单人存档视图的空 ID 读取 MCA；Xaero 的原生转换另存到 cm$converted，
            // 完成后不会切换 currentMultiworld。未访问的维度也生成到该目录。
            return directory.resolve(selection.isEmpty() ? "cm$converted" : selection);
        }

        /** 返回协议中的维度/层/坐标键；存档视图使用转换结果，其他视图仅使用所选地图。 */
        public String regionKey(Path file) {
            Path absoluteRoot = root.toAbsolutePath().normalize();
            Path absolute = file.toAbsolutePath().normalize();
            if (!absolute.startsWith(absoluteRoot)) return null;
            Path relative = absoluteRoot.relativize(absolute);
            if (relative.getNameCount() < 2) return null;
            String dimension = relative.getName(0).toString();
            if (!DimensionPathMapping.isSafeXaeroFolder(dimension)) return null;
            Path base = dimensionDirectory(dimension).toAbsolutePath().normalize();
            if (!absolute.startsWith(base)) return null;
            String suffix = base.relativize(absolute).toString().replace('\\', '/');
            if (!suffix.matches("(?:caves/-?\\d+/)?-?\\d+_-?\\d+\\.zip")) return null;
            return dimension + "/" + suffix.substring(0, suffix.length() - 4);
        }

        public List<Path> regionFiles() throws IOException {
            if (!Files.isDirectory(root)) return List.of();
            try (var files = Files.walk(root)) {
                return files.filter(Files::isRegularFile).filter(p -> regionKey(p) != null).toList();
            }
        }

        public long lastModifiedSeconds() {
            try {
                long latest = 0;
                for (Path file : regionFiles()) latest = Math.max(latest, Files.getLastModifiedTime(file).toMillis() / 1000);
                return latest;
            } catch (IOException e) {
                return 0;
            }
        }

        /** 原生 Xaero 文件每次重新取哈希和时间，不依赖 MapSyncer 生成记录。 */
        public com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry metadata(Path file) throws IOException {
            return new com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry(
                    Files.getLastModifiedTime(file).toMillis() / 1000,
                    com.mapsyncer.util.HashUtils.computeFileHash(file));
        }

        /** --force 仅删除所选地图的 region ZIP，保留 Xaero 配置和其他多世界地图。 */
        public void clearDimensionRegions(String dimension) throws IOException {
            for (Path file : regionFiles()) {
                if (regionKey(file).startsWith(dimension + "/")) Files.deleteIfExists(file);
            }
        }
    }
}
