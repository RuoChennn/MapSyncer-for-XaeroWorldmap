# MapSyncer 项目结构说明

本文档说明 MapSyncer 项目在 Fabric 1.21.1 平台的目录结构和代码组织。

## 整体架构

MapSyncer 采用多平台架构，支持 Fabric、Forge、NeoForge：

```
MapSyncer-for-XaeroWorldmap/
├── libs/                    # 核心库（平台无关）
│   ├── core/               # 纯 Java 核心逻辑
│   └── platform-api/       # 平台抽象接口
├── shared/                 # 跨版本共享代码
│   └── common/            # 1.21.x 通用代码
├── platforms/              # 平台特定实现
│   ├── fabric/            # Fabric 平台
│   ├── forge/             # Forge 平台
│   └── neoforge/          # NeoForge 平台
└── docs/                  # 文档
    └── fabric-1.21.1/    # Fabric 1.21.1 规范文档
```

## 代码层次

### 第 1 层：libs/core（纯 Java 核心）

**路径**: `libs/core/`

**内容**: 纯 Java 代码，不依赖 Minecraft API

- MCA 文件解析器
- NBT 解析器
- 数据结构和算法
- 工具类

**特点**:
- 编译为 Java 17 字节码
- 可在任何 Java 环境运行
- 不包含 Minecraft 相关代码

### 第 2 层：libs/platform-api（平台抽象）

**路径**: `libs/platform-api/`

**内容**: 定义平台抽象接口

- `Platform` 接口：平台功能抽象
- `NetworkHandler` 接口：网络通信抽象
- 数据类和枚举

**依赖**: libs/core

### 第 3 层：shared/common（跨版本共享代码）

**路径**: `shared/common/`

**内容**: 1.21.x 版本共享的 Minecraft API 代码

- `com.mapsyncer.client.*`: 客户端逻辑
- `com.mapsyncer.server.*`: 服务端逻辑
- `com.mapsyncer.util.*`: 工具类

**特点**:
- 不独立编译，作为源码仓库
- 各平台通过 `sourceSets` 引用
- 包含 Minecraft API 调用

### 第 4 层：platforms/fabric（Fabric 平台实现）

**路径**: `platforms/fabric/1.21.1/`

**内容**: Fabric 1.21.1 特定实现

- 入口点类（`MapSyncer`, `MapSyncerClient`）
- Fabric API 适配
- 网络实现
- 配置管理

## Fabric 1.21.1 目录结构

```
platforms/fabric/1.21.1/
├── build.gradle              # 构建配置
├── src/
│   ├── main/
│   │   ├── java/           # 公共代码（客户端和服务端）
│   │   │   └── com/
│   │   │       └── mapsyncer/
│   │   │           ├── MapSyncer.java              # 公共入口点
│   │   │           ├── config/
│   │   │           │   └── ModConfig.java          # 配置数据
│   │   │           ├── network/
│   │   │           │   ├── FabricPayloadAdapters.java
│   │   │           │   └── impl/
│   │   │           │       └── FabricNetworkHandler.java
│   │   │           ├── platform/
│   │   │           │   └── impl/
│   │   │           │       └── FabricPlatform.java # 平台实现
│   │   │           └── server/
│   │   │               ├── IncrementalUpdateHandler.java
│   │   │               ├── PlayerJoinHandler.java
│   │   │               └── ServerSyncHandler.java
│   │   └── resources/
│   │       ├── fabric.mod.json      # Mod 元数据
│   │       └── assets/
│   │           └── mapsyncer/
│   │               └── icon.png     # Mod 图标
│   └── client/                      # 客户端专用代码
│       ├── java/
│       │   └── com/
│       │       └── mapsyncer/
│       │           ├── MapSyncerClient.java        # 客户端入口点
│       │           └── client/
│       │               ├── MapPacketReceiver.java
│       │               ├── MapSyncerCommand.java
│       │               └── ConfigScreenFactory.java
│       └── resources/               # 客户端专用资源
└── run/                             # 运行时目录
```

## 代码分离策略

### 公共代码（src/main/java）

**包含**:
- Mod 初始化（`MapSyncer`）
- 网络处理（服务端接收器）
- 配置数据类
- 平台实现
- 服务端逻辑

**规则**:
- ❌ 不能导入 `net.minecraft.client.*`
- ❌ 不能导入 `net.fabricmc.api.client.*`
- ✅ 可以使用环境检查调用客户端代码

### 客户端代码（src/client/java）

**包含**:
- 客户端初始化（`MapSyncerClient`）
- GUI/屏幕
- 渲染代码
- 输入处理
- 客户端网络处理器

**规则**:
- ✅ 可以导入 `net.minecraft.client.*`
- ✅ 可以使用 Fabric API 客户端部分
- ⚠️ 必须在客户端线程执行

### 代码引用关系

```
src/main/java (公共代码)
    ↓ 引用
libs/core, libs/platform-api
    ↓ 实现
Platform, NetworkHandler 接口

src/client/java (客户端代码)
    ↓ 引用
src/main/java (公共类)
    ↓ 使用
net.minecraft.client.*, Fabric API
```

## 构建配置详解

### sourceSets 配置

```gradle
// 公共代码（排除客户端）
sourceSets.main {
    java {
        srcDir '../../../shared/common/src/main/java'
        srcDir 'src/main/java'
        exclude 'com/mapsyncer/client/**'  // 排除客户端代码
    }
    resources {
        srcDir '../../../shared/common/src/main/resources'
        srcDir 'src/main/resources'
    }
}

// 客户端代码分离
loom {
    splitEnvironmentSourceSets()

    mods {
        "mapsyncer" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}

// 客户端代码（只包含客户端相关）
sourceSets.client {
    java {
        srcDir '../../../shared/common/src/main/java'
        srcDir 'src/client/java'
        include 'com/mapsyncer/client/**'  // 只包含客户端代码
    }
    resources {
        srcDir '../../../shared/common/src/main/resources'
        srcDir 'src/client/resources'
    }
}
```

### 依赖配置

```gradle
dependencies {
    // Minecraft 和 Fabric
    minecraft "com.mojang:minecraft:1.21.1"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:0.19.2"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.116.12+1.21.1"

    // 可选依赖
    modImplementation "me.shedaniel.cloth:cloth-config-fabric:15.0.127"

    // 核心库（打包到 JAR）
    implementation project(':libs:core')
    implementation project(':libs:platform-api')
    include project(':libs:core')
    include project(':libs:platform-api')
}
```

## 关键文件说明

### fabric.mod.json

**路径**: `src/main/resources/fabric.mod.json`

**作用**: Mod 元数据，定义入口点、依赖、权限等

**关键配置**:
- `id`: Mod 标识符（`mapsyncer`）
- `version`: 使用占位符 `${version}`
- `entrypoints`: 入口点类
- `depends`: 依赖声明

### MapSyncer.java（公共入口点）

**路径**: `src/main/java/com/mapsyncer/MapSyncer.java`

**作用**: Mod 主类，实现 `ModInitializer`

**职责**:
- 初始化平台和网络管理器
- 注册服务端事件
- 注册命令
- 管理生命周期

### MapSyncerClient.java（客户端入口点）

**路径**: `src/client/java/com/mapsyncer/MapSyncerClient.java`

**作用**: 客户端初始化，实现 `ClientModInitializer`

**职责**:
- 注册客户端网络处理器
- 注册客户端命令
- 处理客户端连接事件

### FabricPlatform.java

**路径**: `src/main/java/com/mapsyncer/platform/impl/FabricPlatform.java`

**作用**: 实现 `Platform` 接口，适配 Fabric API

**职责**:
- 提供平台信息
- 管理方块属性
- 处理配置
- 管理文件路径

**注意**: 使用环境检查保护客户端代码调用

```java
@Override
public Path getClientXaeroWorldMapDir() {
    if (isClientEnvironment()) {
        // 安全调用客户端代码
        return com.mapsyncer.client.XaeroMapIntegrator.getCurrentServerDirectory();
    }
    // 回退方案
    return Path.of(System.getProperty("user.dir")).resolve("xaero/world-map");
}
```

## 代码复用机制

### shared/common 的作用

`shared/common` 是源码仓库，不是独立编译的库：

```gradle
// shared/common/build.gradle
tasks.withType(JavaCompile).configureEach {
    enabled = false  // 禁用编译
}

tasks.named('jar').configure {
    enabled = false  // 禁用打包
}
```

**使用方式**: 各平台通过 `sourceSets` 引用源码

```gradle
// platforms/fabric/1.21.1/build.gradle
sourceSets.main {
    java {
        srcDir '../../../shared/common/src/main/java'
    }
}
```

### 为什么不用依赖？

如果使用依赖：
```gradle
implementation project(':shared:common')
```

**问题**:
1. shared/common 需要独立编译
2. 需要 Minecraft 依赖（但它不直接依赖）
3. 不同平台的 API 差异难以处理

**优势**: 使用 sourceSets 引用
1. 代码直接编译到当前项目
2. 由平台模块提供 Minecraft 依赖
3. 可以针对平台做微调

## 构建流程

### 开发环境

```bash
# 生成源码（反编译 Minecraft）
./gradlew :platforms:fabric:1.21.1:genSources

# 启动客户端
./gradlew :platforms:fabric:1.21.1:runClient

# 启动服务端
./gradlew :platforms:fabric:1.21.1:runServer
```

### 构建 JAR

```bash
# 构建单个平台
./gradlew :platforms:fabric:1.21.1:build

# 构建所有平台
./gradlew buildAll
```

### 输出目录

```
output/
├── mapsyncer-1.0.1-fabric-1.21.1.jar
├── mapsyncer-1.0.1-forge-1.21.1.jar
└── mapsyncer-1.0.1-neoforge-1.21.1.jar
```

## 代码规范

### 命名规范

- **类名**: PascalCase（如 `MapSyncer`, `FabricPlatform`）
- **方法名**: camelCase（如 `getBlockProperties`, `onPlayerJoin`）
- **常量**: UPPER_SNAKE_CASE（如 `MOD_ID`, `LOGGER`）
- **包名**: 小写（如 `com.mapsyncer.server`）

### 包结构

```
com.mapsyncer/
├── MapSyncer.java          # 公共入口点
├── MapSyncerClient.java    # 客户端入口点（在 client sourceSet）
├── config/                 # 配置相关
│   ├── ModConfig.java     # 配置数据（公共）
│   ├── ConfigScreen.java  # 配置界面（客户端）
│   └── DimensionScanConfig.java
├── network/                # 网络相关
│   ├── Payloads.java      # Payload 定义（公共）
│   ├── ServerReceiver.java # 服务端接收器（公共）
│   └── ClientReceiver.java # 客户端接收器（客户端）
├── platform/               # 平台抽象
│   ├── Platform.java      # 接口
│   └── impl/
│       └── FabricPlatform.java # Fabric 实现（公共）
├── server/                 # 服务端逻辑
│   ├── SyncHandler.java
│   └── PlayerHandler.java
├── client/                 # 客户端逻辑
│   ├── XaeroMapIntegrator.java
│   └── RenderHelper.java
└── util/                   # 工具类
    ├── BlockColorMapper.java
    └── DimensionPathMapping.java
```

## 最佳实践

### 1. 遵循代码分离

- ✅ 使用 `splitEnvironmentSourceSets()`
- ✅ 客户端代码放在 `src/client/java`
- ✅ 公共代码不引用客户端类
- ✅ 使用环境检查保护客户端代码

### 2. 使用抽象接口

- ✅ 核心逻辑在 `libs/platform-api` 定义接口
- ✅ 平台实现提供具体实现
- ✅ 避免直接调用平台特定 API

### 3. 代码复用

- ✅ 通用逻辑放在 `shared/common`
- ✅ 平台特定代码放在 `platforms/xxx`
- ✅ 使用 `sourceSets` 引用共享代码

### 4. 文档化

- ✅ 在 `docs/` 中记录设计决策
- ✅ 在代码中添加关键注释
- ✅ 维护 README 和 CHANGELOG

## 常见问题

### Q: 为什么 shared/common 不独立编译？

**A**: 因为它依赖 Minecraft API，但不应该直接依赖 Minecraft。由平台模块提供 Minecraft 依赖更灵活。

### Q: 如何添加新的客户端功能？

**A**: 
1. 如果是通用客户端逻辑，放在 `shared/common/src/main/java/com/mapsyncer/client/`
2. 如果是 Fabric 特定，放在 `platforms/fabric/1.21.1/src/client/java/com/mapsyncer/client/`

### Q: 如何处理平台差异？

**A**: 
1. 在 `libs/platform-api` 定义抽象接口
2. 在各平台的 `platform/impl/` 中实现
3. 通过 `PlatformManager` 获取当前平台实现

### Q: 如何调试网络问题？

**A**: 
1. 启用调试日志：`enableDebugLogging: true`
2. 使用 `LOGGER.debug()` 记录网络事件
3. 检查 `logs/latest.log`

## 相关文档

- [Fabric 1.21.1 开发指南](development-guide.md)
- [fabric.mod.json 规范](fabric-mod-json-spec.md)
- [Loom 构建配置](loom-configuration.md)
- [代码分离最佳实践](code-separation.md)
- [网络编程规范](networking.md)
