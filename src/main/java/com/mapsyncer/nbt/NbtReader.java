package com.mapsyncer.nbt;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NBT读取器 - 零依赖实现
 * 解析Minecraft NBT格式二进制数据
 *
 * 所有数据采用大端序(Big-Endian)
 */
public class NbtReader implements AutoCloseable {

    private final DataInputStream in;

    public NbtReader(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /**
     * 读取完整的NBT文档（根Compound）
     */
    public Tag.Compound readDocument() throws IOException {
        byte type = in.readByte();
        if (type != Tag.TAG_COMPOUND) {
            throw new IOException("NBT文档必须以Compound开头，实际类型: " + type);
        }
        String name = in.readUTF();
        return readCompoundContent(name);
    }

    /**
     * 读取单个Tag（包含类型和名称）
     */
    public Tag readTag() throws IOException {
        byte type = in.readByte();
        if (type == Tag.TAG_END) {
            return new Tag.End();
        }
        String name = in.readUTF();
        return readPayload(type, name);
    }

    /**
     * 读取Tag内容（不含类型和名称前缀）
     */
    private Tag readPayload(byte type, String name) throws IOException {
        switch (type) {
            case Tag.TAG_END:
                return new Tag.End();
            case Tag.TAG_BYTE:
                return new Tag.Byte(name, in.readByte());
            case Tag.TAG_SHORT:
                return new Tag.Short(name, in.readShort());
            case Tag.TAG_INT:
                return new Tag.Int(name, in.readInt());
            case Tag.TAG_LONG:
                return new Tag.Long(name, in.readLong());
            case Tag.TAG_FLOAT:
                return new Tag.Float(name, in.readFloat());
            case Tag.TAG_DOUBLE:
                return new Tag.Double(name, in.readDouble());
            case Tag.TAG_BYTE_ARRAY:
                return readByteArray(name);
            case Tag.TAG_STRING:
                return new Tag.StringTag(name, in.readUTF());
            case Tag.TAG_LIST:
                return readListContent(name);
            case Tag.TAG_COMPOUND:
                return readCompoundContent(name);
            case Tag.TAG_INT_ARRAY:
                return readIntArray(name);
            case Tag.TAG_LONG_ARRAY:
                return readLongArray(name);
            default:
                throw new IOException("未知NBT类型: " + type);
        }
    }

    /**
     * 读取ByteArray
     */
    private Tag.ByteArray readByteArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("ByteArray长度不能为负: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new Tag.ByteArray(name, data);
    }

    /**
     * 读取IntArray
     */
    private Tag.IntArray readIntArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("IntArray长度不能为负: " + length);
        }
        int[] data = new int[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readInt();
        }
        return new Tag.IntArray(name, data);
    }

    /**
     * 读取LongArray
     */
    private Tag.LongArray readLongArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("LongArray长度不能为负: " + length);
        }
        long[] data = new long[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readLong();
        }
        return new Tag.LongArray(name, data);
    }

    /**
     * 读取List内容（已读取类型和名称）
     */
    private Tag.ListTag readListContent(String name) throws IOException {
        byte elementType = in.readByte();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("List长度不能为负: " + length);
        }
        List<Tag> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            // List元素没有名称，传入空字符串
            items.add(readPayload(elementType, ""));
        }
        return new Tag.ListTag(name, elementType, items);
    }

    /**
     * 读取Compound内容（已读取类型和名称）
     */
    private Tag.Compound readCompoundContent(String name) throws IOException {
        Map<String, Tag> children = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == Tag.TAG_END) {
                break;
            }
            String childName = in.readUTF();
            children.put(childName, readPayload(type, childName));
        }
        return new Tag.Compound(name, children);
    }

    /**
     * 关闭读取器
     */
    @Override
    public void close() throws IOException {
        in.close();
    }
}