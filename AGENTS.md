# MapSyncer 项目约定

## 测试环境

所有游戏内测试一律使用本机固定环境，不另建临时服务端或客户端：

- 测试服务端统一存放在 `E:\Game\服务器端`，按 loader + MC 版本分目录（如 `fabric 1.21.1 server`、`neoforge 26.1 server`）。
- 测试客户端统一存放在 `E:\Game\pcl\.minecraft\versions`。
- 启动服务端、替换模组、查看服务端日志，都在对应服务端目录内进行；服务端日志读取 `<服务端目录>/logs/latest.log`。
- 客户端日志读取对应实例的 `logs/latest.log`：PCL 启用版本隔离时为 `versions/<版本名>/logs/latest.log`，否则为 `.minecraft/logs/latest.log`。
- 以上两个目录是用户明确授权的仓库外测试路径，测试时可直接读写，不需要再次请示；但除测试运行必需的模组文件、配置和日志外，临时脚本、下载物和测试数据仍放到 `cache/tmp/`，不要污染实例目录。

## 临时文件与测试产物

所有临时测试文件、脚本、日志、反编译输出、下载物和测试数据，一律存放在仓库根目录的 `cache/` 下，禁止写到仓库之外的任意位置。

- 临时目录统一用 `cache/tmp/`。需要 `TMPDIR`、`TEMP`、`TMP` 或 `java.io.tmpdir` 时，指向该目录。
- 临时文件按任务建立子目录，例如 `cache/tmp/<任务名>/`，便于事后整体清理。
- 需要临时运行 Gradle 项目缓存时用 `--project-cache-dir cache/gradle-project-cache`；Gradle 用户目录默认沿用现有缓存，避免重复下载依赖。
- 禁止写入 `%TEMP%`、`$HOME`、桌面、文档、下载目录，以及仓库之外的任何路径。
- 确需在仓库外写入时，先说明原因并征得用户同意。
- 清理临时文件时只删除 `cache/` 内对应子目录，不要删除 `cache/` 本身。

常规构建输出（`build/`、`.gradle/`、`output/`）保持现有约定，不视为临时测试文件。

## 编译与构建

执行编译验证时，优先使用 `scripts/fastbuild/` 下的快速编译脚本：Windows 只用 cmd 运行 `.bat`（如 `cmd /c scripts\fastbuild\build-target.bat fabric-1.21.1`），Linux/WSL 用 bash 运行 `.sh`。不保留 `.ps1` 或 PowerShell 调用，也不要直接跑完整 `gradlew build`。

- 目标组合（Loader + MC 版本）没有现成脚本时，Windows 按既有 cmd 风格新增 `.bat`，Linux/WSL 同步补对应 bash `.sh`；复用现有 settings 切换、JDK 切换和产物收集逻辑。不允许引入 `.ps1` 或 PowerShell 调用。
- 编译报错时，先定位并修复对应 `.bat` 脚本（含脚本调用的 Gradle / settings 配置），再重新执行，直到编译成功为止；不要绕过脚本改用其他方式交付。
- 所有交付产物必须是无需外部依赖的单文件 JAR：依赖需 shade / 内联，禁止 `-slim.jar`、分离依赖包或多文件分发；产物经 `copy-release-jars.bat` 收集到 `output/` 后确认可独立加载。
