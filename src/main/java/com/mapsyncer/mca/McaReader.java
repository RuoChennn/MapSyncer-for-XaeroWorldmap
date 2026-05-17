package com.mapsyncer.mca;

import com.mapsyncer.nbt.NbtReader;
import com.mapsyncer.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * MCA文件读取器 - 零依赖实现
 * 解析Minecraft区域文件格式(.mca)
 *
 * MCA文件结构:
 * - 0-4KB: 位置表 (32x32 chunk位置，每个4字节)
 * - 4-8KB: 时间戳表 (32x32 chunk时间戳，每个4字节)
 * - 8KB+:  chunk数据扇区 (每扇区4KB)
 */
public class McaReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaReader.class);
    private static final int SECTOR_SIZE = 4096;
    private static final int CHUNKS_PER_REGION = 32;

    // 压缩类型常量
    private static final int COMPRESS_GZIP = 1;
    private static final int COMPRESS_ZLIB = 2;
    private static final int COMPRESS_NONE = 3;
    private static final int COMPRESS_LZ4 = 4;

    /**
     * Chunk位置信息
     */
    public record ChunkLocation(int offsetSectors, int sectorCount, int timestamp) {
        public boolean exists() {
            return offsetSectors > 0 && sectorCount > 0;
        }
        public long dataOffset() {
            return (long) offsetSectors * SECTOR_SIZE;
        }
    }

    /**
     * Chunk数据
     */
    public record ChunkData(int chunkX, int chunkZ, Tag.Compound nbt) {}

    private final RandomAccessFile raf;
    private final String filePath;

    /**
     * 打开MCA文件
     */
    public McaReader(String path) throws IOException {
        this.filePath = path;
        this.raf = new RandomAccessFile(path, "r");
        if (raf.length() < SECTOR_SIZE * 2) {
            throw new IOException("MCA文件太小: " + raf.length() + " bytes");
        }
    }

    /**
     * 获取chunk位置信息
     * @param localX chunk在region内的局部坐标 (0-31)
     * @param localZ chunk在region内的局部坐标 (0-31)
     */
    public ChunkLocation getChunkLocation(int localX, int localZ) throws IOException {
        int index = (localX + localZ * CHUNKS_PER_REGION) * 4;
        raf.seek(index);

        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        int offsetSectors = (b0 << 16) | (b1 << 8) | b2;
        int sectorCount = raf.readUnsignedByte();

        // 读取时间戳
        raf.seek(SECTOR_SIZE + index);
        int timestamp = raf.readInt();

        return new ChunkLocation(offsetSectors, sectorCount, timestamp);
    }

    /**
     * 读取单个chunk的NBT数据
     * @param localX chunk在region内的局部坐标 (0-31)
     * @param localZ chunk在region内的局部坐标 (0-31)
     */
    public Tag.Compound readChunkNbt(int localX, int localZ) throws IOException {
        ChunkLocation loc = getChunkLocation(localX, localZ);
        if (!loc.exists()) {
            return null;
        }

        long dataOffset = loc.dataOffset();
        if (dataOffset + 5 > raf.length()) {
            return null;
        }

        raf.seek(dataOffset);

        // 读取chunk数据长度（包含压缩类型字节）
        int totalLength = raf.readInt();
        if (totalLength <= 1) {
            return null;
        }

        // 读取压缩类型
        int compressionType = raf.readUnsignedByte();

        // 读取压缩数据
        int dataLength = totalLength - 1;
        byte[] compressedData = new byte[dataLength];
        int read = 0;
        while (read < dataLength) {
            int r = raf.read(compressedData, read, dataLength - read);
            if (r == -1) break;
            read += r;
        }
        if (read != dataLength) {
            return null;
        }

        // 解压缩
        byte[] nbtData = decompress(compressedData, compressionType);
        if (nbtData == null) {
            return null;
        }

        // 解析NBT
        try (NbtReader reader = new NbtReader(new ByteArrayInputStream(nbtData))) {
            return reader.readDocument();
        }
    }

    /**
     * 读取所有存在的chunk
     */
    public Iterable<ChunkData> readAllChunks() throws IOException {
        java.util.List<ChunkData> chunks = new java.util.ArrayList<>();

        for (int localX = 0; localX < CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < CHUNKS_PER_REGION; localZ++) {
                try {
                    Tag.Compound nbt = readChunkNbt(localX, localZ);
                    if (nbt != null) {
                        chunks.add(new ChunkData(localX, localZ, nbt));
                    }
                } catch (IOException e) {
                    // 单个chunk失败不中断整体读取
                    LOGGER.warn("读取chunk ({}, {}) 失败: {}", localX, localZ, e.getMessage());
                }
            }
        }

        return chunks;
    }

    /**
     * 解压缩chunk数据
     */
    private byte[] decompress(byte[] data, int compressionType) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        switch (compressionType) {
            case COMPRESS_GZIP:
                try (GZIPInputStream gis = new GZIPInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = gis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                return baos.toByteArray();

            case COMPRESS_ZLIB:
                try (InflaterInputStream iis = new InflaterInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = iis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                return baos.toByteArray();

            case COMPRESS_NONE:
                return data;

            case COMPRESS_LZ4:
                // LZ4压缩需要额外依赖，暂不支持
                throw new IOException("LZ4压缩暂不支持，请使用GZIP或ZLIB压缩的region文件");

            default:
                throw new IOException("未知压缩类型: " + compressionType);
        }
    }

    /**
     * 关闭读取器
     */
    @Override
    public void close() throws IOException {
        raf.close();
    }

    /**
     * 获取文件路径
     */
    public String getFilePath() {
        return filePath;
    }
}