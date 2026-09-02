# 跨地址地图同步缓存复用修复指南

## 问题描述

当服务器使用内网穿透（如 Cloudflare Tunnel）有多个入口链接且 IP 地址经常变更时，客户端切换地址后地图无法显示，一直提示"等待服务器响应"。

## 根因分析

### 1. 原复制方案性能问题

原 `handleMultiEntryCacheReuse` 通过复制整目录实现缓存复用：

```java
// 旧方案：复制整目录（5-6GB 耗时过长)
Files.copy(srcDir, dstDir, StandardCopyOption.REPLACE_EXISTING);
```

当已下载地图达到 5-6GB 时，复制耗时过长，玩家体验极差。

### 2. 空壳目录检测 bug

原代码使用 `currentIPDir.resolve("null").toFile().exists()` 判断目录是否存在：

```java
// 旧代码：空壳目录也返回 true
if (currentIPDir.resolve("null").toFile().exists()) {
    return; // 跳过复制
}
```

空壳目录（只有目录结构没有地图文件）也被判定为"已存在"，导致本地完整地图永远不会被搬过来。

## 修复内容

### 1. 零拷贝重命名方案

`handleMultiEntryCacheReuse` 从复制整目录改为 `Files.move` 重命名：

```java
// 新方案：零拷贝重命名（毫秒级）
Files.move(srcDir, dstDir, StandardCopyOption.ATOMIC_MOVE);
```

重命名操作是文件系统元数据修改，不涉及数据复制，5-6GB 地图也能瞬间完成。

### 2. 空壳目录自动删除后重命名

```java
// 检测空壳目录（无 region 子目录）
if (isShellDirectory(currentIPDir)) {
    deleteDirectory(currentIPDir); // 删除空壳
}
// 执行重命名
Files.move(srcDir, dstDir, StandardCopyOption.ATOMIC_MOVE);
```

### 3. 根因 bug 修正

空壳目录检测逻辑修正：检查 `region` 子目录是否存在且包含 `.mca` 文件，而非仅判断目录是否存在。

## 测试验证

### 场景 1：外网地址进服
1. 玩家通过外网地址进服
2. 地图数据下载到 `Multiplayer_外网IP/` 目录
3. 切换为内网地址进服
4. `handleMultiEntryCacheReuse` 瞬间将 `Multiplayer_外网IP/` 重命名为 `Multiplayer_内网IP/`
5. 地图立即显示，无"等待服务器响应"

### 场景 2：空壳目录处理
1. 玩家曾进服但未下载地图（目录存在但为空壳）
2. 切换地址后，空壳目录被自动删除
3. 完整地图目录被重命名过来
4. 地图正常显示

## 相关配置

无需额外配置，零拷贝重命名方案在 `handleMultiEntryCacheReuse` 中自动生效。
