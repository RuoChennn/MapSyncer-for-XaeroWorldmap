# 自动同步进度显示修复指南

## 问题描述

客户端进服后自动同步地图，但一直显示"等待服务端响应"，无实际进度条。

## 根因分析

`handleProgressUpdate` 中存在静默丢弃逻辑：

```java
// 原代码：自动同步进度包被静默丢弃
if (AutoSyncManager.isActive()) {
    return; // 直接丢弃，不更新进度显示
}
```

当 `AutoSyncManager.isActive()` 为 true 时（自动同步进行中），进度包被直接丢弃，导致 UI 一直显示"等待服务端响应"。

## 修复内容

删除该静默判断，让进度包正常传递到 UI：

```java
// 修复后：正常处理进度包
handleProgressUpdate(payload);
```

## 影响

- 自动同步时进度条正常显示
- 手动同步不受影响
