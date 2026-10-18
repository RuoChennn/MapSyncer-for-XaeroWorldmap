---
name: build-forge-1201
description: >
  构建 MapSyncer 的 Forge 1.20.1 版本，或处理 Forge 1.20.1 的 reobf / shadowJar /
  NoSuchMethodError 问题。触发词：forge 1.20.1、reobf、shadowJar、Commands.literal、
  构建 forge。
---

# 构建 Forge 1.20.1

## 关键坑（先读，避免踩坑）

1. **shadowJar 必须 reobf**：Minecraft 1.20.1 官方 jar 仍是混淆名，Forge 运行时需要
   mod 编译成 SRG 名。`mc-1.20.1/forge/build.gradle` 已加 `reobf { shadowJar {} }`，**不要删除**。
   - 注意 1.20.2+ 是 `reobf = false`（官方 jar 改用 mojmap 名），**不要照搬到 1.20.1**。

2. **禁用 `-c` 参数**：`gradle -c scripts/fastbuild/settings-forge.gradle` 会导致
   ForgeGradle 6 插件加载失败（buildscript classpath 为空，`compileJava` 等任务全部消失）。

## 正确构建步骤

直接运行构建脚本（推荐，自动切换 settings 和设置 JDK）：

```
scripts\fastbuild\build-forge-1.20.1.bat
```

或手动执行（Bash/PowerShell）：

1. 切换 settings 文件：
   ```
   cp settings.gradle settings.bak.gradle
   cp scripts/fastbuild/settings-forge.gradle settings.gradle
   ```
2. 在 `gradle.properties` 设置 JDK 17：
   ```
   org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot
   ```
3. 构建：
   ```
   gradle-8.9/bin/gradle.bat :mc-1.20.1:forge:build -x test --no-daemon
   ```
4. 恢复 `settings.gradle` 和 `gradle.properties`。

## 验证 reobf 是否正确

```
javap -c -cp mc-1.20.1/forge/build/libs/mapsyncer-1.0.4-forge-1.20.jar com.mapsyncer.server.CacheGenerateCommand | grep Commands
```

- 正确：`Commands.m_82127_`（SRG 名，运行时可映射）
- 错误：`Commands.literal`（mojmap 名，运行时 `NoSuchMethodError`）

## 根因速查

NoSuchMethodError: Commands.literal 的完整链条：
官方 jar 混淆名 → FML 需要 SRG 名 → ForgeGradle 默认只 reobf `jar` 任务 → shadowJar
产物未 reobf 仍是 mojmap 名 → 运行时找不到 `literal`。
