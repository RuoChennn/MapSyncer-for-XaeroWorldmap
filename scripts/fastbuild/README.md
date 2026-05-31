# MapSyncer 分版本构建指南

## 当前可用模块

| MC 版本 | Fabric | Forge | NeoForge |
|---------|--------|-------|----------|
| 1.20.1  | fabric | forge | neoforge |
| 1.21.1  | fabric | forge | neoforge |
| 26.1    | fabric | —     | neoforge |

## Gradle 版本兼容性

| 平台/版本 | Gradle 版本 | 原因 |
|----------|------------|------|
| NeoForge 1.20.1 | 8.5 | NeoGradle 6.x 最高支持 Gradle 8.5 |
| 其他所有平台 | 8.9 | Loom 1.7+ / ForgeGradle 6.x+ / ModDev 2.x |

## 使用构建脚本

### PowerShell 构建工具

```powershell
# 构建 Forge 1.20.1
.\scripts\fastbuild\build-target.ps1 forge-1.20.1 -Clean -NoTest

# 构建所有版本
.\scripts\fastbuild\build-target.ps1 all -NoTest
```

### Windows 批处理

```batch
# 构建指定版本
scripts\fastbuild\build-forge-1.20.1.bat

# 构建全部版本
scripts\fastbuild\build-all.bat
```

### Linux / WSL

```bash
bash scripts/fastbuild/build-all.sh
```

## 手动切换 Gradle 版本

编辑 `gradle/wrapper/gradle-wrapper.properties`：

```properties
# 使用 Gradle 8.5（仅 NeoForge 1.20.1 需要）
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip

# 使用 Gradle 8.9（默认）
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

## 注意事项

1. 切换 Gradle 版本后首次构建会下载对应版本的 Gradle
2. Gradle wrapper 会缓存多个版本在 `~/.gradle/wrapper/dists/`
3. NeoForge 1.20.1 需要切换到 Gradle 8.5 后单独构建
