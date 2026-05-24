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
 * <p>MCA文件结构:</p>
 * <ul>
 *   <li>0-4KB: 位置表 (32x32 chunk位置，每个4字节)</li>
 *   <li>4-8KB: 时间戳表 (32x32 chunk时间戳，每个4字节)</li>
 *   <li>8KB+:  chunk数据扇区 (每扇区4KB)</li>
 * </ul>
 *
 * <p>支持的压缩类型:</p>
 * <ul>
 *   <li>GZIP (类型1)</li>
 *   <li>ZLIB (类型2)</li>
 *   <li>无压缩 (类型3)</li>
 * </ul>
 *
 * <p>注意：LZ4压缩类型(4)暂不支持，需要额外依赖库</p>
 *
 * @see ChunkDataParser 用于解析chunk的NBT数据
 * @see McaReader.ChunkLocation chunk位置信息记录
 * @see McaReader.ChunkData chunk数据记录
 */
public class McaReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaReader.class);
    /**
     * 扇区大小常量（4KB）
     */
    private static final int SECTOR_SIZE = 4096;

    /**
     * 每个区域的chunk数量（32x32）
     */
    private static final int CHUNKS_PER_REGION = 32;

    // 压缩类型常量
    /**
     * GZIP压缩类型标识
     */
    private static final int COMPRESS_GZIP = 1;

    /**
     * ZLIB压缩类型标识
     */
    private static final int COMPRESS_ZLIB = 2;

    /**
     * 无压缩类型标识
     */
    private static final int COMPRESS_NONE = 3;

    /**
     * LZ4压缩类型标识（暂不支持）
     */
    private static final int COMPRESS_LZ4 = 4;

    /**
     * Chunk位置信息记录
     *
     * <p>包含chunk在MCA文件中的位置和元数据:</p>
     * <ul>
     *   <li>offsetSectors: 数据起始位置的扇区偏移量</li>
     *   <li>sectorCount: 数据占用的扇区数量</li>
     *   <li>timestamp: chunk的最后修改时间戳</li>
     * </ul>
     */
    public record ChunkLocation(int offsetSectors, int sectorCount, int timestamp) {
        /**
         * 判断chunk是否存在
         *
         * @return 如果offsetSectors和sectorCount都大于0则返回true，否则返回false
         */
        public boolean exists() {
            return offsetSectors > 0 && sectorCount > 0;
        }

        /**
         * 计算chunk数据的字节偏移量
         *
         * @return 数据在文件中的绝对字节偏移量（offsetSectors * SECTOR_SIZE）
         */
        public long dataOffset() {
            return (long) offsetSectors * SECTOR_SIZE;
        }
    }

    /**
     * Chunk数据记录
     *
     * @param chunkX chunk在region内的局部X坐标 (0-31)
     * @param chunkZ chunk在region内的局部Z坐标 (0-31)
     * @param nbt chunk的NBT数据（Tag.Compound格式）
     */
    public record ChunkData(int chunkX, int chunkZ, Tag.Compound nbt) {}

    /**
     * 随机访问文件对象，用于读取MCA文件
     */
    private final RandomAccessFile raf;

    /**
     * 打开MCA文件并初始化读取器
     *
     * @param path MCA文件的完整路径
     * @throws IOException 如果文件不存在、文件太小或无法读取
     */
    public McaReader(String path) throws IOException {
        this.raf = new RandomAccessFile(path, "r");
        if (raf.length() < SECTOR_SIZE * 2) {
            throw new IOException("MCA文件太小: " + raf.length() + " bytes");
        }
    }

    /**
     * 获取chunk在MCA文件中的位置信息
     *
     * <p>从位置表和时间戳表读取chunk的元数据</p>
     *
     * @param localX chunk在region内的局部X坐标 (0-31)
     * @param localZ chunk在region内的局部Z坐标 (0-31)
     * @return ChunkLocation对象，包含偏移量、扇区数和时间戳
     * @throws IOException 如果读取文件失败
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
     *
     * <p>处理流程:</p>
     * <ol>
     *   <li>获取chunk位置信息</li>
     *   <li>读取压缩的数据长度和压缩类型</li>
     *   <li>解压缩数据</li>
     *   <li>解析NBT格式</li>
     * </ol>
     *
     * @param localX chunk在region内的局部X坐标 (0-31)
     * @param localZ chunk在region内的局部Z坐标 (0-31)
     * @return chunk的NBT数据（Tag.Compound格式），如果chunk不存在或读取失败则返回null
     * @throws IOException 如果读取或解压缩失败
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
     * 读取区域文件中所有存在的chunk
     *
     * <p>遍历32x32的所有chunk位置，读取每个存在的chunk数据</p>
     * <p>单个chunk读取失败不会中断整体读取过程，会记录警告日志</p>
     *
     * @return 包含所有成功读取的ChunkData对象的列表
     * @throws IOException 如果打开或读取文件失败
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
     *
     * <p>根据压缩类型选择相应的解压缩方法:</p>
     * <ul>
     *   <li>GZIP (1): 使用GZIPInputStream解压</li>
     *   <li>ZLIB (2): 使用InflaterInputStream解压</li>
     *   <li>无压缩 (3): 直接返回原始数据</li>
     *   <li>LZ4 (4): 暂不支持，抛出异常</li>
     * </ul>
     *
     * @param data 压缩的数据字节数组
     * @param compressionType 压缩类型标识 (1-4)
     * @return 解压缩后的NBT数据字节数组
     * @throws IOException 如果解压缩失败或压缩类型不支持
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
     * 关闭MCA文件读取器
     *
     * <p>释放文件资源，实现AutoCloseable接口以支持try-with-resources语法</p>
     *
     * @throws IOException 如果关闭文件失败
     */
    @Override
    public void close() throws IOException {
        raf.close();
    }
}