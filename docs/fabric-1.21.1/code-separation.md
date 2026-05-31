# 代码分离最佳实践

## 为什么需要代码分离？

Minecraft 分为两个逻辑环境：

- **客户端（Client）**: 处理渲染、UI、输入、声音等
- **服务端（Server）**: 处理游戏逻辑、世界状态、网络等

**常见问题**: Mod 在单人游戏（客户端+集成服务端）工作正常，但在专用服务器上崩溃，因为代码意外调用了客户端类。

## splitEnvironmentSourceSets

### 功能说明

Fabric Loom 提供的 `splitEnvironmentSourceSets()` 功能会在**编译时**检查：

- 公共代码（`src/main/java`）不能引用客户端类
- 防止 `net.minecraft.client.*` 在服务端环境中被调用

### 启用方法

```gradle
loom {
    splitEnvironmentSourceSets()

    mods {
        "modid" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}
```

### 目录结构

```
src/
├── main/
│   ├── java/           # 公共代码（客户端和服务端都能运行）
│   │   └── com/
│   │       └── modid/
│   │           ├── ModInitializer.java
│   │           ├── config/
│   │           ├── server/
│   │           └── util/
│   └── resources/
│       └── fabric.mod.json
├── client/
│   ├── java/           # 客户端专用代码
│   │   └── com/
│   │       └── modid/
│   │           ├── ClientInitializer.java
│   │           ├── client/
│   │           │   ├── RenderHelper.java
│   │           │   ├── ScreenFactory.java
│   │           │   └── InputHandler.java
│   │           └── config/
│   │               └── ClientConfigGUI.java
│   └── resources/      # 客户端专用资源
│       └── assets/
│           └── modid/
│               ├── textures/
│               └── models/
└── server/             # 服务端专用代码（可选）
    ├── java/
    └── resources/
```

## 代码分类指南

### 公共代码（src/main/java）

**可以使用的 API**:
- `net.minecraft.server.*`
- `net.minecraft.world.*`
- `net.minecraft.core.*`
- `net.minecraft.resources.*`
- `net.minecraft.network.*`
- 所有非客户端的 Minecraft API

**典型内容**:
- Mod 初始化类（实现 `ModInitializer`）
- 网络包处理（服务端接收器）
- 世界数据处理
- 配置数据类（不包含 GUI）
- 工具类

**示例**:

```java
// src/main/java/com/mapsyncer/MapSyncer.java
package com.mapsyncer;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;

public class MapSyncer implements ModInitializer {
    @Override
    public void onInitialize() {
        // 初始化公共逻辑
    }

    // 服务端方法 - 可以在公共代码中
    public void onServerStart(MinecraftServer server) {
        // 服务端逻辑
    }
}
```

### 客户端代码（src/client/java）

**必须使用的 API**:
- `net.minecraft.client.*`
- `net.minecraft.client.gui.*`
- `net.minecraft.client.renderer.*`
- `net.minecraft.client.multiplayer.*`
- Fabric API 的客户端部分

**典型内容**:
- 客户端初始化类（实现 `ClientModInitializer`）
- GUI/屏幕
- 渲染代码
- 输入处理
- 音效播放
- 客户端网络处理器

**示例**:

```java
// src/client/java/com/mapsyncer/MapSyncerClient.java
package com.mapsyncer;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public class MapSyncerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 客户端初始化
    }

    // 客户端方法 - 必须在客户端代码中
    public void showScreen() {
        Minecraft.getInstance().setScreen(new MyScreen());
    }
}
```

### 服务端代码（src/server/java）（可选）

**典型内容**:
- 专用服务器初始化（实现 `DedicatedServerModInitializer`）
- 仅服务端的命令
- 服务端专用逻辑

## 代码组织策略

### 策略 1: 按包分离

将客户端代码放在 `client` 包中：

```
com/
└── modid/
    ├── ModInitializer.java      # 公共
    ├── client/                  # 客户端
    │   ├── ClientInitializer.java
    │   └── ScreenFactory.java
    ├── server/                  # 公共（服务端逻辑）
    │   └── ServerHandler.java
    ├── config/                  # 公共
    │   ├── Config.java
    │   └── ConfigScreen.java    # ⚠️ 这个应该在 client 包
    └── util/                    # 公共
        └── Helper.java
```

**优点**: 简单，易于理解

**缺点**: 如果 `config` 包中混合了公共和客户端代码，需要小心处理

### 策略 2: 按功能模块分离

```
com/
└── modid/
    ├── ModInitializer.java
    ├── network/
    │   ├── NetworkHandler.java      # 公共
    │   ├── ServerReceiver.java      # 公共
    │   └── ClientReceiver.java      # 客户端（放在 client sourceSet）
    ├── config/
    │   ├── ConfigData.java          # 公共
    │   └── ConfigGUI.java           # 客户端
    └── feature/
        ├── FeatureLogic.java        # 公共
        └── FeatureRenderer.java     # 客户端
```

**优点**: 功能内聚，便于维护

**缺点**: 需要更仔细地规划目录结构

## 跨环境代码调用

### 问题场景

公共代码需要调用客户端功能：

```java
// ❌ 错误：公共代码直接调用客户端类
public class FabricPlatform implements Platform {
    public Path getClientXaeroWorldMapDir() {
        Minecraft mc = Minecraft.getInstance();  // 服务端会崩溃！
        return mc.gameDirectory.toPath();
    }
}
```

### 解决方案

#### 方案 1: 环境检查

```java
// ✅ 正确：使用环境检查
public class FabricPlatform implements Platform {
    public Path getClientXaeroWorldMapDir() {
        if (isClientEnvironment()) {
            // 安全调用客户端代码
            return ClientHelper.getXaeroWorldMapDir();
        }
        // 回退方案
        return Path.of(System.getProperty("user.dir")).resolve("xaero/world-map");
    }

    private boolean isClientEnvironment() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
            .getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
    }
}
```

#### 方案 2: 使用抽象接口

```java
// src/main/java - 公共接口
public interface WorldMapDirProvider {
    Path getXaeroWorldMapDir();
}

// src/main/java - 默认实现
public class DefaultWorldMapDirProvider implements WorldMapDirProvider {
    @Override
    public Path getXaeroWorldMapDir() {
        return Path.of(System.getProperty("user.dir")).resolve("xaero/world-map");
    }
}

// src/client/java - 客户端实现
public class ClientWorldMapDirProvider implements WorldMapDirProvider {
    @Override
    public Path getXaeroWorldMapDir() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory.toPath().resolve("xaero/world-map");
    }
}
```

#### 方案 3: 使用 Fabric Loader 的入口点

```json
// fabric.mod.json
{
  "entrypoints": {
    "main": ["com.mapsyncer.MapSyncer"],
    "client": ["com.mapsyncer.MapSyncerClient"],
    "mapsyncer:worldmap_provider": ["com.mapsyncer.client.ClientWorldMapDirProvider"]
  }
}
```

```java
// src/main/java - 获取实现
public class WorldMapDirHelper {
    public static Path getXaeroWorldMapDir() {
        List<WorldMapDirProvider> providers = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getEntrypoints("mapsyncer:worldmap_provider", WorldMapDirProvider.class);

        if (!providers.isEmpty()) {
            return providers.get(0).getXaeroWorldMapDir();
        }

        return Path.of(System.getProperty("user.dir")).resolve("xaero/world-map");
    }
}
```

## Mixin 配置

### 分离客户端和服务端 Mixin

```json
// fabric.mod.json
{
  "mixins": [
    "mapsyncer.common.mixins.json",
    {
      "config": "mapsyncer.client.mixins.json",
      "environment": "client"
    }
  ]
}
```

```json
// mapsyncer.client.mixins.json
{
  "required": true,
  "package": "com.mapsyncer.mixin.client",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "ClientLevelMixin",
    "MinecraftMixin"
  ],
  "client": [
    "ClientLevelMixin",
    "MinecraftMixin"
  ]
}
```

## MapSyncer 项目实践

### 当前结构

```
platforms/fabric/1.21.1/src/
├── main/java/
│   └── com/mapsyncer/
│       ├── MapSyncer.java              # 公共入口点
│       ├── config/
│       │   └── ModConfig.java          # 公共（配置数据）
│       ├── network/
│       │   ├── FabricPayloadAdapters.java
│       │   └── impl/
│       │       └── FabricNetworkHandler.java
│       ├── platform/
│       │   └── impl/
│       │       └── FabricPlatform.java # 公共（已移除客户端引用）
│       └── server/
│           ├── IncrementalUpdateHandler.java
│           ├── PlayerJoinHandler.java
│           └── ServerSyncHandler.java
└── client/java/
    └── com/mapsyncer/
        ├── MapSyncerClient.java        # 客户端入口点
        ├── client/
        │   ├── MapPacketReceiver.java
        │   ├── MapSyncerCommand.java
        │   └── ConfigScreenFactory.java # 客户端配置界面
        └── (shared/common 中的客户端代码)
```

### 关键修复

1. **移除客户端类引用**: `FabricPlatform.java` 不再导入 `net.minecraft.client.Minecraft`
2. **分离配置界面**: `createConfigScreen()` 方法移到 `ConfigScreenFactory`
3. **环境检查**: 使用 `isClientEnvironment()` 保护客户端代码调用
4. **代码分离**: 客户端代码在 `src/client/java`，公共代码在 `src/main/java`

## 最佳实践总结

1. **始终启用 splitEnvironmentSourceSets**: 在编译时发现错误
2. **按功能模块组织代码**: 便于维护和理解
3. **使用环境检查保护客户端代码**: 避免服务端崩溃
4. **优先使用抽象接口**: 解耦客户端和服务端实现
5. **分离 Mixin 配置**: 客户端 Mixin 使用 `environment: "client"`
6. **测试两端**: 确保在客户端和服务端都能正常工作
7. **文档化代码位置**: 在 README 中说明目录结构

## 常见错误

### 错误 1: 编译时找不到客户端类

**原因**: 在公共代码中导入了 `net.minecraft.client.*`

**解决**: 将该类移到 `src/client/java`

### 错误 2: 运行时 ClassNotFoundException

**原因**: 客户端类在服务端环境被加载

**解决**: 使用环境检查或抽象接口

### 错误 3: Mixin 在服务端崩溃

**原因**: Mixin 目标是客户端类

**解决**: 在 mixin 配置中添加 `"environment": "client"`
