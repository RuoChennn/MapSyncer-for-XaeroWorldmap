# 同步超时与重发机制修复指南

## 问题描述

客户端进服后地图同步一直显示"等待服务端响应"，无实际进度，最终超时失败。

## 根因分析

### 1. 服务端分包超时无通知

服务端分包上传过程中，网络波动导致分包超时，服务端未主动通知客户端，客户端无限等待。

### 2. 客户端同步请求无重试

客户端发送同步请求后，服务端因负载过高或网络问题未响应，客户端无重试机制，直接显示"等待服务端响应"。

### 3. 分片丢失不恢复

分片传输过程中部分分片丢失，客户端等待完整包组装，无即时重发机制。

## 修复内容

### 1. 服务端分包 20s 超时通知重发

```java
// 分包上传超时（20s）后主动通知客户端重发
if (System.currentTimeMillis() - lastPartTime > PART_ASSEMBLY_TIMEOUT_MS) {
    sendPartTimeoutNotice(session, partIndex);
}
```

### 2. 客户端 60s 超时自动重发（最多 3 次）

```java
// 同步请求超时（60s）后自动重发，最多 3 次
if (System.currentTimeMillis() - requestTime > CLIENT_TIMEOUT_MS) {
    if (retryCount < MAX_RETRIES) {
        retrySyncRequest(session, retryCount + 1);
    } else {
        abortSync(session, "timeout");
    }
}
```

### 3. `request_partial_timeout` 即时重发

```java
// 分片丢失时即时请求重发，不等待整包超时
if (isPartMissing(partIndex)) {
    sendPartRequest(session, partIndex);
}
```

## 相关配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `PART_ASSEMBLY_TIMEOUT_MS` | 20000 (20s) | 服务端分包组装超时 |
| `CLIENT_TIMEOUT_MS` | 60000 (60s) | 客户端同步请求超时 |
| `MAX_RETRIES` | 3 | 客户端最大重试次数 |
