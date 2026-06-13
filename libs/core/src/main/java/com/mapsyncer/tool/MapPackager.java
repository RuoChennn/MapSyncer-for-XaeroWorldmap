package com.mapsyncer.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Standalone CLI tool that repackages a server cache directory into a client-ready Xaero map zip.
 *
 * <p>Path mapping: {cacheDir}/{dim}/*.zip → Multiplayer_{name}/{dim}/mw$<worldId>/*.zip</p>
 * <p>World ID is auto-detected from xaeromap.txt in the world directory, or defaults to 0.</p>
 * <p>Also converts generation_cache.properties to sync_timestamps.cache.</p>
 */
public final class MapPackager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPackager.class);

    private static final String GENERATION_CACHE = "generation_cache.properties";
    private static final String SYNC_TIMESTAMPS = "sync_timestamps.cache";
    private static final String XAERO_MAP_FILE = "xaeromap.txt";

    private final Path cacheDir;
    private final Path outputFile;
    private final String serverName;
    private final int worldId;

    private MapPackager(Path cacheDir, Path outputFile, String serverName, int worldId) {
        this.cacheDir = cacheDir.toAbsolutePath().normalize();
        this.outputFile = outputFile.toAbsolutePath().normalize();
        this.serverName = sanitizeServerName(serverName);
        this.worldId = worldId;
    }

    // ==================== entry point ====================

    public static void main(String[] args) {
        CliArgs cli = parseArgs(args);
        if (cli == null) {
            // null means --help was shown or parse error; exit 0 for help, 1 for error
            System.exit(0);
        }

        MapPackager packager = new MapPackager(cli.cacheDir, cli.output, cli.serverName, cli.worldId);
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
        LOGGER.info("  Server name: {}", serverName);
        LOGGER.info("  World ID: {}", worldId);

        validateSourceDir();

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String prefix = "Multiplayer_" + serverName + "/";
        String mwDir = String.format("mw$%d", worldId);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputFile))) {
            // 遍历维度目录，添加地图文件
            for (String dim : scanDimensions()) {
                Path dimPath = cacheDir.resolve(dim);
                String destBase = prefix + dim + "/" + mwDir + "/";
                addDirectoryToZip(zos, dimPath, destBase, "");
            }

            // 转换 generation_cache.properties → sync_timestamps.cache
            addTimestampsCache(zos, prefix);
        }

        LOGGER.info("Packaging complete: {} ({} bytes)", outputFile, Files.size(outputFile));
    }

    // ==================== source validation ====================

    private void validateSourceDir() {
        if (!Files.isDirectory(cacheDir)) {
            throw new IllegalArgumentException("Cache directory not found: " + cacheDir);
        }
        List<String> dims = scanDimensions();
        if (dims.isEmpty()) {
            throw new IllegalArgumentException("No dimension subdirectories found (null/, DIM-1/, etc): " + cacheDir);
        }
        LOGGER.info("Detected {} dimensions: {}", dims.size(), dims);
    }

    // ==================== dimension scanning ====================

    private List<String> scanDimensions() {
        List<String> dims = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(cacheDir, Files::isDirectory)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (!name.startsWith(".")) {
                    dims.add(name);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan dimensions: {}", e.getMessage());
        }
        dims.sort(String::compareTo);
        return dims;
    }

    // ==================== 递归添加目录到 zip ====================

    private void addDirectoryToZip(ZipOutputStream zos, Path sourceDir, String destPrefix, String relativePath) throws IOException {
        try (var stream = Files.newDirectoryStream(sourceDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();

                if (Files.isDirectory(entry)) {
                    addDirectoryToZip(zos, entry, destPrefix, relativePath + name + "/");
                } else if (isMapFile(name)) {
                    String zipEntryName = destPrefix + relativePath + name;
                    ZipEntry zipEntry = new ZipEntry(zipEntryName);
                    zos.putNextEntry(zipEntry);
                    Files.copy(entry, zos);
                    zos.closeEntry();
                    LOGGER.debug("  + {}", zipEntryName);
                }
                // exclude: generation_cache.properties, mca_timestamps.cache, *.temp, hidden files
            }
        }
    }

    private static boolean isMapFile(String name) {
        return name.endsWith(".zip")
            && !name.startsWith(".")
            && !name.endsWith(".temp")
            && !name.endsWith(".tmp");
    }

    // ==================== generation_cache → sync_timestamps ====================

    private void addTimestampsCache(ZipOutputStream zos, String prefix) throws IOException {
        Path genCacheFile = cacheDir.resolve(GENERATION_CACHE);
        if (!Files.isRegularFile(genCacheFile)) {
            LOGGER.warn("{} not found, skipping timestamps cache generation", GENERATION_CACHE);
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(genCacheFile)) {
            props.load(in);
        }

        // collect entries and dimensions
        List<String[]> entries = new ArrayList<>();
        Set<String> dimensions = new LinkedHashSet<>();

        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (value == null || value.isEmpty()) continue;

            entries.add(new String[]{key, value});

            int slashIdx = key.indexOf('/');
            if (slashIdx > 0) {
                dimensions.add(key.substring(0, slashIdx));
            }
        }

        if (entries.isEmpty()) {
            LOGGER.warn("No entries in {}, skipping timestamps cache", GENERATION_CACHE);
            return;
        }

        // generate in ClientTimestampCache format
        StringBuilder sb = new StringBuilder();
        sb.append("# Sync timestamps cache\n");
        sb.append("# ==================== STATE ====================\n");
        sb.append("_state=completed\n");
        sb.append("_dimensions=").append(String.join(",", dimensions)).append("\n");
        sb.append("_command=\n");
        sb.append("\n");
        sb.append("# ==================== TIMESTAMP CACHE ====================\n");
        sb.append("# Format: dimension/region_x_z = timestamp_seconds:hash\n");

        for (String[] entry : entries) {
            sb.append(entry[0]).append(" = ").append(entry[1]).append("\n");
        }

        String zipEntryName = prefix + SYNC_TIMESTAMPS;
        ZipEntry zipEntry = new ZipEntry(zipEntryName);
        zos.putNextEntry(zipEntry);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
        LOGGER.info("  + {} ({} entries, dimensions: {})", zipEntryName, entries.size(), dimensions);
    }

    // ==================== CLI parsing ====================

    private static CliArgs parseArgs(String[] args) {
        Path cacheDir = null;
        Path output = null;
        String serverName = "Server";
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

        // Priority: explicit --world-id > auto-detect from --world-dir > default 0
        if (worldId == null) {
            worldId = worldDir != null ? readWorldId(worldDir) : 0;
        }

        return new CliArgs(cacheDir, output, serverName, worldId);
    }

    private static void printHelp() {
        System.out.println("MapPackager - Xaero World Map packager for server cache");
        System.out.println();
        System.out.println("Usage: java -jar mapsyncer-packager.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -c, --cache-dir <path>    Server cache directory path (required)");
        System.out.println("  -o, --output <path>       Output zip file path (required)");
        System.out.println("  -s, --server-name <name>  Server name, default \"Server\"");
        System.out.println("  -w, --world-id <id>       World ID override (auto-detected from xaeromap.txt by default)");
        System.out.println("  -d, --world-dir <path>    World directory to read xaeromap.txt from (auto-detect world ID)");
        System.out.println("  -h, --help                Show this help");
        System.out.println();
        System.out.println("World ID auto-detection:");
        System.out.println("  If --world-dir is specified, reads <dir>/xaeromap.txt for the world ID.");
        System.out.println("  If neither --world-id nor --world-dir is given, defaults to 0.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar mapsyncer-packager.jar -c ./cache -o ./output.zip");
        System.out.println("  java -jar mapsyncer-packager.jar -c ./cache -d ./world -o output.zip");
    }

    // ==================== utility ====================

    /**
     * Read world ID from a Xaero server-side xaeromap.txt file.
     * Format: "id:<int>"
     */
    static int readWorldId(Path worldDir) {
        Path mapFile = worldDir.resolve(XAERO_MAP_FILE);
        if (!Files.isRegularFile(mapFile)) {
            LOGGER.warn("{} not found in world directory, falling back to 0", XAERO_MAP_FILE);
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
            LOGGER.warn("Failed to read {}: {}, falling back to 0", mapFile, e.getMessage());
        }
        return 0;
    }

    private static String sanitizeServerName(String name) {
        if (name == null || name.isBlank()) return "Server";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    // ==================== 内部类型 ====================

    private record CliArgs(Path cacheDir, Path output, String serverName, int worldId) {}
}
