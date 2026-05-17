package com.mapsyncer.network;

import net.minecraft.network.RegistryFriendlyByteBuf;

public class ChunkMapData {
    public final int regionX;
    public final int regionZ;
    public final String dimension;
    public final byte[] data;

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimension = dimension;
        this.data = data;
    }

    public static void encode(RegistryFriendlyByteBuf buf, ChunkMapData data) {
        buf.writeInt(data.regionX);
        buf.writeInt(data.regionZ);
        buf.writeUtf(data.dimension);
        buf.writeByteArray(data.data);
    }

    public static ChunkMapData decode(RegistryFriendlyByteBuf buf) {
        return new ChunkMapData(
                buf.readInt(),
                buf.readInt(),
                buf.readUtf(),
                buf.readByteArray()
        );
    }
}
