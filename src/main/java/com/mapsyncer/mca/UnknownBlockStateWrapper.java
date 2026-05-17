package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 未知方块状态包装器
 * 参考 Xaero 的 UnknownBlockState 实现
 * 用于包装无法在注册表中找到的方块，保存原始 NBT 数据
 */
public class UnknownBlockStateWrapper {

    private final String blockName;
    private final Map<String, String> properties;
    private final Tag.Compound originalNbt;
    private final String stringRepresentation;

    /**
     * 从 NBT 创建未知方块状态
     */
    public UnknownBlockStateWrapper(Tag.Compound nbt) {
        this.originalNbt = nbt;
        this.blockName = nbt.getString("Name");

        // 解析属性
        Map<String, String> props = new java.util.LinkedHashMap<>();
        if (nbt.contains("Properties", Tag.TAG_COMPOUND)) {
            Tag.Compound propsTag = nbt.getCompound("Properties");
            for (Map.Entry<String, Tag> entry : propsTag.children().entrySet()) {
                Tag propTag = entry.getValue();
                if (propTag instanceof Tag.StringTag str) {
                    props.put(entry.getKey(), str.value());
                }
            }
        }
        this.properties = props;

        this.stringRepresentation = "Unknown: " + blockName + (props.isEmpty() ? "" : props.toString());
    }

    /**
     * 从方块名称和属性创建
     */
    public UnknownBlockStateWrapper(String blockName, Map<String, String> properties) {
        this.blockName = blockName;
        this.properties = properties;
        this.originalNbt = null;
        this.stringRepresentation = "Unknown: " + blockName + (properties.isEmpty() ? "" : properties.toString());
    }

    /**
     * 获取方块名称
     */
    public String getBlockName() {
        return blockName;
    }

    /**
     * 获取方块属性
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * 获取原始 NBT 数据
     */
    public Tag.Compound getOriginalNbt() {
        return originalNbt;
    }

    /**
     * 写入到输出流（用于序列化）
     */
    public void write(DataOutputStream out) throws IOException {
        if (originalNbt != null) {
            // 写入原始 NBT
            writeNbtCompound(originalNbt, out);
        } else {
            // 构造新的 NBT
            out.writeByte(10);  // TAG_Compound
            out.writeShort(0);  // empty name
            out.writeByte(8);   // TAG_String
            out.writeUTF("Name");
            out.writeUTF(blockName);

            if (!properties.isEmpty()) {
                out.writeByte(10);  // TAG_Compound for Properties
                out.writeUTF("Properties");
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    out.writeByte(8);  // TAG_String
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
                out.writeByte(0);  // TAG_End for Properties
            }

            out.writeByte(0);  // TAG_End
        }
    }

    /**
     * 写入 NBT Compound 到输出流
     */
    private void writeNbtCompound(Tag.Compound compound, DataOutputStream out) throws IOException {
        out.writeByte(10);  // TAG_Compound
        out.writeShort(0);  // empty name

        for (Map.Entry<String, Tag> entry : compound.children().entrySet()) {
            Tag tag = entry.getValue();
            writeTag(entry.getKey(), tag, out);
        }

        out.writeByte(0);  // TAG_End
    }

    /**
     * 写入单个 Tag
     */
    private void writeTag(String name, Tag tag, DataOutputStream out) throws IOException {
        if (tag instanceof Tag.StringTag str) {
            out.writeByte(8);
            out.writeUTF(name);
            out.writeUTF(str.value());
        } else if (tag instanceof Tag.Int intTag) {
            out.writeByte(3);
            out.writeUTF(name);
            out.writeInt(intTag.value());
        } else if (tag instanceof Tag.Byte byteTag) {
            out.writeByte(1);
            out.writeUTF(name);
            out.writeByte(byteTag.value());
        } else if (tag instanceof Tag.Short shortTag) {
            out.writeByte(2);
            out.writeUTF(name);
            out.writeShort(shortTag.value());
        } else if (tag instanceof Tag.Long longTag) {
            out.writeByte(4);
            out.writeUTF(name);
            out.writeLong(longTag.value());
        } else if (tag instanceof Tag.Float floatTag) {
            out.writeByte(5);
            out.writeUTF(name);
            out.writeFloat(floatTag.value());
        } else if (tag instanceof Tag.Double doubleTag) {
            out.writeByte(6);
            out.writeUTF(name);
            out.writeDouble(doubleTag.value());
        } else if (tag instanceof Tag.Compound compoundTag) {
            out.writeByte(10);
            out.writeUTF(name);
            writeNbtCompound(compoundTag, out);
        } else if (tag instanceof Tag.LongArray longArray) {
            out.writeByte(12);
            out.writeUTF(name);
            out.writeInt(longArray.value().length);
            for (long l : longArray.value()) {
                out.writeLong(l);
            }
        } else if (tag instanceof Tag.IntArray intArray) {
            out.writeByte(11);
            out.writeUTF(name);
            out.writeInt(intArray.value().length);
            for (int i : intArray.value()) {
                out.writeInt(i);
            }
        } else if (tag instanceof Tag.ByteArray byteArray) {
            out.writeByte(7);
            out.writeUTF(name);
            out.writeInt(byteArray.value().length);
            out.write(byteArray.value());
        } else if (tag instanceof Tag.ListTag list) {
            out.writeByte(9);
            out.writeUTF(name);
            byte elementType = list.elementType();
            out.writeByte(elementType);
            List<Tag> items = list.items();
            out.writeInt(items.size());
            for (Tag item : items) {
                // 写入列表元素（无名称）
                writeListElement(item, out);
            }
        }
    }

    /**
     * 写入列表元素（无名称）
     */
    private void writeListElement(Tag tag, DataOutputStream out) throws IOException {
        if (tag instanceof Tag.StringTag str) {
            out.writeUTF(str.value());
        } else if (tag instanceof Tag.Int intTag) {
            out.writeInt(intTag.value());
        } else if (tag instanceof Tag.Byte byteTag) {
            out.writeByte(byteTag.value());
        } else if (tag instanceof Tag.Short shortTag) {
            out.writeShort(shortTag.value());
        } else if (tag instanceof Tag.Long longTag) {
            out.writeLong(longTag.value());
        } else if (tag instanceof Tag.Float floatTag) {
            out.writeFloat(floatTag.value());
        } else if (tag instanceof Tag.Double doubleTag) {
            out.writeDouble(doubleTag.value());
        } else if (tag instanceof Tag.Compound compoundTag) {
            writeNbtCompound(compoundTag, out);
        } else if (tag instanceof Tag.LongArray longArray) {
            out.writeInt(longArray.value().length);
            for (long l : longArray.value()) {
                out.writeLong(l);
            }
        } else if (tag instanceof Tag.IntArray intArray) {
            out.writeInt(intArray.value().length);
            for (int i : intArray.value()) {
                out.writeInt(i);
            }
        } else if (tag instanceof Tag.ByteArray byteArray) {
            out.writeInt(byteArray.value().length);
            out.write(byteArray.value());
        }
    }

    @Override
    public String toString() {
        return stringRepresentation;
    }

    /**
     * 判断是否为空气（未知方块不是空气）
     */
    public boolean isAir() {
        return false;
    }

    /**
     * 判断是否为流体（未知方块默认不是流体）
     */
    public boolean isFluid() {
        return false;
    }

    /**
     * 判断是否为水（未知方块默认不是水）
     */
    public boolean isWater() {
        return blockName.contains("water");
    }

    /**
     * 判断是否为熔岩（未知方块默认不是熔岩）
     */
    public boolean isLava() {
        return blockName.contains("lava");
    }
}