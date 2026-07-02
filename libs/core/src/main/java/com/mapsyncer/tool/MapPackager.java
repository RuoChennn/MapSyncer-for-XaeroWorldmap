package com.mapsyncer.tool;

import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Standalone CLI tool that repackages a server cache directory into a client-ready Xaero map zip.
 *
 * <p>Server layout (flat or with mw$):</p>
 * <pre>
 * {cacheDir}/{dim}/*.zip
 * {cacheDir}/{dim}/caves/{layer}/*.zip
 * {cacheDir}/{dim}/mw${id}/*.zip   (legacy / re-imported layout)
 * </pre>
 *
 * <p>Client layout:</p>
 * <pre>
 * Multiplayer_{server}/{dim}/mw${worldId}/*.zip
 * Multiplayer_{server}/{dim}/mw${worldId}/caves/{layer}/*.zip
 * Multiplayer_{server}/sync_timestamps.cache
 * </pre>
 */
public final class MapPackager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPackager.class);

    private static final String GENERATION_CACHE = "generation_cache.properties";
    private static final String SYNC_TIMESTAMPS = "sync_timestamps.cache";
    private static final String XAERO_MAP_FILE = "xaeromap.txt";
    /** 未指定服务器地址时使用的占位名，对应 Xaero 目录 Multiplayer_Server/ */
    static final String PLACEHOLDER_SERVER = "Server";

    private final Path cacheDir;
    private final Path outputFile;
    private final String serverFolderName;
    private final int worldId;

    private MapPackager(Path cacheDir, Path outputFile, String serverFolderName, int worldId) {
        this.cacheDir = cacheDir.toAbsolutePath().normalize();
        this.outputFile = outputFile.toAbsolutePath().normalize();
        this.serverFolderName = serverFolderName;
        this.worldId = worldId;
    }

    // ==================== entry point ====================

    public static void main(String[] args) {
        CliArgs cli = parseArgs(args);
        if (cli == null) {
            System.exit(0);
        }

        MapPackager packager = new MapPackager(cli.cacheDir, cli.output, cli.serverFolderName, cli.worldId);
        try {
            packager.execute();
            System.exit(0);
        } catch (Exception e) {
            LOGGER.error("Packaging failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // ==================== main flow ====================

    private void execute() throws IOException {
        LOGGER.info("MapPackager starting");
        LOGGER.info("  Cache dir: {}", cacheDir);
        LOGGER.info("  Output file: {}", outputFile);
        LOGGER.info("  Server folder: Multiplayer_{}", serverFolderName);
        LOGGER.info("  World ID: {}", worldId);

        validateSourceDir();

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String prefix = "Multiplayer_" + serverFolderName + "/";
        String mwDirName = "mw$" + worldId;
        Map<String, TimestampHashEntry> generationCache = loadGenerationCache();
        List<PackagedRegion> packagedRegions = new ArrayList<>();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputFile))) {
            for (String dim : scanDimensions()) {
                Path dimPath = cacheDir.resolve(dim);
                Path sourceRoot = resolveMapSourceRoot(dimPath, worldId);
                String destBase = prefix + dim + "/" + mwDirName + "/";
                packageMapFiles(zos, dim, sourceRoot, destBase, "", packagedRegions, generationCache);
            }

            if (packagedRegions.isEmpty()) {
                throw new IllegalStateException("No valid region map files found in cache directory");
            }

            addTimestampsCache(zos, prefix, packagedRegions);
        }

        LOGGER.info("Packaging complete: {} regions, {} bytes -> {}",
            packagedRegions.size(), Files.size(outputFile), outputFile);
    }

    // ==================== source validation ====================

    private void validateSourceDir() {
        if (!Files.isDirectory(cacheDir)) {
            throw new IllegalArgumentException("Cache directory not found: " + cacheDir);
        }
        List<String> dims = scanDimensions();
        if (dims.isEmpty()) {
            throw new IllegalArgumentException(
                "No dimension subdirectories with map data found (null/, DIM-1/, etc): " + cacheDir);
        }
        LOGGER.info("Detected {} dimensions: {}", dims.size(), dims);
    }

    // ==================== dimension scanning ====================

    /**
     * 扫描含地图数据的维度目录，排除无 zip 的空目录及非维度目录。
     */
    private List<String> scanDimensions() {
        List<String> dims = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(cacheDir, Files::isDirectory)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || isExcludedDirectory(name)) {
                    continue;
                }
                if (hasMapContent(dir)) {
                    dims.add(name);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan dimensions: {}", e.getMessage());
        }
        dims.sort(String::compareTo);
        return dims;
    }

    private static boolean isExcludedDirectory(String name) {
        return name.equals("caves") || name.startsWith("cache");
    }

    private static boolean hasMapContent(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(p -> Files.isRegularFile(p) && isMapFile(p.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 解析维度的地图源根目录。
     * 服务端通常为扁平结构；若存在 mw$ 子目录则从中读取（避免路径重复）。
     */
    static Path resolveMapSourceRoot(Path dimDir, int targetWorldId) throws IOException {
        Path preferred = dimDir.resolve("mw$" + targetWorldId);
        if (Files.isDirectory(preferred) && hasMapContent(preferred)) {
            return preferred;
        }

        List<Path> mwDirs = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dimDir, Files::isDirectory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith("mw$") && hasMapContent(entry)) {
                    mwDirs.add(entry);
                }
            }
        }

        if (mwDirs.size() == 1) {
            Path only = mwDirs.get(0);
            if (!only.equals(preferred)) {
                LOGGER.info("Using map source {} for dimension {}", only.getFileName(), dimDir.getFileName());
            }
            return only;
        }
        if (mwDirs.size() > 1) {
            LOGGER.warn("Multiple mw$ directories in {}, using first: {}", dimDir.getFileName(), mwDirs.get(0).getFileName());
            return mwDirs.get(0);
        }

        return dimDir;
    }

    // ==================== map file packaging ====================

    private void packageMapFiles(
            ZipOutputStream zos,
            String dim,
            Path sourceDir,
            String destPrefix,
            String relativePath,
            List<PackagedRegion> packagedRegions,
            Map<String, TimestampHashEntry> generationCache) throws IOException {

        try (var stream = Files.newDirectoryStream(sourceDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }

                if (Files.isDirectory(entry)) {
                    if (shouldSkipSubDirectory(name)) {
                        continue;
                    }
                    packageMapFiles(zos, dim, entry, destPrefix, relativePath + name + "/",
                        packagedRegions, generationCache);
                } else if (isMapFile(name)) {
                    if (!HashUtils.isValidRegionZip(entry)) {
                        LOGGER.warn("Skipping invalid region zip: {}", entry);
                        continue;
                    }

                    String zipEntryName = destPrefix + relativePath + name;
                    ZipEntry zipEntry = new ZipEntry(zipEntryName);
                    zos.putNextEntry(zipEntry);
                    Files.copy(entry, zos);
                    zos.closeEntry();

                    String cacheKey = buildCacheKey(dim, relativePath, name);
                    TimestampHashEntry cacheEntry = resolveCacheEntry(cacheKey, generationCache, entry);

                    packagedRegions.add(new PackagedRegion(cacheKey, cacheEntry.timestampSeconds(), cacheEntry.hash()));
                    LOGGER.debug("  + {} ({}:{})", zipEntryName, cacheEntry.timestampSeconds(), cacheEntry.hash());
                }
            }
        }
    }

    private static boolean shouldSkipSubDirectory(String name) {
        return name.startsWith("cache") || name.startsWith("mw$");
    }

    private static boolean isMapFile(String name) {
        return name.endsWith(".zip")
            && !name.startsWith(".")
            && !name.endsWith(".temp")
            && !name.endsWith(".tmp");
    }

    /**
     * 构建与 GenerationCache / ClientTimestampCache 一致的缓存键。
     * 例：null/0_0、null/caves/32/1_-2
     */
    static String buildCacheKey(String dim, String relativePath, String zipFileName) {
        String regionCoords = zipFileName.substring(0, zipFileName.length() - 4);
        if (relativePath.isEmpty()) {
            return dim + "/" + regionCoords;
        }
        String normalized = relativePath.replace("\\", "/");
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return dim + "/" + normalized + "/" + regionCoords;
    }

    /**
     * 优先从 generation_cache.properties 读取时间戳与 CRC32；
     * 缓存缺失时再回退到文件修改时间与现场计算哈希。
     */
    private static TimestampHashEntry resolveCacheEntry(
            String cacheKey,
            Map<String, TimestampHashEntry> generationCache,
            Path sourceFile) throws IOException {
        TimestampHashEntry cached = generationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long timestamp = Files.getLastModifiedTime(sourceFile).toMillis() / 1000;
        String hash = HashUtils.computeFileHash(sourceFile);
        LOGGER.debug("No generation_cache entry for {}, using mtime + computed hash", cacheKey);
        return new TimestampHashEntry(timestamp, hash);
    }

    // ==================== generation_cache → sync_timestamps ====================

    private Map<String, TimestampHashEntry> loadGenerationCache() {
        Path genCacheFile = cacheDir.resolve(GENERATION_CACHE);
        if (!Files.isRegularFile(genCacheFile)) {
            LOGGER.warn("{} not found, timestamps/hashes will fall back to file metadata", GENERATION_CACHE);
            return Map.of();
        }
        return PropertiesCacheIO.load(genCacheFile, PropertiesCacheIO::parseTimestampHash);
    }

    private void addTimestampsCache(ZipOutputStream zos, String prefix, List<PackagedRegion> packagedRegions)
            throws IOException {
        Map<String, PackagedRegion> regionByKey = new LinkedHashMap<>();
        Set<String> dimensions = new LinkedHashSet<>();

        for (PackagedRegion region : packagedRegions) {
            regionByKey.put(region.cacheKey(), region);
            int slashIdx = region.cacheKey().indexOf('/');
            if (slashIdx > 0) {
                dimensions.add(region.cacheKey().substring(0, slashIdx));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Sync timestamps cache\n");
        sb.append("# ==================== STATE ====================\n");
        sb.append("_state=completed\n");
        sb.append("_dimensions=").append(String.join(",", dimensions)).append("\n");
        sb.append("_command=\n");
        sb.append("\n");
        sb.append("# ==================== TIMESTAMP CACHE ====================\n");
        sb.append("# Format: dimension/region_x_z=timestamp_seconds:hash\n");

        for (PackagedRegion region : regionByKey.values()) {
            sb.append(region.cacheKey())
                .append("=")
                .append(region.timestampSeconds())
                .append(":")
                .append(region.hash())
                .append("\n");
        }

        String zipEntryName = prefix + SYNC_TIMESTAMPS;
        ZipEntry zipEntry = new ZipEntry(zipEntryName);
        zos.putNextEntry(zipEntry);
        zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        LOGGER.info("  + {} ({} entries, dimensions: {})", zipEntryName, regionByKey.size(), dimensions);
    }

    // ==================== CLI parsing ====================

    private static CliArgs parseArgs(String[] args) {
        Path cacheDir = null;
        Path output = null;
        String serverName = PLACEHOLDER_SERVER;
        String serverAddress = null;
        Integer worldId = null;
        Path worldDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--cache-dir":
                case "-c":
                    cacheDir = Path.of(args[++i]);
                    break;
                case "--output":
                case "-o":
                    output = Path.of(args[++i]);
                    break;
                case "--server-address":
                case "-a":
                    serverAddress = args[++i];
                    break;
                case "--server-name":
                case "-s":
                    serverName = args[++i];
                    break;
                case "--world-id":
                case "-w":
                    worldId = Integer.parseInt(args[++i]);
                    break;
                case "--world-dir":
                case "-d":
                    worldDir = Path.of(args[++i]);
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    return null;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printHelp();
                    return null;
            }
        }

        if (cacheDir == null || output == null) {
            System.err.println("Missing required options --cache-dir and/or --output");
            printHelp();
            return null;
        }

        cacheDir = cacheDir.toAbsolutePath().normalize();
        worldId = resolveWorldId(cacheDir, worldDir, worldId);

        String serverFolderName = resolveServerFolderName(serverAddress, serverName);
        return new CliArgs(cacheDir, output, serverFolderName, worldId);
    }

    /**
     * World ID 优先级：--world-id &gt; xaeromap.txt &gt; 缓存目录 mw$ &gt; 0
     */
    static int resolveWorldId(Path cacheDir, Path worldDir, Integer explicitWorldId) {
        if (explicitWorldId != null) {
            return explicitWorldId;
        }
        if (worldDir != null && Files.isRegularFile(worldDir.resolve(XAERO_MAP_FILE))) {
            return readWorldId(worldDir);
        }
        Integer fromCache = detectWorldIdFromCache(cacheDir);
        if (fromCache != null) {
            LOGGER.info("Auto-detected world ID {} from cache mw$ directory", fromCache);
            return fromCache;
        }
        return 0;
    }

    static Integer detectWorldIdFromCache(Path cacheDir) {
        if (!Files.isDirectory(cacheDir)) {
            return null;
        }
        try (var stream = Files.walk(cacheDir, 3)) {
            return stream.filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.startsWith("mw$"))
                .map(MapPackager::parseMwWorldId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Integer parseMwWorldId(String mwDirName) {
        try {
            return Integer.parseInt(mwDirName.substring(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void printHelp() {
        System.out.println("MapPackager - Xaero World Map packager for server cache");
        System.out.println();
        System.out.println("Usage: java -jar mapsyncer-packager.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -c, --cache-dir <path>    Server cache directory path (required)");
        System.out.println("  -o, --output <path>       Output zip file path (required)");
        System.out.println("  -a, --server-address <addr>  Server address (IP/host:port), replaces placeholder \""
            + PLACEHOLDER_SERVER + "\" in Multiplayer folder name");
        System.out.println("  -s, --server-name <name>     Server folder name when --server-address is omitted, default \""
            + PLACEHOLDER_SERVER + "\"");
        System.out.println("  -w, --world-id <id>       World ID override");
        System.out.println("  -d, --world-dir <path>    World directory to read xaeromap.txt from (auto-detect world ID)");
        System.out.println("  -h, --help                Show this help");
        System.out.println();
        System.out.println("World ID auto-detection (in priority order):");
        System.out.println("  1. --world-id");
        System.out.println("  2. xaeromap.txt via --world-dir");
        System.out.println("  3. mw$ directory in cache");
        System.out.println("  4. default 0");
        System.out.println();
        System.out.println("Output includes:");
        System.out.println("  - Region map zips under Multiplayer_<server>/<dim>/mw$<worldId>/");
        System.out.println("  - Cave layers under .../mw$<worldId>/caves/<layer>/");
        System.out.println("  - sync_timestamps.cache (timestamps + CRC32 from generation_cache.properties)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar mapsyncer-packager.jar -c ./cache -o ./output.zip");
        System.out.println("  java -jar mapsyncer-packager.jar -c ./cache -d ./world -o output.zip");
        System.out.println("  java -jar mapsyncer-packager.jar -c ./cache -a play.example.com:25565 -o output.zip");
    }

    // ==================== utility ====================

    static int readWorldId(Path worldDir) {
        Path mapFile = worldDir.resolve(XAERO_MAP_FILE);
        if (!Files.isRegularFile(mapFile)) {
            LOGGER.warn("{} not found in world directory, falling back to cache/default", XAERO_MAP_FILE);
            return 0;
        }
        try (BufferedReader reader = Files.newBufferedReader(mapFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 2 && "id".equals(parts[0].trim())) {
                    int id = Integer.parseInt(parts[1].trim());
                    LOGGER.info("Found world ID {} in {}/{}", id, worldDir.getFileName(), XAERO_MAP_FILE);
                    return id;
                }
            }
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("Failed to read {}: {}", mapFile, e.getMessage());
        }
        return 0;
    }

    static String resolveServerFolderName(String serverAddress, String serverName) {
        if (serverAddress != null && !serverAddress.isBlank()) {
            return cleanServerAddress(serverAddress);
        }
        return sanitizeServerName(serverName);
    }

    static String cleanServerAddress(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            return PLACEHOLDER_SERVER;
        }

        String cleaned = rawAddress.trim();
        int portDivider = cleaned.lastIndexOf(':');
        if (portDivider > 0 && cleaned.indexOf(':') != cleaned.lastIndexOf(':')) {
            portDivider = cleaned.lastIndexOf("]:") + 1;
        }
        if (portDivider > 0) {
            cleaned = cleaned.substring(0, portDivider);
        }
        cleaned = cleaned.replace("[", "").replace("]", "");
        cleaned = cleaned.replaceAll(":", ".");
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            cleaned = "Empty Address";
        }
        return cleaned.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String sanitizeServerName(String name) {
        if (name == null || name.isBlank()) return PLACEHOLDER_SERVER;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private record PackagedRegion(String cacheKey, long timestampSeconds, String hash) {}

    private record CliArgs(Path cacheDir, Path output, String serverFolderName, int worldId) {}
}
