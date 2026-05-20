package com.mapsyncer.nbt;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NBT读取器 - 零依赖实现
 *
 * <p>用于解析Minecraft NBT（Named Binary Tag）格式的二进制数据。
 * 所有数据采用大端序（Big-Endian）存储，符合Minecraft的NBT规范。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * try (NbtReader reader = new NbtReader(inputStream)) {
 *     Tag.Compound root = reader.readDocument();
 *     // 处理NBT数据...
 * }
 * }</pre>
 *
 * @see Tag
 */
public class NbtReader implements AutoCloseable {

    /** 数据输入流，用于读取二进制NBT数据 */
    private final DataInputStream in;

    /**
     * 构造NBT读取器
     *
     * @param in 输入流，包含NBT格式的二进制数据
     */
    public NbtReader(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /**
     * 读取完整的NBT文档（根Compound）
     *
     * <p>读取整个NBT文档，返回根Compound标签。
     * NBT文档必须以Compound类型开头。</p>
     *
     * @return 根Compound标签
     * @throws IOException 如果读取失败或文档格式不正确
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
     *
     * <p>从输入流中读取一个完整的标签，包括类型标识、名称和数据内容。</p>
     *
     * @return 读取的Tag对象
     * @throws IOException 如果读取失败
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
     *
     * <p>根据给定的类型标识读取对应的数据内容。</p>
     *
     * @param type NBT类型标识
     * @param name 标签名称
     * @return 读取的Tag对象
     * @throws IOException 如果读取失败或类型未知
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
     * 读取ByteArray类型标签
     *
     * @param name 标签名称
     * @return ByteArray标签对象
     * @throws IOException 如果读取失败或长度为负数
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
     * 读取IntArray类型标签
     *
     * @param name 标签名称
     * @return IntArray标签对象
     * @throws IOException 如果读取失败或长度为负数
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
     * 读取LongArray类型标签
     *
     * @param name 标签名称
     * @return LongArray标签对象
     * @throws IOException 如果读取失败或长度为负数
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
     * 读取List类型标签内容
     *
     * <p>List中的所有元素必须是相同类型。元素没有名称，使用空字符串作为名称。</p>
     *
     * @param name 标签名称
     * @return ListTag标签对象
     * @throws IOException 如果读取失败或长度为负数
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
     * 读取Compound类型标签内容
     *
     * <p>Compound是一个键值对集合，以TAG_END作为结束标记。
     * 子标签按读取顺序保存。</p>
     *
     * @param name 标签名称
     * @return Compound标签对象
     * @throws IOException 如果读取失败
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
     * 关闭读取器并释放资源
     *
     * @throws IOException 如果关闭时发生I/O错误
     */
    @Override
    public void close() throws IOException {
        in.close();
    }
}