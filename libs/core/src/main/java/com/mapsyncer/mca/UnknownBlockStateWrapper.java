package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 未知方块状态包装器
 *
 * <p>参考 Xaero 的 UnknownBlockState 实现，用于包装无法在注册表中找到的方块，
 * 保存原始 NBT 数据以便后续序列化。</p>
 *
 * <p>主要用途:</p>
 * <ul>
 *   <li>处理模组添加的方块（可能不在原版注册表中）</li>
 *   <li>保存方块的完整NBT数据以进行正确序列化</li>
 *   <li>提供基本的方块属性判断方法</li>
 * </ul>
 *
 * @deprecated 此类为备用功能，暂未使用。
 *             当前转换流程通过 ChunkSectionParser.BlockState record 直接处理方块状态，
 *             模组方块通过名称字符串匹配判断属性。
 *             UnknownBlockStateWrapper 保留用于以下潜在场景：
 *             1. 需要完整保留方块 NBT 数据进行序列化的场景
 *             2. BlockClassifier 字符串匹配不足以判断方块属性时
 *             3. 需要与 Xaero UnknownBlockState 实现保持兼容的场景
 *
 * @see com.mapsyncer.mca.ChunkSectionParser.BlockState 当前使用的方块状态表示
 * @see com.mapsyncer.nbt.Tag.Compound NBT复合标签
 */
@Deprecated(since = "2026-05-24", forRemoval = false)
public class UnknownBlockStateWrapper {

    /**
     * 方块的注册名称（如 "minecraft:stone"）
     */
    private final String blockName;

    /**
     * 方块的属性映射（如 {snowy: "false", facing: "north"}）
     */
    private final Map<String, String> properties;

    /**
     * 原始的NBT复合标签数据
     */
    private final Tag.Compound originalNbt;

    /**
     * 用于toString输出的字符串表示形式
     */
    private final String stringRepresentation;

    /**
     * 从NBT复合标签创建未知方块状态
     *
     * <p>解析NBT数据中的方块名称和属性，生成字符串表示形式</p>
     *
     * @param nbt 包含方块数据的NBT复合标签（必须包含"Name"字段）
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
     * 从方块名称和属性创建未知方块状态
     *
     * <p>适用于已知方块名称和属性但缺少原始NBT数据的情况</p>
     *
     * @param blockName 方块的注册名称（如 "minecraft:stone"）
     * @param properties 方块属性映射（可为空Map）
     */
    public UnknownBlockStateWrapper(String blockName, Map<String, String> properties) {
        this.blockName = blockName;
        this.properties = properties;
        this.originalNbt = null;
        this.stringRepresentation = "Unknown: " + blockName + (properties.isEmpty() ? "" : properties.toString());
    }

    /**
     * 获取方块的注册名称
     *
     * @return 方块名称字符串（如 "minecraft:stone"）
     */
    public String getBlockName() {
        return blockName;
    }

    /**
     * 获取方块的属性映射
     *
     * @return 属性映射表，包含所有方块状态属性
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * 获取原始的NBT数据
     *
     * <p>如果方块是从NBT创建的，返回原始NBT；否则返回null</p>
     *
     * @return 原始NBT复合标签，或null
     */
    public Tag.Compound getOriginalNbt() {
        return originalNbt;
    }

    /**
     * 将方块状态写入到输出流（用于序列化）
     *
     * <p>如果存在原始NBT数据，直接写入原始数据；
     * 否则根据方块名称和属性构造新的NBT结构</p>
     *
     * @param out 数据输出流
     * @throws IOException 如果写入失败
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
     * 将NBT复合标签写入到输出流
     *
     * <p>递归写入所有子标签，包括字符串、整数、字节等基本类型，
     * 以及复合标签、列表、数组等复杂类型</p>
     *
     * @param compound NBT复合标签
     * @param out 数据输出流
     * @throws IOException 如果写入失败
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
     * 将单个NBT标签写入到输出流
     *
     * <p>根据标签类型写入相应的类型标识、名称和值</p>
     *
     * @param name 标签名称
     * @param tag NBT标签对象
     * @param out 数据输出流
     * @throws IOException 如果写入失败
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
     * 将列表元素写入到输出流（无名称）
     *
     * <p>列表元素不包含名称字段，只写入类型标识和值</p>
     *
     * @param tag NBT标签对象
     * @param out 数据输出流
     * @throws IOException 如果写入失败
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

    /**
     * 获取方块状态的字符串表示形式
     *
     * @return 格式为 "Unknown: blockName{properties}" 的字符串
     */
    @Override
    public String toString() {
        return stringRepresentation;
    }

    /**
     * 判断是否为空气方块
     *
     * <p>未知方块默认不是空气</p>
     *
     * @return 始终返回false
     */
    public boolean isAir() {
        return false;
    }

    /**
     * 判断是否为流体方块
     *
     * <p>未知方块默认不是流体</p>
     *
     * @return 始终返回false
     */
    public boolean isFluid() {
        return false;
    }

    /**
     * 判断是否为水方块
     *
     * <p>通过检查方块名称是否包含"water"来判断</p>
     *
     * @return 如果方块名称包含"water"则返回true
     */
    public boolean isWater() {
        return blockName.contains("water");
    }

    /**
     * 判断是否为熔岩方块
     *
     * <p>通过检查方块名称是否包含"lava"来判断</p>
     *
     * @return 如果方块名称包含"lava"则返回true
     */
    public boolean isLava() {
        return blockName.contains("lava");
    }
}