# Fabric 1.21.1 开发指南总览

## 开发环境要求

### 必需工具

- **Java 21**: Minecraft 1.21.1 需要 Java 21
- **Gradle 8.14+**: 推荐使用 Gradle Wrapper
- **Fabric Loom 1.16+**: Gradle 插件，用于开发 Fabric Mod

### 推荐 IDE

- IntelliJ IDEA（推荐，有官方支持）
- Eclipse
- VS Code（需要额外配置）

## 工具链概述

### Fabric Loom

Fabric Loom 是 Fabric 生态系统的 Gradle 插件，提供：

- 下载和安装 Minecraft 及 Mod 到开发环境
- 处理 Minecraft 的混淆映射
- 支持 Mixin 编译处理
- Jar-in-jar 系统支持
- 客户端/服务端代码分离（splitEnvironmentSourceSets）

**重要**: 对于 Minecraft 1.21.1（混淆版本），需要使用 `fabric-loom-remap` 插件，而不是旧的 `fabric-loom`。

```gradle
plugins {
    id 'net.fabricmc.fabric-loom-remap' version '1.16-SNAPSHOT'
}
```

### Fabric Loader

Fabric Loader 是轻量级的 Mod 加载器，提供：

- 基于入口点（Entrypoint）的初始化系统
- Mixin 支持（类转换）
- 依赖管理和冲突检测
- 嵌套 JAR 支持

### Fabric API

Fabric API 是官方的 API 模块集合，包括：

- 网络 API（networking-api-v1）
- 事件系统（lifecycle-events, server-tick-events 等）
- 命令 API（command-api-v2）
- 其他游戏功能 API

## 核心概念

### 1. 入口点（Entrypoints）

Fabric Loader 使用入口点系统来加载 Mod 代码：

```json
{
  "entrypoints": {
    "main": ["com.example.MyMod"],
    "client": ["com.example.MyModClient"]
  }
}
```

- **main**: 实现 `ModInitializer`，在所有环境加载
- **client**: 实现 `ClientModInitializer`，仅在客户端加载
- **server**: 实现 `DedicatedServerModInitializer`，仅在服务端加载

### 2. 环境分离

Minecraft 分为两个逻辑环境：

- **客户端（Client）**: 处理渲染、UI、输入等
- **服务端（Server）**: 处理游戏逻辑、世界状态等

**重要**: 即使在单人游戏，也运行着一个集成服务端。代码必须考虑两端的兼容性。

### 3. Mixin

Mixin 允许在运行时修改 Minecraft 类：

- 注入代码到现有方法
- 修改方法行为
- 访问私有字段和方法

### 4. 依赖管理

在 `fabric.mod.json` 中声明依赖：

```json
{
  "depends": {
    "fabricloader": ">=0.19.2",
    "minecraft": ">=1.21.1 <1.21.2",
    "java": ">=21",
    "fabric-api": "*"
  }
}
```

## 开发工作流

### 1. 项目设置

1. 克隆或创建项目
2. 配置 `build.gradle` 和 `gradle.properties`
3. 运行 `./gradlew genSources` 生成源码
4. 导入到 IDE

### 2. 开发和测试

1. 编写代码
2. 使用 `./gradlew runClient` 启动客户端
3. 使用 `./gradlew runServer` 启动服务端
4. 运行 `./gradlew build` 构建 JAR

### 3. 调试

- 使用 IDE 的调试器
- 查看 `logs/latest.log` 日志
- 使用 Mixin 调试工具

## 常见问题

### 缓存问题

如果遇到构建失败，尝试：

```bash
./gradlew build --refresh-dependencies
```

### 依赖冲突

使用 `./gradlew dependencies` 查看依赖树，解决版本冲突。

### 编译错误

确保：
1. Java 版本正确（21）
2. Loom 版本匹配 Minecraft 版本
3. Mappings 配置正确

## 下一步

- 阅读 [fabric.mod.json 规范](fabric-mod-json-spec.md) 了解详细配置
- 查看 [Loom 构建配置](loom-configuration.md) 了解构建系统
- 参考 [代码分离最佳实践](code-separation.md) 了解客户端/服务端代码组织
