package com.mapsyncer.nbt;

import java.util.List;
import java.util.Map;

/**
 * NBT标签类型定义 - 零依赖实现
 *
 * <p>定义了所有Minecraft NBT（Named Binary Tag）格式中使用的标签类型。
 * 使用Java 17的sealed interface确保类型安全。</p>
 *
 * <p>支持的标签类型：</p>
 * <ul>
 *   <li>{@link End} - 结束标记，用于标记Compound的结束</li>
 *   <li>{@link Byte} - 8位有符号整数</li>
 *   <li>{@link Short} - 16位有符号整数</li>
 *   <li>{@link Int} - 32位有符号整数</li>
 *   <li>{@link Long} - 64位有符号整数</li>
 *   <li>{@link Float} - 32位IEEE 754浮点数</li>
 *   <li>{@link Double} - 64位IEEE 754浮点数</li>
 *   <li>{@link ByteArray} - 字节数组</li>
 *   <li>{@link StringTag} - UTF-8字符串</li>
 *   <li>{@link ListTag} - 同类型标签列表</li>
 *   <li>{@link Compound} - 键值对集合</li>
 *   <li>{@link IntArray} - 整数数组</li>
 *   <li>{@link LongArray} - 长整数数组</li>
 * </ul>
 *
 * @see NbtReader
 */
public sealed interface Tag permits
    Tag.End,
    Tag.Byte,
    Tag.Short,
    Tag.Int,
    Tag.Long,
    Tag.Float,
    Tag.Double,
    Tag.ByteArray,
    Tag.StringTag,
    Tag.ListTag,
    Tag.Compound,
    Tag.IntArray,
    Tag.LongArray {

    /**
     * 获取NBT标签类型ID
     *
     * @return 标签类型ID（0-12）
     */
    byte typeId();

    /**
     * 获取标签名称
     *
     * <p>根Compound通常为空字符串。</p>
     *
     * @return 标签名称
     */
    String name();

    // ========== 标签类型常量 ==========

    /** 结束标记类型ID - 用于标记Compound的结束 */
    byte TAG_END = 0;
    /** 字节类型ID - 8位有符号整数 */
    byte TAG_BYTE = 1;
    /** 短整型类型ID - 16位有符号整数 */
    byte TAG_SHORT = 2;
    /** 整型类型ID - 32位有符号整数 */
    byte TAG_INT = 3;
    /** 长整型类型ID - 64位有符号整数 */
    byte TAG_LONG = 4;
    /** 单精度浮点类型ID - 32位IEEE 754浮点数 */
    byte TAG_FLOAT = 5;
    /** 双精度浮点类型ID - 64位IEEE 754浮点数 */
    byte TAG_DOUBLE = 6;
    /** 字节数组类型ID */
    byte TAG_BYTE_ARRAY = 7;
    /** 字符串类型ID - UTF-8编码 */
    byte TAG_STRING = 8;
    /** 列表类型ID - 同类型元素集合 */
    byte TAG_LIST = 9;
    /** 复合类型ID - 键值对集合 */
    byte TAG_COMPOUND = 10;
    /** 整数数组类型ID */
    byte TAG_INT_ARRAY = 11;
    /** 长整数数组类型ID */
    byte TAG_LONG_ARRAY = 12;

    // ========== 具体Tag实现 ==========

    /**
     * TAG_End - Compound结束标记
     *
     * <p>用于标记Compound标签的结束，不包含实际数据。</p>
     */
    record End() implements Tag {
        @Override public byte typeId() { return TAG_END; }
        @Override public String name() { return ""; }
    }

    /**
     * TAG_Byte - 8位有符号整数
     *
     * @param name  标签名称
     * @param value 字节值（-128到127）
     */
    record Byte(String name, byte value) implements Tag {
        @Override public byte typeId() { return TAG_BYTE; }
    }

    /**
     * TAG_Short - 16位有符号整数
     *
     * @param name  标签名称
     * @param value 短整型值（-32768到32767）
     */
    record Short(String name, short value) implements Tag {
        @Override public byte typeId() { return TAG_SHORT; }
    }

    /**
     * TAG_Int - 32位有符号整数
     *
     * @param name  标签名称
     * @param value 整型值
     */
    record Int(String name, int value) implements Tag {
        @Override public byte typeId() { return TAG_INT; }
    }

    /**
     * TAG_Long - 64位有符号整数
     *
     * @param name  标签名称
     * @param value 长整型值
     */
    record Long(String name, long value) implements Tag {
        @Override public byte typeId() { return TAG_LONG; }
    }

    /**
     * TAG_Float - 32位IEEE 754浮点数
     *
     * @param name  标签名称
     * @param value 单精度浮点值
     */
    record Float(String name, float value) implements Tag {
        @Override public byte typeId() { return TAG_FLOAT; }
    }

    /**
     * TAG_Double - 64位IEEE 754浮点数
     *
     * @param name  标签名称
     * @param value 双精度浮点值
     */
    record Double(String name, double value) implements Tag {
        @Override public byte typeId() { return TAG_DOUBLE; }
    }

    /**
     * TAG_Byte_Array - 字节数组
     *
     * @param name  标签名称
     * @param value 字节数组
     */
    record ByteArray(String name, byte[] value) implements Tag {
        @Override public byte typeId() { return TAG_BYTE_ARRAY; }
    }

    /**
     * TAG_String - UTF-8字符串
     *
     * @param name  标签名称
     * @param value 字符串内容
     */
    record StringTag(String name, String value) implements Tag {
        @Override public byte typeId() { return TAG_STRING; }
    }

    /**
     * TAG_List - 同类型标签列表
     *
     * <p>List中的所有元素必须是相同的类型。元素没有独立的名称。</p>
     *
     * @param name        标签名称
     * @param elementType 列表元素的类型ID
     * @param items       标签元素列表
     */
    record ListTag(String name, byte elementType, List<Tag> items) implements Tag {
        @Override public byte typeId() { return TAG_LIST; }
    }

    /**
     * TAG_Compound - 键值对集合
     *
     * <p>Compound是最常用的NBT类型，类似于Map结构。
     * 每个子标签都有一个唯一的名称作为键。</p>
     *
     * @param name     标签名称
     * @param children 子标签映射，键为标签名称
     */
    record Compound(String name, Map<String, Tag> children) implements Tag {
        @Override public byte typeId() { return TAG_COMPOUND; }

        // ========== 快捷访问方法 ==========

        /**
         * 获取指定键的标签
         *
         * @param key 键名
         * @return 标签对象，不存在则返回null
         */
        public Tag get(String key) { return children.get(key); }

        /**
         * 检查是否包含指定键
         *
         * @param key 键名
         * @return 如果包含则返回true
         */
        public boolean contains(String key) { return children.containsKey(key); }

        /**
         * 检查是否包含指定键且类型匹配
         *
         * @param key     键名
         * @param typeId  期望的类型ID
         * @return 如果包含且类型匹配则返回true
         */
        public boolean contains(String key, byte typeId) {
            Tag t = children.get(key);
            return t != null && t.typeId() == typeId;
        }

        /**
         * 获取Byte值
         *
         * @param key 键名
         * @return Byte值，不存在或类型不匹配则返回0
         */
        public byte getByte(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Byte b ? b.value() : 0;
        }

        /**
         * 获取Short值
         *
         * @param key 键名
         * @return Short值，不存在或类型不匹配则返回0
         */
        public short getShort(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Short s ? s.value() : 0;
        }

        /**
         * 获取Int值
         *
         * @param key 键名
         * @return Int值，不存在或类型不匹配则返回0
         */
        public int getInt(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Int i ? i.value() : 0;
        }

        /**
         * 获取Long值
         *
         * @param key 键名
         * @return Long值，不存在或类型不匹配则返回0
         */
        public long getLong(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Long l ? l.value() : 0;
        }

        /**
         * 获取String值
         *
         * @param key 键名
         * @return String值，不存在或类型不匹配则返回空字符串
         */
        public String getString(String key) {
            Tag t = children.get(key);
            return t instanceof StringTag s ? s.value() : "";
        }

        /**
         * 获取Compound子标签
         *
         * @param key 键名
         * @return Compound对象，不存在或类型不匹配则返回空Compound
         */
        public Compound getCompound(String key) {
            Tag t = children.get(key);
            return t instanceof Compound c ? c : new Compound(key, Map.of());
        }

        /**
         * 获取List子标签
         *
         * @param key          键名
         * @param expectedType 期望的元素类型ID
         * @return ListTag对象，不存在或类型不匹配则返回空ListTag
         */
        public ListTag getList(String key, byte expectedType) {
            Tag t = children.get(key);
            return t instanceof ListTag l ? l : new ListTag(key, expectedType, List.of());
        }

        /**
         * 获取ByteArray值
         *
         * @param key 键名
         * @return 字节数组，不存在或类型不匹配则返回空数组
         */
        public byte[] getByteArray(String key) {
            Tag t = children.get(key);
            return t instanceof ByteArray ba ? ba.value() : new byte[0];
        }

        /**
         * 获取IntArray值
         *
         * @param key 键名
         * @return 整数数组，不存在或类型不匹配则返回空数组
         */
        public int[] getIntArray(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.IntArray ia ? ia.value() : new int[0];
        }

        /**
         * 获取LongArray值
         *
         * @param key 键名
         * @return 长整数数组，不存在或类型不匹配则返回空数组
         */
        public long[] getLongArray(String key) {
            Tag t = children.get(key);
            return t instanceof LongArray la ? la.value() : new long[0];
        }
    }

    /**
     * TAG_Int_Array - 整数数组
     *
     * @param name  标签名称
     * @param value 整数数组
     */
    record IntArray(String name, int[] value) implements Tag {
        @Override public byte typeId() { return TAG_INT_ARRAY; }
    }

    /**
     * TAG_Long_Array - 长整数数组
     *
     * @param name  标签名称
     * @param value 长整数数组
     */
    record LongArray(String name, long[] value) implements Tag {
        @Override public byte typeId() { return TAG_LONG_ARRAY; }
    }
}