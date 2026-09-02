package com.mapsyncer.network.payload;

import com.mapsyncer.platform.UpdateMode;
import net.minecraft.network.FriendlyByteBuf;

/**
 * ServerInstalledPayload 的统一网络序列化。
 */
public final class ServerInstalledWireCodec {

    private ServerInstalledWireCodec() {}

    public static void write(FriendlyByteBuf buf, ServerInstalledPayload payload) {
        buf.writeUtf(payload.version());
        buf.writeLong(payload.lastGenerationTimestamp());
        buf.writeInt(payload.autoSyncIntervalMinutes());
        buf.writeByte(payload.updateMode().ordinal());
        buf.writeInt(payload.incrementalUpdateIntervalTicks());
        // serverName: 服务端统一标识，客户端用它命名存档目录（多入口复用同一目录）
        String serverName = payload.serverName();
        buf.writeUtf(serverName != null ? serverName : "");
    }

    public static ServerInstalledPayload read(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        long lastGen = buf.readLong();
        int intervalMinutes = buf.readInt();
        UpdateMode mode = readUpdateMode(buf.readByte());
        int intervalTicks = buf.readInt();
        // 兼容旧协议：旧客户端无 serverName 字段时读不到数据，用空字符串兜底
        String serverName = "";
        try {
            if (buf.readableBytes() > 0) {
                serverName = buf.readUtf();
            }
        } catch (Exception e) {
            // 旧版数据包无此字段，忽略
        }
        return new ServerInstalledPayload(version, lastGen, intervalMinutes, mode, intervalTicks, serverName);
    }

    private static UpdateMode readUpdateMode(int ordinal) {
        UpdateMode[] values = UpdateMode.values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return UpdateMode.DISABLED;
    }
}
