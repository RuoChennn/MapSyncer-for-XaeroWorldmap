# MapSyncer 分版本构建指南

## Gradle 版本兼容性问题

由于不同 Minecraft 版本和平台需要不同版本的 Gradle：

| 平台/版本 | Gradle 版本 | 原因 |
|----------|------------|------|
| NeoForge 1.20.x | 8.5 | NeoGradle 6.x 最高支持 Gradle 8.5 |
| NeoForge 1.21.x | 8.9 | ModDev 2.x 需要 Gradle 8.8+ |
| Fabric | 8.9 | Fabric Loom 1.7 需要 Gradle 8.8+ |
| Forge | 8.9 | ForgeGradle 6.x 支持 Gradle 8.x |

## 使用构建脚本

使用 `fastbuild/build-target.ps1` 脚本自动切换 Gradle 版本：

```powershell
# 构建 NeoForge 1.21.1（自动使用 Gradle 8.9）
.\fastbuild\build-target.ps1 neoforge-1.21.1

# 构建 NeoForge 1.20.4（自动使用 Gradle 8.5）
.\fastbuild\build-target.ps1 neoforge-1.20.4

# 构建所有 NeoForge 1.21.x 版本
.\fastbuild\build-target.ps1 neoforge-all

# 清理后构建
.\fastbuild\build-target.ps1 neoforge-1.21.1 -Clean

# 跳过测试
.\fastbuild\build-target.ps1 neoforge-1.21.1 -NoTest
```

## 手动切换 Gradle 版本

编辑 `gradle/wrapper/gradle-wrapper.properties`：

```properties
# 使用 Gradle 8.5
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip

# 使用 Gradle 8.9
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

## 当前可用模块

### Gradle 8.9（默认）
- core, platform-api, minecraft-common
- neoforge-1.21.1, neoforge-1.21.11
- forge-1.20.1, forge-1.20.4, forge-1.21.1, forge-1.21.11
- fabric-1.20.1, fabric-1.20.4, fabric-1.21.1

### Gradle 8.5（需要切换）
- neoforge-1.20.4（配置问题待解决）

## 注意事项

1. 切换 Gradle 版本后首次构建会下载对应版本的 Gradle
2. Gradle wrapper 会缓存多个版本在 `~/.gradle/wrapper/dists/`
3. NeoForge 1.20.x 构建需要额外解决 NeoForm 配置问题