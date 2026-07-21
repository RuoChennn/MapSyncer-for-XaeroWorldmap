# MapSyncer 分版本构建指南

## 项目结构（G1–G4）

```
libs/common/          # 全版本业务逻辑
libs/mc-1.20/         # G1 锚点（编译目标 1.20.1）
libs/mc-1.21/         # G2 锚点（编译目标 1.21.1）
libs/mc-1.21.11/      # G3 锚点
libs/mc-26/           # G4 锚点（编译目标 26.1）
mc-{精确版本}/{fabric|forge|neoforge}/   # Loader 胶水层
```

完整 includes / excludes 见 [gradle/versions.toml](../../gradle/versions.toml)。

## 当前胶水模块

| 锚点组 | 编译 MC | Fabric | Forge | NeoForge | JAR 后缀示例 |
|--------|---------|--------|-------|----------|--------------|
| G1 | 1.20.1 | `mc-1.20.1/fabric` | `mc-1.20.1/forge` | — | `1.0.4-fabric-1.20` |
| G2 | 1.21.1 | `mc-1.21.1/fabric` | `mc-1.21.1/forge` | `mc-1.21.1/neoforge` | `1.0.4-fabric-1.21` |
| G3 | 1.21.11 | `mc-1.21.11/fabric` | `mc-1.21.11/forge` | `mc-1.21.11/neoforge` | `1.0.4-fabric-1.21.11` |
| G4 | 26.1 | `mc-26.1/fabric` | — | `mc-26.1/neoforge` | `1.0.4-fabric-26.1` |
| G4′ | 26.2 | `mc-26.2/fabric` | — | `mc-26.2/neoforge` | `1.0.4-fabric-26.2` |

元数据 MC 范围按锚点组放宽（如 G2 `>=1.21 <1.22`），二进制仍按上表锚点编译。

## 隔离 settings（Loom / ForgeGradle 冲突）

| 场景 | settings 文件 | Gradle |
|------|---------------|--------|
| 默认（Fabric 1.20/1.21 + NeoForge） | `settings.gradle` | 9.4（wrapper） |
| Forge 全版本 | `scripts/fastbuild/settings-forge.gradle` | **8.9**（`gradle-8.9/`，ForgeGradle 不支持 Gradle 9） |
| Fabric 1.21.11 | `scripts/fastbuild/settings-12111.gradle` | 9.4 |
| MC 26.x（Fabric + NeoForge） | `scripts/fastbuild/settings-26.gradle` | 9.4 |

## 产物收集

所有 `scripts/fastbuild/*.bat` 通过 `copy-release-jars.ps1` / `copy-release-jars.bat` 收集 JAR 到 `output/`，**自动排除** `-slim.jar`、`-sources.jar`、`-javadoc.jar`。

根项目 `./gradlew collectJars` 仅收集**当前 settings 已 include** 的模块产物（不含 Forge / 隔离 Fabric）。

## 使用构建脚本

### 首次环境准备（一键部署依赖）

安装 JDK 17/21/25、下载 Gradle 8.9、引导 Wrapper，并预拉取 Maven / MC 工件：

```powershell
.\scripts\setup-deps.ps1              # 完整部署（含 Maven 预拉取，耗时较长）
.\scripts\setup-deps.ps1 -Quick       # 仅 JDK 检测 + Gradle 8.9 + Wrapper
```

或双击 `scripts\setup-deps.bat`。

### PowerShell（推荐）

```powershell
# 单目标
.\scripts\fastbuild\build-target.ps1 fabric-1.21.1 -Clean -NoTest
.\scripts\fastbuild\build-target.ps1 forge-1.20.1 -NoTest
.\scripts\fastbuild\build-target.ps1 fabric-1.21.11 -NoTest
.\scripts\fastbuild\build-target.ps1 fabric-26.1 -NoTest

# 全平台（等同 build-all.bat）
.\scripts\fastbuild\build-target.ps1 all -NoTest

# MapPackager 工具
.\scripts\fastbuild\build-target.ps1 packager
```

### Windows 批处理

```batch
scripts\fastbuild\build-forge-1.20.1.bat
scripts\fastbuild\build-all.bat
scripts\fastbuild\build-packager.bat
```

### Linux / WSL

```bash
bash scripts/fastbuild/build-all.sh
bash scripts/fastbuild/build-packager.sh
```

## Forge 构建前提

1. 项目根目录存在 `gradle-8.9/`（Gradle 8.9 二进制）
2. Forge 脚本会临时切换 `settings-forge.gradle` 并覆盖 `gradle.properties` 中的 `org.gradle.java.home`
3. G1 Forge 需要 JDK 17；G2/G3 Forge 需要 JDK 21

## 注意事项

1. **不要**再引用已删除的 `mc-*/shared/`；MC 相关源码在 `libs/mc-*`
2. 切换 settings 后 fastbuild 脚本会自动恢复 `settings.gradle`
3. G2/G4 元数据覆盖多版本，跨网络协议版本需实机验证
