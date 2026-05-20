package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 处理 Xaero 区域文件在区块级别的增量合并。
 * 将服务端数据与现有客户端数据合并，仅添加客户端不存在的新区块。
 *
 * <p>此类为备用功能，当前未使用。</p>
 *
 * <p>当前同步逻辑直接覆盖写入服务端数据（XaeroMapIntegrator.writeMapData），
 * 不进行区块级合并。保留用于以下潜在场景：</p>
 * <ul>
 *   <li>需要保护客户端探索的新内容不被覆盖</li>
 *   <li>需要增量合并而非全量覆盖的场景</li>
 *   <li>需要检测缺失区块进行选择性同步</li>
 * </ul>
 *
 * @deprecated 当前同步逻辑直接覆盖写入，不进行区块级合并。
 *             保留用于潜在的未来需求。参见 XaeroMapIntegrator.writeMapData。
 * @see XaeroMapIntegrator.writeMapData 当前使用的直接写入方式
 */
@Deprecated(since = "2026-05-21", forRemoval = false)
public class RegionMerger {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionMerger.class);

    /** Xaero 文件格式常量：版本标记 */
    private static final int VERSION_MARKER = 0xFF;

    /** Xaero 文件格式常量：完整版本号（major=6, minor=8） */
    private static final int FULL_VERSION = 393224;

    /**
     * 合并操作的结果。
     *
     * @param clientChunks 客户端原有区块数量
     * @param serverChunks 服务端区块数量
     * @param newChunksAdded 新增的区块数量
     * @param fileChanged 文件是否发生变化
     */
    public static class MergeResult {
        /** 客户端原有区块数量 */
        public final int clientChunks;
        /** 服务端区块数量 */
        public final int serverChunks;
        /** 新增的区块数量 */
        public final int newChunksAdded;
        /** 文件是否发生变化 */
        public final boolean fileChanged;

        /**
         * 构造合并结果。
         *
         * @param clientChunks 客户端原有区块数量
         * @param serverChunks 服务端区块数量
         * @param newChunksAdded 新增的区块数量
         * @param fileChanged 文件是否发生变化
         */
        public MergeResult(int clientChunks, int serverChunks, int newChunksAdded, boolean fileChanged) {
            this.clientChunks = clientChunks;
            this.serverChunks = serverChunks;
            this.newChunksAdded = newChunksAdded;
            this.fileChanged = fileChanged;
        }
    }

    /**
     * 完整性检查结果。
     *
     * @param totalChunks 总区块数（固定为64）
     * @param chunksWithData 有数据的区块数量
     * @param isComplete 是否完整（所有区块都有数据）
     */
    public static class CompletenessResult {
        /** 总区块数（固定为64） */
        public final int totalChunks;
        /** 有数据的区块数量 */
        public final int chunksWithData;
        /** 是否完整 */
        public final boolean isComplete;

        /**
         * 构造完整性检查结果。
         *
         * @param totalChunks 总区块数
         * @param chunksWithData 有数据的区块数量
         * @param isComplete 是否完整
         */
        public CompletenessResult(int totalChunks, int chunksWithData, boolean isComplete) {
            this.totalChunks = totalChunks;
            this.chunksWithData = chunksWithData;
            this.isComplete = isComplete;
        }
    }

    /**
     * 检查区域文件是否已完全生成（所有64个区块都有数据）。
     * 完整的区域将不需要从服务器获取任何额外的区块。
     *
     * <p>简化方法：由于服务器缓存文件是从完整世界数据生成的，
     * 我们假设具有合理大小（>2KB）的文件是完整的。
     * 复杂的 Xaero 像素格式解析由于动态参数标志而不可靠。</p>
     *
     * @param regionFile 要检查的区域 zip 文件
     * @return 包含统计信息的完整性检查结果
     * @throws IOException 如果读取文件失败
     */
    public static CompletenessResult checkCompleteness(Path regionFile) throws IOException {
        if (regionFile == null || !Files.exists(regionFile)) {
            return new CompletenessResult(64, 0, false);
        }

        // Simplified check: file size based heuristic
        // A complete region should have at least 2KB of data (64 chunks with pixel data)
        // Empty regions or placeholder files would be much smaller
        long fileSize = Files.size(regionFile);

        // Minimum threshold for a complete region:
        // - Zip overhead: ~100 bytes
        // - Version header: 5 bytes
        // - Each chunk minimum: 1 byte coord + some tile data
        // - 64 chunks with actual terrain data typically results in 10KB-50KB+
        // Use 2KB as conservative threshold
        final long MIN_COMPLETE_SIZE = 2048;

        boolean isComplete = fileSize >= MIN_COMPLETE_SIZE;
        int estimatedChunks = isComplete ? 64 : 0;

        LOGGER.debug("Region {} size: {} bytes, complete: {}",
                regionFile.getFileName(), fileSize, isComplete);

        return new CompletenessResult(64, estimatedChunks, isComplete);
    }

    /**
     * 查找区域文件中的缺失区块。
     * 区块被认为缺失的条件：不存在或所有16个图块都有 tileMarker == -1。
     *
     * @param regionFile 要检查的区域 zip 文件
     * @return 缺失区块坐标集合（0-63）
     * @throws IOException 如果读取文件失败
     */
    public static Set<Integer> findMissingChunks(Path regionFile) throws IOException {
        if (regionFile == null || !Files.exists(regionFile)) {
            // All 64 chunks are missing
            LOGGER.info("Region file does not exist, all 64 chunks are missing");
            Set<Integer> allMissing = new HashSet<>();
            for (int i = 0; i < 64; i++) {
                allMissing.add(i);
            }
            return allMissing;
        }

        Map<Integer, ChunkData> chunks = parseRegionFile(regionFile);
        Set<Integer> missing = new HashSet<>();

        LOGGER.info("Parsing region {}: found {} chunks in file", regionFile.getFileName(), chunks.size());

        for (int coord = 0; coord < 64; coord++) {
            ChunkData chunk = chunks.get(coord);
            if (chunk == null) {
                missing.add(coord);
                LOGGER.debug("Chunk coord {} not found in file", coord);
            } else if (isChunkEmpty(chunk)) {
                missing.add(coord);
                LOGGER.debug("Chunk coord {} exists but is empty (rawData size: {})", coord, chunk.rawData.length);
            }
        }

        LOGGER.info("Region {} has {} missing chunks out of 64",
                regionFile.getFileName(), missing.size());

        return missing;
    }

    /**
     * 检查区块是否为空（所有16个图块都有 tileMarker == -1）。
     *
     * @param chunk 要检查的区块数据
     * @return 如果所有图块都为空返回 true；否则返回 false
     */
    private static boolean isChunkEmpty(ChunkData chunk) {
        if (chunk == null || chunk.rawData == null) {
            return true;
        }

        // rawData structure: [chunkCoord byte] + [16 tiles data]
        // Each tile starts with an int (tileMarker)
        // If tileMarker == -1, tile is empty (4 bytes only)
        // Minimum empty chunk: 1 byte (coord) + 16 * 4 bytes (-1) = 65 bytes

        if (chunk.rawData.length < 65) {
            LOGGER.warn("Chunk {} has invalid size: {} bytes (expected at least 65)",
                    chunk.chunkCoord, chunk.rawData.length);
            return true;
        }

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(chunk.rawData));
            int coord = in.readByte(); // Skip chunkCoord

            // Check 16 tiles
            int emptyTiles = 0;
            for (int tileIdx = 0; tileIdx < 16; tileIdx++) {
                int tileMarker = in.readInt();
                if (tileMarker == -1) {
                    emptyTiles++;
                }
            }

            LOGGER.debug("Chunk {} has {} empty tiles out of 16", coord, emptyTiles);
            return emptyTiles == 16; // All tiles are -1 = empty
        } catch (IOException e) {
            LOGGER.warn("Error checking chunk emptiness for coord {}", chunk.chunkCoord, e);
            return true;
        }
    }

    /**
     * 将服务端区域数据合并到现有客户端文件中。
     * 保留客户端有数据的区块，仅从服务端添加缺失的区块。
     * 如果服务端也没有某个区块，则保持为空。
     *
     * @param clientFile 现有客户端区域文件
     * @param serverData 服务端原始 zip 数据（完整区域）
     * @param outputFile 合并结果的写入位置（可以与 clientFile 相同）
     * @return 包含统计信息的合并结果
     * @throws IOException 如果读写文件失败
     */
    public static MergeResult mergeRegionData(Path clientFile, byte[] serverData, Path outputFile) throws IOException {
        LOGGER.info("Starting merge: client={}, serverData size={} bytes, output={}",
                clientFile, serverData.length, outputFile);

        // Parse existing client chunks
        Map<Integer, ChunkData> clientChunks = parseRegionFile(clientFile);
        LOGGER.info("Parsed client file: {} chunks found", clientChunks.size());

        // Parse server chunks
        Map<Integer, ChunkData> serverChunks = parseRegionData(serverData);
        LOGGER.info("Parsed server data: {} chunks found", serverChunks.size());

        // Merge strategy:
        // 1. Keep client chunks that have data (don't replace)
        // 2. Add server chunks for positions where client is empty/missing
        // 3. If server also empty, skip (keep client's empty state)

        int clientWithData = 0;
        int clientEmptySkipped = 0;
        int addedFromServer = 0;
        int serverEmptySkipped = 0;
        int totalWritten = 0;

        Map<Integer, ChunkData> mergedChunks = new LinkedHashMap<>();

        for (int coord = 0; coord < 64; coord++) {
            ChunkData clientChunk = clientChunks.get(coord);
            ChunkData serverChunk = serverChunks.get(coord);

            boolean clientHasData = clientChunk != null && !isChunkEmpty(clientChunk);
            boolean serverHasData = serverChunk != null && !isChunkEmpty(serverChunk);

            if (clientHasData) {
                // Keep client's data (don't replace)
                mergedChunks.put(coord, clientChunk);
                clientWithData++;
                totalWritten++;
            } else if (serverHasData) {
                // Client missing/empty, server has data -> add from server
                mergedChunks.put(coord, serverChunk);
                addedFromServer++;
                totalWritten++;
                LOGGER.debug("Adding chunk {} from server (client empty/missing, server has data)", coord);
            } else {
                // Both empty/missing -> skip (don't add placeholder)
                if (clientChunk != null) {
                    clientEmptySkipped++;
                }
                if (serverChunk != null) {
                    serverEmptySkipped++;
                }
            }
        }

        // Write merged file
        writeRegionFile(outputFile, mergedChunks);

        LOGGER.info("Merge complete: {} client chunks kept, {} added from server, {} skipped (client empty), {} skipped (server empty), {} total written",
                clientWithData, addedFromServer, clientEmptySkipped, serverEmptySkipped, totalWritten);

        return new MergeResult(clientWithData, serverChunks.size(), addedFromServer, true);
    }

    /**
     * 将服务端区域数据与现有客户端文件合并。
     * 仅添加客户端文件中不存在的区块。
     *
     * @param clientFile 现有客户端区域文件（可能不存在）
     * @param serverData 服务端原始 zip 数据
     * @param outputFile 合并结果的写入位置
     * @return 包含统计信息的合并结果
     * @throws IOException 如果读写文件失败
     */
    public static MergeResult mergeRegion(Path clientFile, byte[] serverData, Path outputFile) throws IOException {
        // Parse existing client chunks
        Map<Integer, ChunkData> clientChunks = parseRegionFile(clientFile);

        // Parse server chunks
        Map<Integer, ChunkData> serverChunks = parseRegionData(serverData);

        // Merge: prefer client data for existing chunks, add new server chunks
        int newChunks = 0;
        Map<Integer, ChunkData> mergedChunks = new LinkedHashMap<>();

        // Add all client chunks first
        mergedChunks.putAll(clientChunks);

        // Add server chunks that don't exist in client
        for (Map.Entry<Integer, ChunkData> entry : serverChunks.entrySet()) {
            if (!mergedChunks.containsKey(entry.getKey())) {
                mergedChunks.put(entry.getKey(), entry.getValue());
                newChunks++;
            }
        }

        // Determine if we need to write a new file
        boolean fileChanged = newChunks > 0;

        if (fileChanged) {
            writeRegionFile(outputFile, mergedChunks);
            LOGGER.debug("Merged region: {} client chunks + {} server chunks -> {} total, {} new",
                    clientChunks.size(), serverChunks.size(), mergedChunks.size(), newChunks);
        } else {
            LOGGER.debug("No new chunks to merge, skipping write");
        }

        return new MergeResult(clientChunks.size(), serverChunks.size(), newChunks, fileChanged);
    }

    /**
     * 表示区域文件中的原始区块数据。
     */
    private static class ChunkData {
        /** 区块坐标 (o << 4 | p)，范围 0-63 */
        final int chunkCoord;
        /** 原始字节数据，包含 chunkCoord 和所有图块数据 */
        final byte[] rawData;

        /**
         * 构造区块数据。
         *
         * @param chunkCoord 区块坐标（0-63）
         * @param rawData 原始字节数据
         */
        ChunkData(int chunkCoord, byte[] rawData) {
            this.chunkCoord = chunkCoord;
            this.rawData = rawData;
        }
    }

    /**
     * 解析区域 zip 文件并提取区块数据。
     *
     * @param file 区域文件路径，如果不存在返回空 Map
     * @return 区块坐标到区块数据的映射
     * @throws IOException 如果读取文件失败
     */
    private static Map<Integer, ChunkData> parseRegionFile(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        return parseRegionData(Files.readAllBytes(file));
    }

    /**
     * 从原始 zip 字节数据解析区域数据。
     * 使用流式方法跟踪区块边界。
     *
     * @param zipData 原始 zip 字节数据
     * @return 区块坐标到区块数据的映射
     * @throws IOException 如果解析失败
     */
    private static Map<Integer, ChunkData> parseRegionData(byte[] zipData) throws IOException {
        Map<Integer, ChunkData> chunks = new LinkedHashMap<>();

        LOGGER.debug("Parsing region data: {} bytes input", zipData.length);

        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry = zipIn.getNextEntry();
            if (entry == null) {
                LOGGER.warn("Invalid region file: no zip entry found");
                return chunks;
            }
            if (!entry.getName().equals("region.xaero")) {
                LOGGER.warn("Invalid region file: entry name is '{}', expected 'region.xaero'", entry.getName());
                return chunks;
            }

            // Read entire region.xaero content into byte array for easier processing
            ByteArrayOutputStream regionContent = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = zipIn.read(buffer)) != -1) {
                regionContent.write(buffer, 0, bytesRead);
            }
            zipIn.closeEntry();

            byte[] data = regionContent.toByteArray();
            LOGGER.debug("region.xaero content: {} bytes", data.length);

            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

            // Check and skip version header if present
            int firstByte = in.read();
            if (firstByte == VERSION_MARKER) {
                int version = in.readInt();
                LOGGER.debug("Found version header: 0xFF + version {}", version);
                firstByte = in.read();
            } else {
                LOGGER.debug("No version header found, first byte = {}", firstByte);
            }

            // Parse chunks
            int chunkCount = 0;
            while (firstByte != -1 && firstByte != 0xFF) {
                int chunkCoord = firstByte;

                if (chunkCoord < 0 || chunkCoord > 63) {
                    LOGGER.warn("Invalid chunkCoord: {} (expected 0-63)", chunkCoord);
                    break;
                }

                // Read chunk data by parsing through tiles
                ByteArrayOutputStream chunkBuffer = new ByteArrayOutputStream();
                chunkBuffer.write(chunkCoord);

                // Each chunk has 16 tiles (4x4)
                for (int tileIdx = 0; tileIdx < 16; tileIdx++) {
                    // Read tile marker (first int of tile, or -1 if empty)
                    int tileMarker = in.readInt();
                    chunkBuffer.write((tileMarker >> 24) & 0xFF);
                    chunkBuffer.write((tileMarker >> 16) & 0xFF);
                    chunkBuffer.write((tileMarker >> 8) & 0xFF);
                    chunkBuffer.write(tileMarker & 0xFF);

                    if (tileMarker != -1) {
                        // Tile exists - we need to read the pixel data
                        readTileData(in, chunkBuffer, tileMarker);
                    }
                }

                byte[] chunkData = chunkBuffer.toByteArray();
                chunks.put(chunkCoord, new ChunkData(chunkCoord, chunkData));
                chunkCount++;

                LOGGER.debug("Parsed chunk {}: {} bytes", chunkCoord, chunkData.length);

                firstByte = in.read();
            }

            LOGGER.info("Parsed {} chunks from region file", chunkCount);
        } catch (Exception e) {
            LOGGER.error("Error parsing region data", e);
            throw new IOException("Failed to parse region data: " + e.getMessage(), e);
        }

        return chunks;
    }

    /**
     * 根据格式标志读取图块像素数据。
     * tileMarker 包含决定后续数据量的标志。
     *
     * @param in 数据输入流
     * @param out 数据输出流（用于复制数据）
     * @param tileMarker 图块标记（包含格式标志）
     * @throws IOException 如果读取失败
     */
    private static void readTileData(DataInputStream in, ByteArrayOutputStream out, int tileMarker) throws IOException {
        // Each tile has 16x16 = 256 pixels
        // Pixel format depends on flags in the first int (tileMarker for first pixel)
        // Subsequent pixels read their own int

        // For simplicity, we read pixels by tracking the format
        // A pixel is: int parametres + variable data based on parametres flags

        int currentParametres = tileMarker;
        for (int pixelIdx = 0; pixelIdx < 256; pixelIdx++) {
            // Read pixel based on parametres flags
            // Flags determine what data follows:
            // bit 0: has blockstate
            // bit 6: has height byte
            // other bits for biome, light, etc.

            readPixelData(in, out, currentParametres);

            // For next pixel, read new parametres unless it's the first pixel (uses tileMarker)
            if (pixelIdx < 255) {
                currentParametres = in.readInt();
                out.write((currentParametres >> 24) & 0xFF);
                out.write((currentParametres >> 16) & 0xFF);
                out.write((currentParametres >> 8) & 0xFF);
                out.write(currentParametres & 0xFF);
            }
        }

        // Read tile metadata (version 4+)
        int worldInterpVer = in.read();
        out.write(worldInterpVer);

        // Read cave data (version 6+)
        int caveStart = in.readInt();
        out.write((caveStart >> 24) & 0xFF);
        out.write((caveStart >> 16) & 0xFF);
        out.write((caveStart >> 8) & 0xFF);
        out.write(caveStart & 0xFF);

        int caveDepth = in.read();
        out.write(caveDepth);
    }

    /**
     * 根据参数标志读取像素数据。
     * 格式来自 Xaero 的 loadPixel 方法。
     *
     * @param in 数据输入流
     * @param out 数据输出流
     * @param parametres 参数标志
     * @throws IOException 如果读取失败
     */
    private static void readPixelData(DataInputStream in, ByteArrayOutputStream out, int parametres) throws IOException {
        // bit 0: has blockstate (int for old format, or palette index/NBT)
        if ((parametres & 1) != 0) {
            // bit 9 (0x200000): new palette entry (NBT follows)
            // otherwise: palette index (int)
            if ((parametres & 0x200000) != 0) {
                // NBT data - read until we find the end
                readNBTData(in, out);
            } else {
                // Palette index
                int paletteIdx = in.readInt();
                out.write((paletteIdx >> 24) & 0xFF);
                out.write((paletteIdx >> 16) & 0xFF);
                out.write((paletteIdx >> 8) & 0xFF);
                out.write(paletteIdx & 0xFF);
            }
        }

        // bit 6: has height as single byte
        if ((parametres & 0x40) != 0) {
            int height = in.read();
            out.write(height);
        }

        // bit 1: has topHeight (int)
        if ((parametres & 2) != 0) {
            int topHeight = in.readInt();
            out.write((topHeight >> 24) & 0xFF);
            out.write((topHeight >> 16) & 0xFF);
            out.write((topHeight >> 8) & 0xFF);
            out.write(topHeight & 0xFF);
        }

        // bit 2: has foliageColor (int)
        if ((parametres & 4) != 0) {
            int foliageColor = in.readInt();
            out.write((foliageColor >> 24) & 0xFF);
            out.write((foliageColor >> 16) & 0xFF);
            out.write((foliageColor >> 8) & 0xFF);
            out.write(foliageColor & 0xFF);
        }

        // bit 3: has grassColor (int)
        if ((parametres & 8) != 0) {
            int grassColor = in.readInt();
            out.write((grassColor >> 24) & 0xFF);
            out.write((grassColor >> 16) & 0xFF);
            out.write((grassColor >> 8) & 0xFF);
            out.write(grassColor & 0xFF);
        }

        // bit 4: has waterColor (int)
        if ((parametres & 16) != 0) {
            int waterColor = in.readInt();
            out.write((waterColor >> 24) & 0xFF);
            out.write((waterColor >> 16) & 0xFF);
            out.write((waterColor >> 8) & 0xFF);
            out.write(waterColor & 0xFF);
        }

        // bit 5: has biomeInt (int)
        if ((parametres & 32) != 0) {
            int biomeInt = in.readInt();
            out.write((biomeInt >> 24) & 0xFF);
            out.write((biomeInt >> 16) & 0xFF);
            out.write((biomeInt >> 8) & 0xFF);
            out.write(biomeInt & 0xFF);
        }

        // bit 7: has light (byte)
        if ((parametres & 0x80) != 0) {
            int light = in.read();
            out.write(light);
        }

        // bit 8: has randomOffset (byte)
        if ((parametres & 0x100) != 0) {
            int randomOffset = in.read();
            out.write(randomOffset);
        }
    }

    /**
     * 从流中读取 NBT 复合标签数据。
     * NBT 格式：字节类型 + 字符串名称 + 内容，以 TAG_End (0) 结束。
     *
     * @param in 数据输入流
     * @param out 数据输出流
     * @throws IOException 如果读取失败
     */
    private static void readNBTData(DataInputStream in, ByteArrayOutputStream out) throws IOException {
        // Read NBT compound tag
        byte tagType = in.readByte();
        out.write(tagType);

        if (tagType == 0) {
            // TAG_End - nothing more
            return;
        }

        // Read name length and name
        short nameLength = in.readShort();
        out.write((nameLength >> 8) & 0xFF);
        out.write(nameLength & 0xFF);
        for (int i = 0; i < nameLength; i++) {
            byte nameChar = in.readByte();
            out.write(nameChar);
        }

        // Recursively read NBT content
        readNBTContent(in, out, tagType);
    }

    /**
     * 根据标签类型读取 NBT 内容。
     *
     * @param in 数据输入流
     * @param out 数据输出流
     * @param tagType NBT 标签类型
     * @throws IOException 如果读取失败
     */
    private static void readNBTContent(DataInputStream in, ByteArrayOutputStream out, byte tagType) throws IOException {
        switch (tagType) {
            case 1: // TAG_Byte
                out.write(in.readByte());
                break;
            case 2: // TAG_Short
                short s = in.readShort();
                out.write((s >> 8) & 0xFF);
                out.write(s & 0xFF);
                break;
            case 3: // TAG_Int
                int i = in.readInt();
                out.write((i >> 24) & 0xFF);
                out.write((i >> 16) & 0xFF);
                out.write((i >> 8) & 0xFF);
                out.write(i & 0xFF);
                break;
            case 4: // TAG_Long
                long l = in.readLong();
                for (int b = 7; b >= 0; b--) {
                    out.write((int)((l >> (b * 8)) & 0xFF));
                }
                break;
            case 5: // TAG_Float
                float f = in.readFloat();
                int fi = Float.floatToIntBits(f);
                out.write((fi >> 24) & 0xFF);
                out.write((fi >> 16) & 0xFF);
                out.write((fi >> 8) & 0xFF);
                out.write(fi & 0xFF);
                break;
            case 6: // TAG_Double
                double d = in.readDouble();
                long dl = Double.doubleToLongBits(d);
                for (int b = 7; b >= 0; b--) {
                    out.write((int)((dl >> (b * 8)) & 0xFF));
                }
                break;
            case 7: // TAG_Byte_Array
                int arrayLen = in.readInt();
                out.write((arrayLen >> 24) & 0xFF);
                out.write((arrayLen >> 16) & 0xFF);
                out.write((arrayLen >> 8) & 0xFF);
                out.write(arrayLen & 0xFF);
                for (int j = 0; j < arrayLen; j++) {
                    out.write(in.readByte());
                }
                break;
            case 8: // TAG_String
                short strLen = in.readShort();
                out.write((strLen >> 8) & 0xFF);
                out.write(strLen & 0xFF);
                for (int j = 0; j < strLen; j++) {
                    out.write(in.readByte());
                }
                break;
            case 9: // TAG_List
                byte listType = in.readByte();
                out.write(listType);
                int listLen = in.readInt();
                out.write((listLen >> 24) & 0xFF);
                out.write((listLen >> 16) & 0xFF);
                out.write((listLen >> 8) & 0xFF);
                out.write(listLen & 0xFF);
                for (int j = 0; j < listLen; j++) {
                    readNBTContent(in, out, listType);
                }
                break;
            case 10: // TAG_Compound
                // Read nested tags until TAG_End
                byte nestedType;
                while ((nestedType = in.readByte()) != 0) {
                    out.write(nestedType);
                    short nestedNameLen = in.readShort();
                    out.write((nestedNameLen >> 8) & 0xFF);
                    out.write(nestedNameLen & 0xFF);
                    for (int j = 0; j < nestedNameLen; j++) {
                        out.write(in.readByte());
                    }
                    readNBTContent(in, out, nestedType);
                }
                out.write(0); // TAG_End
                break;
            case 11: // TAG_Int_Array
                int intArrayLen = in.readInt();
                out.write((intArrayLen >> 24) & 0xFF);
                out.write((intArrayLen >> 16) & 0xFF);
                out.write((intArrayLen >> 8) & 0xFF);
                out.write(intArrayLen & 0xFF);
                for (int j = 0; j < intArrayLen; j++) {
                    int intVal = in.readInt();
                    out.write((intVal >> 24) & 0xFF);
                    out.write((intVal >> 16) & 0xFF);
                    out.write((intVal >> 8) & 0xFF);
                    out.write(intVal & 0xFF);
                }
                break;
            case 12: // TAG_Long_Array
                int longArrayLen = in.readInt();
                out.write((longArrayLen >> 24) & 0xFF);
                out.write((longArrayLen >> 16) & 0xFF);
                out.write((longArrayLen >> 8) & 0xFF);
                out.write(longArrayLen & 0xFF);
                for (int j = 0; j < longArrayLen; j++) {
                    long longVal = in.readLong();
                    for (int b = 7; b >= 0; b--) {
                        out.write((int)((longVal >> (b * 8)) & 0xFF));
                    }
                }
                break;
            default:
                LOGGER.warn("Unknown NBT tag type: {}", tagType);
                break;
        }
    }

    /**
     * 将合并的区块写入新的区域文件。
     *
     * @param outputFile 输出文件路径
     * @param chunks 合并的区块数据映射
     * @throws IOException 如果写入失败
     */
    private static void writeRegionFile(Path outputFile, Map<Integer, ChunkData> chunks) throws IOException {
        Files.createDirectories(outputFile.getParent());

        Path tempFile = outputFile.resolveSibling(outputFile.getFileName() + ".temp");

        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(tempFile))) {
            ZipEntry entry = new ZipEntry("region.xaero");
            zipOut.putNextEntry(entry);

            DataOutputStream out = new DataOutputStream(zipOut);

            // Write version header
            out.write(VERSION_MARKER);
            out.writeInt(FULL_VERSION);

            // Write chunks in sorted order (0-63)
            List<Integer> sortedCoords = new ArrayList<>(chunks.keySet());
            Collections.sort(sortedCoords);

            for (int coord : sortedCoords) {
                ChunkData chunk = chunks.get(coord);
                out.write(chunk.rawData);
            }

            // Write end marker
            out.write(-1);

            zipOut.closeEntry();
        }

        Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
    }
}