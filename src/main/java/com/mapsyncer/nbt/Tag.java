package com.mapsyncer.nbt;

import java.util.List;
import java.util.Map;

/**
 * NBT Tag类型定义 - 零依赖实现
 * 支持所有Minecraft NBT格式中的Tag类型
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
     * NBT Tag类型ID
     */
    byte typeId();

    /**
     * Tag名称（根Compound通常为空字符串）
     */
    String name();

    // ========== Tag类型常量 ==========
    byte TAG_END = 0;
    byte TAG_BYTE = 1;
    byte TAG_SHORT = 2;
    byte TAG_INT = 3;
    byte TAG_LONG = 4;
    byte TAG_FLOAT = 5;
    byte TAG_DOUBLE = 6;
    byte TAG_BYTE_ARRAY = 7;
    byte TAG_STRING = 8;
    byte TAG_LIST = 9;
    byte TAG_COMPOUND = 10;
    byte TAG_INT_ARRAY = 11;
    byte TAG_LONG_ARRAY = 12;

    // ========== 具体Tag实现 ==========

    /** TAG_End - Compound结束标记 */
    record End() implements Tag {
        @Override public byte typeId() { return TAG_END; }
        @Override public String name() { return ""; }
    }

    /** TAG_Byte - 有符号8-bit整数 */
    record Byte(String name, byte value) implements Tag {
        @Override public byte typeId() { return TAG_BYTE; }
    }

    /** TAG_Short - 有符号16-bit整数 */
    record Short(String name, short value) implements Tag {
        @Override public byte typeId() { return TAG_SHORT; }
    }

    /** TAG_Int - 有符号32-bit整数 */
    record Int(String name, int value) implements Tag {
        @Override public byte typeId() { return TAG_INT; }
    }

    /** TAG_Long - 有符号64-bit整数 */
    record Long(String name, long value) implements Tag {
        @Override public byte typeId() { return TAG_LONG; }
    }

    /** TAG_Float - 32-bit IEEE 754浮点数 */
    record Float(String name, float value) implements Tag {
        @Override public byte typeId() { return TAG_FLOAT; }
    }

    /** TAG_Double - 64-bit IEEE 754浮点数 */
    record Double(String name, double value) implements Tag {
        @Override public byte typeId() { return TAG_DOUBLE; }
    }

    /** TAG_Byte_Array - 字节数组 */
    record ByteArray(String name, byte[] value) implements Tag {
        @Override public byte typeId() { return TAG_BYTE_ARRAY; }
    }

    /** TAG_String - UTF-8字符串 */
    record StringTag(String name, String value) implements Tag {
        @Override public byte typeId() { return TAG_STRING; }
    }

    /** TAG_List - 同类型Tag列表 */
    record ListTag(String name, byte elementType, List<Tag> items) implements Tag {
        @Override public byte typeId() { return TAG_LIST; }
    }

    /** TAG_Compound - 键值对集合 */
    record Compound(String name, Map<String, Tag> children) implements Tag {
        @Override public byte typeId() { return TAG_COMPOUND; }

        // 快捷访问方法
        public Tag get(String key) { return children.get(key); }
        public boolean contains(String key) { return children.containsKey(key); }
        public boolean contains(String key, byte typeId) {
            Tag t = children.get(key);
            return t != null && t.typeId() == typeId;
        }
        public byte getByte(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Byte b ? b.value() : 0;
        }
        public short getShort(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Short s ? s.value() : 0;
        }
        public int getInt(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Int i ? i.value() : 0;
        }
        public long getLong(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Long l ? l.value() : 0;
        }
        public String getString(String key) {
            Tag t = children.get(key);
            return t instanceof StringTag s ? s.value() : "";
        }
        public Compound getCompound(String key) {
            Tag t = children.get(key);
            return t instanceof Compound c ? c : new Compound(key, Map.of());
        }
        public ListTag getList(String key, byte expectedType) {
            Tag t = children.get(key);
            return t instanceof ListTag l ? l : new ListTag(key, expectedType, List.of());
        }
        public byte[] getByteArray(String key) {
            Tag t = children.get(key);
            return t instanceof ByteArray ba ? ba.value() : new byte[0];
        }
        public int[] getIntArray(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.IntArray ia ? ia.value() : new int[0];
        }
        public long[] getLongArray(String key) {
            Tag t = children.get(key);
            return t instanceof LongArray la ? la.value() : new long[0];
        }
    }

    /** TAG_Int_Array - Int数组 */
    record IntArray(String name, int[] value) implements Tag {
        @Override public byte typeId() { return TAG_INT_ARRAY; }
    }

    /** TAG_Long_Array - Long数组 */
    record LongArray(String name, long[] value) implements Tag {
        @Override public byte typeId() { return TAG_LONG_ARRAY; }
    }
}