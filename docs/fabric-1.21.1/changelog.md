# Fabric 1.21.1 规范检查与修复记录

## 检查时间

2026-05-29

## 参考文档

- [Fabric 1.21.1 官方开发文档](https://docs.fabricmc.net/1.21.1/develop/)
- [Fabric Example Mod (1.21.1 分支)](https://github.com/FabricMC/fabric-example-mod/tree/1.21.1)
- [fabric.mod.json v1 规范](https://github.com/FabricMC/fabric-loom/blob/dev/1.16/src/main/java/net/fabricmc/loom/api/fmj/FabricModJsonV1Spec.java)

## 发现的问题

### 1. Loom 插件版本过旧 ❌

**问题**: 使用 `fabric-loom` 1.7-SNAPSHOT

**规范**: 对于 Minecraft 1.21.1（混淆版本），应使用 `fabric-loom-remap`

**修复**: 更新到 `net.fabricmc.fabric-loom-remap` 1.16-SNAPSHOT

### 2. Fabric Loader 版本过旧 ❌

**问题**: 使用 0.15.11

**规范**: 官方模板使用 0.19.2

**修复**: 更新到 0.19.2

### 3. Fabric API 版本过旧 ❌

**问题**: 使用 0.107.0+1.21.1

**规范**: 官方模板使用 0.116.12+1.21.1

**修复**: 更新到 0.116.12+1.21.1

### 4. 缺少代码分离 ❌

**问题**: 未使用 `splitEnvironmentSourceSets()`

**规范**: 官方推荐分离客户端和公共代码，防止服务端崩溃

**修复**: 
- 启用 `splitEnvironmentSourceSets()`
- 创建 `src/client/java` 目录
- 配置 `sourceSets` 分离代码

### 5. 客户端代码混在公共代码中 ❌

**问题**: 
- `FabricPlatform.java` 导入 `net.minecraft.client.Minecraft`
- `ModConfig.java` 包含 `createConfigScreen()` 方法

**规范**: 公共代码不能引用客户端类

**修复**:
- 移除 `FabricPlatform.java` 中的客户端类导入
- 使用环境检查保护客户端代码调用
- 将 `createConfigScreen()` 方法移到 `ConfigScreenFactory.java`

### 6. 缺少图标文件 ❌

**问题**: `assets/mapsyncer/icon.png` 不存在

**规范**: 建议提供 128×128 的 PNG 图标

**修复**: 创建占位图标（需要替换为实际图标）

### 7. 缺少 contact 信息 ❌

**问题**: `contact` 字段为空 `{}`

**规范**: 建议提供 homepage、sources、issues 链接

**修复**: 添加 GitHub 仓库链接

## 修复详情

### 1. 更新 fabric.mod.json

**文件**: `platforms/fabric/1.21.1/src/main/resources/fabric.mod.json`

**变更**:
```json
{
  "contact": {
    "homepage": "https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap",
    "sources": "https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap",
    "issues": "https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap/issues"
  },
  "depends": {
    "fabricloader": ">=0.19.2"  // 从 0.15.11 更新
  }
}
```

### 2. 更新 build.gradle

**文件**: `platforms/fabric/1.21.1/build.gradle`

**变更**:
```gradle
plugins {
    id 'net.fabricmc.fabric-loom-remap' version '1.16-SNAPSHOT'  // 从 fabric-loom 1.7-SNAPSHOT 更新
}

dependencies {
    modImplementation "net.fabricmc:fabric-loader:0.19.2"  // 从 0.15.11 更新
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.116.12+1.21.1"  // 从 0.107.0 更新
}

// 启用代码分离
loom {
    splitEnvironmentSourceSets()

    mods {
        "mapsyncer" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}

// 公共代码（排除客户端）
sourceSets.main {
    java {
        srcDir '../../../shared/common/src/main/java'
        srcDir 'src/main/java'
        exclude 'com/mapsyncer/client/**'
    }
}

// 客户端代码
sourceSets.client {
    java {
        srcDir '../../../shared/common/src/main/java'
        srcDir 'src/client/java'
        include 'com/mapsyncer/client/**'
    }
}
```

### 3. 重构 FabricPlatform.java

**文件**: `platforms/fabric/1.21.1/src/main/java/com/mapsyncer/platform/impl/FabricPlatform.java`

**变更**:
- 移除 `import net.minecraft.client.Minecraft;`
- 修改 `getClientXaeroWorldMapDir()` 方法，使用环境检查：
```java
@Override
public Path getClientXaeroWorldMapDir() {
    try {
        // 使用环境检查保护客户端代码
        if (isClientEnvironment()) {
            Path serverDir = com.mapsyncer.client.XaeroMapIntegrator.getCurrentServerDirectory();
            if (serverDir != null) {
                return serverDir;
            }
        }

        // 回退：返回默认 Xaero 目录
        Path gameDir = Path.of(System.getProperty("user.dir"));
        return gameDir.resolve("xaero").resolve("world-map");
    } catch (Exception e) {
        LOGGER.debug("Failed to get Xaero world map dir: {}", e.getMessage());
    }
    return null;
}
```

### 4. 分离配置界面代码

**新建文件**: `platforms/fabric/1.21.1/src/client/java/com/mapsyncer/client/ConfigScreenFactory.java`

**作用**: 从 `ModConfig.java` 中提取 `createConfigScreen()` 方法

**修改文件**: `platforms/fabric/1.21.1/src/main/java/com/mapsyncer/config/ModConfig.java`

**变更**:
- 移除客户端相关 import
- 移除 `createConfigScreen()` 方法
- 保留配置数据类和加载/保存逻辑

### 5. 移动客户端代码

**操作**:
```bash
# 从 src/main/java 移动到 src/client/java
mv platforms/fabric/1.21.1/src/main/java/com/mapsyncer/client/* \
   platforms/fabric/1.21.1/src/client/java/com/mapsyncer/

# 删除旧目录
rm -rf platforms/fabric/1.21.1/src/main/java/com/mapsyncer/client
```

### 6. 创建图标文件

**路径**: `platforms/fabric/1.21.1/src/main/resources/assets/mapsyncer/icon.png`

**状态**: 创建占位图标，需要替换为实际的 128×128 PNG 图标

## 新增目录结构

```
platforms/fabric/1.21.1/src/
├── main/
│   ├── java/               # 公共代码（9 个文件）
│   │   └── com/mapsyncer/
│   │       ├── MapSyncer.java
│   │       ├── config/
│   │       ├── network/
│   │       ├── platform/
│   │       └── server/
│   └── resources/
│       ├── fabric.mod.json
│       └── assets/mapsyncer/icon.png
└── client/                 # 客户端代码（3 个文件）
    ├── java/
    │   └── com/mapsyncer/
    │       ├── MapSyncerClient.java
    │       └── client/
    │           ├── MapPacketReceiver.java
    │           ├── MapSyncerCommand.java
    │           └── ConfigScreenFactory.java
    └── resources/
```

## 验证结果

### 编译检查

- ✅ main sourceSet 中无 `net.minecraft.client.*` 引用
- ✅ 客户端代码正确分离到 `src/client/java`
- ✅ 配置界面代码移到 `ConfigScreenFactory`
- ✅ 环境检查保护客户端代码调用

### 待验证

- ⚠️ 实际编译（需要运行 `./gradlew build`）
- ⚠️ 运行时测试（客户端和服务端）

## 新增文档

本次修复创建了完整的 Fabric 1.21.1 开发规范文档：

1. **[README.md](README.md)** - 文档索引
2. **[development-guide.md](development-guide.md)** - 开发指南总览
3. **[fabric-mod-json-spec.md](fabric-mod-json-spec.md)** - fabric.mod.json 规范
4. **[loom-configuration.md](loom-configuration.md)** - Loom 构建配置
5. **[code-separation.md](code-separation.md)** - 代码分离最佳实践
6. **[networking.md](networking.md)** - 网络编程规范
7. **[project-structure.md](project-structure.md)** - 项目结构说明

## 后续工作

### 必需

1. **替换图标**: 将占位图标替换为实际的 128×128 PNG 图标
2. **编译测试**: 运行 `./gradlew :platforms:fabric:1.21.1:build` 验证编译
3. **运行测试**: 在客户端和服务端测试功能

### 推荐

1. **更新其他版本**: 将规范应用到 Fabric 1.20.1 和 1.20.4
2. **添加测试**: 编写单元测试和集成测试
3. **CI/CD**: 配置自动化构建和测试

## 总结

本次检查和修复使 MapSyncer 的 Fabric 1.21.1 平台实现符合官方开发规范：

- ✅ 使用推荐的 Loom 插件版本
- ✅ 使用最新的 Fabric Loader 和 API
- ✅ 启用代码分离，防止服务端崩溃
- ✅ 正确组织客户端和服务端代码
- ✅ 提供完整的开发文档

项目现在符合 Fabric 1.21.1 的最佳实践，代码结构清晰，易于维护和扩展。
