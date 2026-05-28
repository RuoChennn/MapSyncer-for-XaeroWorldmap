# Fabric 网络编程规范

## 网络基础

### 客户端-服务端架构

Minecraft 使用客户端-服务端架构：

- **客户端（Client）**: 处理渲染、UI、输入
- **服务端（Server）**: 处理游戏逻辑、世界状态

**重要**: 即使在单人游戏，也运行着一个集成服务端。网络通信始终存在。

### 数据包（Packets）

数据包是客户端和服务端通信的基本单位：

- **客户端 → 服务端**: 玩家操作（移动、使用物品、发送消息）
- **服务端 → 客户端**: 世界更新（方块变化、实体移动、聊天消息）

## Fabric API 网络系统

### 核心概念

1. **Payload**: 数据包的内容（数据）
2. **PayloadType**: Payload 的类型标识
3. **StreamCodec**: 序列化/反序列化器
4. **PayloadTypeRegistry**: 注册 Payload 类型

### 基本流程

```
1. 定义 Payload（数据结构）
2. 注册 PayloadType（在两端）
3. 发送方：创建 Payload → 发送数据包
4. 接收方：注册处理器 → 处理数据包
```

## 定义 Payload

### 使用 Record（推荐）

```java
package com.mapsyncer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MapSyncPayload(
    String dimensionId,
    int regionX,
    int regionZ,
    byte[] data
) implements CustomPacketPayload {

    // Payload 类型标识
    public static final CustomPacketPayload.Type<MapSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.parse("mapsyncer:map_sync"));

    // 序列化/反序列化编解码器
    public static final StreamCodec<FriendlyByteBuf, MapSyncPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.dimensionId());
                buf.writeInt(payload.regionX());
                buf.writeInt(payload.regionZ());
                buf.writeByteArray(payload.data());
            },
            buf -> new MapSyncPayload(
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                buf.readByteArray()
            )
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

### 使用 StreamCodec.ofMember（更简洁）

```java
public static final StreamCodec<FriendlyByteBuf, MapSyncPayload> CODEC =
    StreamCodec.ofMember(
        (payload, buf) -> {
            buf.writeUtf(payload.dimensionId());
            buf.writeInt(payload.regionX());
            buf.writeInt(payload.regionZ());
            buf.writeByteArray(payload.data());
        },
        buf -> new MapSyncPayload(
            buf.readUtf(),
            buf.readInt(),
            buf.readInt(),
            buf.readByteArray()
        )
    );
```

## 注册 Payload

### 在公共初始化代码中注册

```java
// MapSyncer.java (main entrypoint)
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class MapSyncer implements ModInitializer {
    @Override
    public void onInitialize() {
        // 注册客户端 → 服务端 Payload
        PayloadTypeRegistry.playC2S().register(
            MapSyncPayload.TYPE,
            MapSyncPayload.CODEC
        );

        // 注册服务端 → 客户端 Payload
        PayloadTypeRegistry.playS2C().register(
            MapSyncPayload.TYPE,
            MapSyncPayload.CODEC
        );
    }
}
```

**重要**: 必须在**两端**都注册，否则会导致崩溃。

## 发送数据包

### 服务端 → 客户端

```java
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

// 发送给单个玩家
public void sendToPlayer(ServerPlayer player, MapSyncPayload payload) {
    ServerPlayNetworking.send(player, payload);
}

// 发送给所有追踪的玩家
public void sendToTrackingPlayers(ServerPlayer player, MapSyncPayload payload) {
    for (ServerPlayer trackingPlayer : PlayerLookup.tracking(player)) {
        ServerPlayNetworking.send(trackingPlayer, payload);
    }
}

// 发送给所有玩家
public void sendToAllPlayers(MinecraftServer server, MapSyncPayload payload) {
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        ServerPlayNetworking.send(player, payload);
    }
}
```

### 客户端 → 服务端

```java
import net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking;

// 发送给服务端
public void sendToServer(MapSyncPayload payload) {
    ClientPlayNetworking.send(payload);
}
```

## 接收数据包

### 服务端接收（客户端 → 服务端）

```java
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

// 在 MapSyncer.onInitialize() 中注册
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    // 注册接收器
    ServerPlayNetworking.registerReceiver(handler, MapSyncPayload.TYPE, (payload, context) -> {
        // 处理数据包
        ServerPlayer player = context.player();
        MinecraftServer server = context.server();

        // 验证数据
        if (payload.data() == null || payload.data().length > MAX_SIZE) {
            return; // 无效数据
        }

        // 处理逻辑
        handleMapSync(player, payload);
    });
});
```

### 客户端接收（服务端 → 客户端）

```java
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

// 在 MapSyncerClient.onInitializeClient() 中注册
public class MapSyncerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(MapSyncPayload.TYPE, (payload, context) -> {
            // 处理数据包
            // 注意：在客户端线程执行
            context.client().execute(() -> {
                handleMapSync(payload);
            });
        });
    }
}
```

**重要**: 客户端处理器中修改游戏状态必须在客户端线程执行，使用 `context.client().execute()`。

## 数据验证

### 服务端验证（必需）

```java
ServerPlayNetworking.registerGlobalReceiver(MapSyncPayload.TYPE, (payload, context) -> {
    // 1. 验证玩家权限
    if (!context.player().hasPermissions(2)) {
        return;
    }

    // 2. 验证数据长度
    if (payload.data().length > 1024 * 1024) { // 1MB 限制
        return;
    }

    // 3. 验证数据内容
    if (payload.dimensionId() == null || payload.dimensionId().isEmpty()) {
        return;
    }

    // 4. 验证游戏状态
    MinecraftServer server = context.server();
    ResourceKey<Level> dimension = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.parse(payload.dimensionId())
    );
    ServerLevel level = server.getLevel(dimension);
    if (level == null) {
        return; // 无效维度
    }

    // 5. 防止作弊（如果适用）
    if (isAntiCheatEnabled() && !isValidAction(context.player(), payload)) {
        return;
    }

    // 处理数据包
    handleMapSync(context.player(), payload);
});
```

### 验证清单

- ✅ 检查玩家权限
- ✅ 限制数据包大小
- ✅ 验证必填字段
- ✅ 验证游戏状态（维度、坐标等）
- ✅ 防止作弊（如果适用）
- ✅ 记录可疑行为（用于调试）

## 性能优化

### 1. 限制发送频率

```java
private final Map<UUID, Long> lastSendTime = new HashMap<>();

public void sendWithThrottle(ServerPlayer player, MapSyncPayload payload, long minIntervalMs) {
    UUID playerId = player.getUUID();
    long now = System.currentTimeMillis();
    Long lastTime = lastSendTime.get(playerId);

    if (lastTime != null && (now - lastTime) < minIntervalMs) {
        return; // 发送太频繁
    }

    lastSendTime.put(playerId, now);
    ServerPlayNetworking.send(player, payload);
}
```

### 2. 批量处理

```java
// 而不是每个变化发送一个数据包
// ❌ 错误
for (BlockPos pos : changedBlocks) {
    sendBlockChange(player, pos);
}

// ✅ 正确：批量发送
List<BlockPos> batch = changedBlocks.subList(0, Math.min(100, changedBlocks.size()));
sendBlockChanges(player, batch);
```

### 3. 压缩数据

```java
public static byte[] compress(byte[] data) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
        gzip.write(data);
    }
    return baos.toByteArray();
}

public static byte[] decompress(byte[] compressed) throws IOException {
    ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
    try (GZIPInputStream gzip = new GZIPInputStream(bais)) {
        return gzip.readAllBytes();
    }
}
```

### 4. 分块传输

```java
public void sendDataInChunks(ServerPlayer player, byte[] largeData, int chunkSize) {
    int totalChunks = (int) Math.ceil((double) largeData.length / chunkSize);

    for (int i = 0; i < totalChunks; i++) {
        int offset = i * chunkSize;
        int length = Math.min(chunkSize, largeData.length - offset);
        byte[] chunk = Arrays.copyOfRange(largeData, offset, offset + length);

        MapSyncChunkPayload payload = new MapSyncChunkPayload(i, totalChunks, chunk);
        ServerPlayNetworking.send(player, payload);
    }
}
```

## 连接事件

### 监听玩家连接

```java
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

// 玩家加入
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    ServerPlayer player = handler.player();
    LOGGER.info("Player {} joined", player.getName().getString());

    // 发送初始数据
    sendInitialData(player);
});

// 玩家离开
ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
    ServerPlayer player = handler.player();
    LOGGER.info("Player {} left", player.getName().getString());

    // 清理数据
    cleanupPlayerData(player.getUUID());
});
```

### 监听客户端连接

```java
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

// 客户端连接到服务器
ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
    LOGGER.info("Connected to server");

    // 初始化客户端状态
    resetClientState();
});

// 客户端断开连接
ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
    LOGGER.info("Disconnected from server");

    // 清理客户端状态
    clearSyncData();
});
```

## MapSyncer 网络实现示例

### Payload 定义

```java
public record MapSyncPayload(
    String dimensionId,
    int regionX,
    int regionZ,
    byte[] compressedData
) implements CustomPacketPayload {

    public static final Type<MapSyncPayload> TYPE =
        new Type<>(ResourceLocation.parse("mapsyncer:map_sync"));

    public static final StreamCodec<FriendlyByteBuf, MapSyncPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.dimensionId());
                buf.writeInt(p.regionX());
                buf.writeInt(p.regionZ());
                buf.writeByteArray(p.compressedData());
            },
            buf -> new MapSyncPayload(
                buf.readUtf(),
                buf.readInt(),
                buf.readInt(),
                buf.readByteArray()
            )
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

### 注册（公共初始化）

```java
// MapSyncer.java
@Override
public void onInitialize() {
    // 注册双向 Payload
    PayloadTypeRegistry.playC2S().register(MapSyncPayload.TYPE, MapSyncPayload.CODEC);
    PayloadTypeRegistry.playS2C().register(MapSyncPayload.TYPE, MapSyncPayload.CODEC);

    // 注册服务端接收器
    ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
        ServerPlayNetworking.registerReceiver(
            handler,
            MapSyncPayload.TYPE,
            (payload, context) -> handleClientMapSync(context.player(), payload)
        );
    });
}
```

### 客户端接收（客户端初始化）

```java
// MapSyncerClient.java
@Override
public void onInitializeClient() {
    ClientPlayNetworking.registerGlobalReceiver(MapSyncPayload.TYPE, (payload, context) -> {
        context.client().execute(() -> {
            handleServerMapSync(payload);
        });
    });
}
```

## 常见错误

### 1. Payload 未注册

**错误**: `PayloadTypeException: Unknown payload type`

**解决**: 确保在两端都注册了 Payload

### 2. 客户端线程问题

**错误**: 在网络线程修改游戏状态导致崩溃

**解决**: 使用 `context.client().execute()` 切换到客户端线程

### 3. 数据包过大

**错误**: 数据包超过最大大小（默认 1MB）

**解决**: 压缩数据或分块传输

### 4. 服务端验证缺失

**错误**: 客户端发送恶意数据导致服务端崩溃

**解决**: 在服务端验证所有数据

## 最佳实践

1. **始终在服务端验证数据**: 防止作弊和崩溃
2. **使用 Record 定义 Payload**: 简洁且类型安全
3. **批量处理**: 减少数据包数量
4. **压缩大数据**: 减少网络带宽
5. **限制发送频率**: 防止滥用
6. **记录网络事件**: 便于调试
7. **测试两端**: 确保客户端和服务端都正常工作
