# NeoForge 26.1 开发指南

本文档整理了与 MapSyncer 项目相关的 NeoForge 26.1 知识，供开发参考。

## 目录

- [版本系统](#版本系统)
- [项目配置](#项目配置)
- [开发环境要求](#开发环境要求)
- [构建系统](#构建系统)
- [Mod 配置文件](#mod-配置文件)
- [兼容性说明](#兼容性说明)
- [常见问题](#常见问题)

---

## 版本系统

### Minecraft 26.1+ 新版本系统

从 Minecraft 26.1 开始，Mojang 采用了新的 **calver（日历版本）** 系统：

```
格式：year.release.<patch>
```

**版本号含义：**
- **26.1** - 2026 年第一个 drop（大版本）
- **26.1.1** - 26.1 的热修复版本（hotfix）
- **26.1.2** - 26.1 的第二个热修复
- **26.2** - 2026 年第二个 drop（下一个大版本）

**快照版本格式：**
- `26.1-snapshot-1` - 26.1 的第一个快照
- `26.1-pre-1` - 26.1 的第一个预发布版
- `26.1-rc-1` - 26.1 的第一个候选发布版

### NeoForge 版本系统

NeoForge 使用改编的语义化版本（semver）系统：

```
格式：MC次版本.MC补丁版本.NeoForge版本[-后缀]

示例：NeoForge 26.1.0.19-beta
- 26 = Minecraft 26.x 系列
- 1 = Minecraft 26.1
- 0 = 补丁版本占位符
- 19 = NeoForge 第 19 个版本
- beta = 预发布标识
```

**版本对应关系：**
- NeoForge `26.1.0.x` → Minecraft `26.1`
- NeoForge `26.1.1.x` → Minecraft `26.1.1`（热修复）
- NeoForge `26.2.0.x` → Minecraft `26.2`（下一个大版本）

**参考文档：**
- [NeoForge 官方版本文档](https://docs.neoforged.net/docs/gettingstarted/versioning/)

---

## 项目配置

### 当前版本配置

MapSyncer 项目的 NeoForge 26.1 平台配置：

**gradle.properties:**
```properties
# NeoForge 26.1 versions (Minecraft 26.1)
neo_261_version=26.1.0.19-beta
neo_261_version_range=[26.1,)
```

**构建文件位置：**
```
platforms/neoforge/26.1/build.gradle
platforms/neoforge/26.1/src/main/resources/META-INF/neoforge.mods.toml
```

### 多版本支持

项目支持多个 Minecraft 版本：
- 1.20.1 / 1.20.4 / 1.21.1 / 1.21.11 (NeoForge 21.x)
- 26.1 (NeoForge 26.1.x)

---

## 开发环境要求

### Java 版本

NeoForge 26.1 要求 **Java 25**：

```gradle
java.toolchain.languageVersion = JavaLanguageVersion.of(25)
```

**JDK 安装路径（gradle.properties）：**
```properties
org.gradle.java.installations.paths=C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot
```

### Gradle 插件

NeoForge 26.1 使用 **ModDevGradle** 插件（替代旧版 NeoGradle）：

```gradle
plugins {
    id 'net.neoforged.moddev' version '2.0.141'
}
```

**参考文档：**
- [ModDevGradle 文档](https://docs.neoforged.net/docs/gettingstarted/moddevgradle)

---

## 构建系统

### build.gradle 结构

```gradle
neoForge {
    // NeoForge 版本
    version = "26.1.0.19-beta"

    // 运行配置
    runs {
        client {
            client()
            systemProperty 'neoforge.enabledGameTestNamespaces', mod_id
        }
        server {
            server()
            programArgument '--nogui'
        }
    }

    // Mod 源码注册
    mods {
        "${mod_id}" {
            sourceSet sourceSets.main
        }
    }
}
```

### ShadowJar 打包

项目使用 ShadowJar 打包内部依赖（core 和 platform-api）：

```gradle
plugins {
    id 'com.gradleup.shadow' version '8.3.5'
}

shadowJar {
    archiveClassifier.set('')
    configurations = [project.configurations.bundle]
    exclude 'org/slf4j/**'  // 排除运行时已提供的依赖
}
```

### 依赖管理

```gradle
dependencies {
    implementation project(':libs:core')
    implementation project(':libs:platform-api')
}

configurations {
    bundle
}
dependencies {
    bundle project(':libs:core')
    bundle project(':libs:platform-api')
}
```

---

## Mod 配置文件

### neoforge.mods.toml

**文件位置：** `platforms/neoforge/26.1/src/main/resources/META-INF/neoforge.mods.toml`

```toml
modLoader="javafml"
loaderVersion="[4,)"
license="All Rights Reserved"

[[mods]]
modId="mapsyncer"
version="1.0.1"
displayName="MapSyncer"
description="Syncs server-side explored areas to client's Xaero World Map"
authors="Ruo_Chen"

# NeoForge 依赖
[[dependencies.mapsyncer]]
modId="neoforge"
type="required"
versionRange="[26.1,)"    # 支持所有 26.1.x 版本
ordering="NONE"
side="BOTH"

# Minecraft 依赖
[[dependencies.mapsyncer]]
modId="minecraft"
type="required"
versionRange="[26.1,26.2)"  # 支持 26.1 到 26.1.x，不包括 26.2
ordering="NONE"
side="BOTH"
```

### 版本范围说明

**NeoForge 版本范围：**
- `[26.1,)` - 从 26.1 开始的所有版本（包括 26.1.0.x, 26.1.1.x 等）

**Minecraft 版本范围：**
- `[26.1,26.2)` - 从 26.1（含）到 26.2（不含）
- 这覆盖了所有 26.1.x 热修复版本（26.1.1, 26.1.2 等）

**为什么使用左闭右开区间？**
- `[26.1,26.2)` 确保 mod 在 26.1 的所有热修复版本上都能运行
- 避免在 26.2（可能有 API 变更）上意外加载

---

## 兼容性说明

### 跨补丁版本兼容性

**问题：** Mod 在 26.1 开发后，能在 26.1.1 和 26.1.2 中使用吗？

**答案：** ✅ **可以**

**原因：**
1. 26.1.1 和 26.1.2 是 **热修复版本**，只包含 bug 修复，不包含 API 变更
2. `versionRange="[26.1,26.2)"` 配置已覆盖所有 26.1.x 版本
3. NeoForge 承诺在补丁版本中保持向后兼容性

**最佳实践：**
```toml
# 推荐：覆盖所有 26.1.x 补丁版本
versionRange="[26.1,26.2)"

# 不推荐：仅支持特定版本
versionRange="[26.1,26.1.1)"  # 只支持 26.1，不支持 26.1.1
```

### 跨大版本兼容性

**问题：** Mod 在 26.1 开发后，能在 26.2 中使用吗？

**答案：** ❌ **可能不行**

**原因：**
1. 26.2 是新的大版本，可能有 API 变更
2. 当前配置 `[26.1,26.2)` 明确排除 26.2
3. 需要为 26.2 单独开发和测试

---

## 常见问题

### 1. 如何升级 NeoForge 版本？

**步骤：**
1. 修改 `gradle.properties` 中的 `neo_261_version`
2. 同步 Gradle 配置
3. 运行 `./gradlew :platforms:neoforge:26.1:build` 测试
4. 检查是否有 API 变更需要适配

**示例：**
```properties
# 从 beta 升级到稳定版
neo_261_version=26.1.0.50  # 移除 -beta 后缀
```

### 2. 如何支持 26.1.1 热修复版本？

**无需额外配置！**

当前的 `versionRange="[26.1,26.2)"` 已经覆盖所有 26.1.x 版本，包括：
- 26.1.0 (基础版本)
- 26.1.1 (第一个热修复)
- 26.1.2 (第二个热修复)
- ...

**验证方法：**
检查 `neoforge.mods.toml` 中的版本范围配置。

### 3. 构建时找不到 Java 25 怎么办？

**解决方案：**

1. **安装 JDK 25：**
   - 推荐：[Eclipse Adoptium Temurin](https://adoptium.net/)
   - 或者其他 OpenJDK 发行版

2. **配置 Gradle：**
   ```properties
   # gradle.properties
   org.gradle.java.installations.paths=C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot
   ```

3. **验证安装：**
   ```bash
   java -version
   # 应显示：openjdk version "25.0.x"
   ```

### 4. NeoForge 26.1 和 1.21.1 有什么区别？

**主要区别：**

| 特性 | 1.21.1 | 26.1 |
|------|--------|------|
| Minecraft 版本 | 1.21.1 | 26.1 |
| NeoForge 版本 | 21.1.x | 26.1.0.x |
| Java 版本 | 21 | 25 |
| Gradle 插件 | NeoGradle | ModDevGradle |
| 版本系统 | semver | calver |

**API 变化：**
- NeoForge 26.1 是基于 Minecraft 26.1 的全新版本
- 部分 API 可能有 breaking changes
- 需要参考 [NeoForge 迁移指南](https://docs.neoforged.net/docs/migration/)

### 5. 如何在 mods.toml 中指定依赖版本？

**推荐配置：**

```toml
# NeoForge：使用宽松范围，支持所有 26.1.x
[[dependencies.mapsyncer]]
modId="neoforge"
versionRange="[26.1,)"

# Minecraft：限制到 26.1.x，排除 26.2
[[dependencies.mapsyncer]]
modId="minecraft"
versionRange="[26.1,26.2)"
```

**不推荐：**
```toml
# 过于严格：只支持特定版本
versionRange="[26.1.0.19-beta,26.1.0.20-beta)"

# 过于宽松：可能在不兼容版本上运行
versionRange="[26.1,)"
```

---

## 参考资源

### 官方文档
- [NeoForge 官方文档](https://docs.neoforged.net/)
- [NeoForge Getting Started](https://docs.neoforged.net/docs/gettingstarted/)
- [NeoForge Versioning](https://docs.neoforged.net/docs/gettingstarted/versioning/)
- [ModDevGradle 文档](https://docs.neoforged.net/docs/gettingstarted/moddevgradle)

### 项目相关
- [MapSyncer 项目 README](../../README.md)
- [NeoForge 26.1 平台代码](../../platforms/neoforge/26.1/)
- [项目构建配置](../../build.gradle)

### 社区资源
- [NeoForge Discord](https://discord.neoforged.net/)
- [NeoForge GitHub](https://github.com/neoforged)
- [NeoForge Maven](https://maven.neoforged.net/)

---

## 更新日志

**2026-05-29 - 初始版本**
- 整理 NeoForge 26.1 版本系统知识
- 记录项目配置和构建系统
- 添加兼容性说明和常见问题

---

*本文档由 MapSyncer 开发团队维护，如有问题请提交 Issue。*
