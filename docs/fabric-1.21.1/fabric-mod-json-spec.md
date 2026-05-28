# fabric.mod.json 规范

`fabric.mod.json` 是 Fabric Mod 的元数据文件，必须放在 JAR 文件的根目录。

## 必需字段

### schemaVersion

必须是第一个字段，值必须为 `1`。

```json
"schemaVersion": 1
```

### id

Mod 的唯一标识符。

- 必须以字母开头
- 只能包含 ASCII 字母、数字、下划线或连字符
- 2-64 个字符

```json
"id": "mapsyncer"
```

### version

Mod 版本，应遵循 [Semantic Versioning 2.0.0](https://semver.org/)。

支持在 `processResources` 中使用占位符：

```json
"version": "${version}"
```

在 `build.gradle` 中配置替换：

```gradle
processResources {
    inputs.property "version", project.version

    filesMatching("fabric.mod.json") {
        expand "version": project.version
    }
}
```

## 元数据字段

### name

用户友好的 Mod 名称。如果不提供，默认使用 `id`。

```json
"name": "MapSyncer"
```

### description

Mod 描述。

```json
"description": "Syncs server-side explored areas to client's Xaero World Map"
```

### authors

作者列表，可以是字符串或对象：

```json
"authors": [
    "Author Name",
    {
        "name": "Another Author",
        "contact": {
            "homepage": "https://example.com"
        }
    }
]
```

### contact

项目联系信息字典：

```json
"contact": {
    "homepage": "https://github.com/user/repo",
    "sources": "https://github.com/user/repo",
    "issues": "https://github.com/user/repo/issues"
}
```

### license

许可证信息，推荐使用 [SPDX License Identifiers](https://spdx.org/licenses/)。

```json
"license": "MIT"
```

### icon

Mod 图标，必须是正方形 PNG 文件（推荐 128×128）。

单个文件：
```json
"icon": "assets/mapsyncer/icon.png"
```

多个尺寸：
```json
"icon": {
    "128": "assets/mapsyncer/icon-128.png",
    "64": "assets/mapsyncer/icon-64.png"
}
```

## Mod 加载配置

### environment

定义 Mod 运行环境：

- `*`: 所有环境（默认）
- `client`: 仅物理客户端
- `server`: 仅物理服务端

```json
"environment": "*"
```

### entrypoints

定义入口点类：

```json
"entrypoints": {
    "main": ["com.mapsyncer.MapSyncer"],
    "client": ["com.mapsyncer.MapSyncerClient"],
    "server": ["com.mapsyncer.MapSyncerServer"]
}
```

**入口点类型**:
- `main`: 实现 `ModInitializer`，在所有环境运行
- `client`: 实现 `ClientModInitializer`，仅客户端运行
- `server`: 实现 `DedicatedServerModInitializer`，仅服务端运行

### mixins

Mixin 配置文件列表：

```json
"mixins": [
    "mapsyncer.mixins.json",
    {
        "config": "mapsyncer.client.mixins.json",
        "environment": "client"
    }
]
```

### jars

嵌套 JAR 列表（Loom 会自动填充）：

```json
"jars": [
    {
        "file": "META-INF/jars/library.jar"
    }
]
```

### accessWidener

Access Widener 文件路径：

```json
"accessWidener": "mapsyncer.accesswidener"
```

## 依赖声明

### depends

必需依赖，缺失会导致崩溃：

```json
"depends": {
    "fabricloader": ">=0.19.2",
    "minecraft": ">=1.21.1 <1.21.2",
    "java": ">=21",
    "fabric-api": "*"
}
```

### recommends

推荐依赖，缺失会记录警告：

```json
"recommends": {
    "cloth-config": ">=15.0.0"
}
```

### suggests

建议依赖，仅作为元数据：

```json
"suggests": {
    "modmenu": "*"
}
```

### breaks

冲突 Mod，存在会导致崩溃：

```json
"breaks": {
    "incompatible-mod": "*"
}
```

### conflicts

冲突 Mod，存在会记录警告：

```json
"conflicts": {
    "problematic-mod": "<2.0.0"
}
```

## 版本范围语法

使用语义化版本范围：

| 范围 | 说明 | 示例 |
|------|------|------|
| `*` | 任何版本（不推荐） | `"fabric-api": "*"` |
| `1.0.0` | 精确版本 | `"minecraft": "1.21.1"` |
| `>=1.0.0` | 大于等于 | `"fabricloader": ">=0.19.2"` |
| `<2.0.0` | 小于 | `"minecraft": "<1.21.2"` |
| `>=1.0.0 <2.0.0` | 范围 | `"minecraft": ">=1.21.1 <1.21.2"` |
| `~1.0.0` | 同一次版本 | `"~1.21.1"` 等同于 `>=1.21.1 <1.22.0` |
| `^1.0.0` | 同一主版本 | `"^1.21.1"` 等同于 `>=1.21.1 <2.0.0` |

**注意**: Minecraft 不遵循语义化版本，Fabric 会自动转换（如 `1.21.1` → `1.21.1.0`）。

## MapSyncer 配置示例

```json
{
  "schemaVersion": 1,
  "id": "mapsyncer",
  "version": "${version}",
  "name": "MapSyncer",
  "description": "Syncs server-side explored areas to client's Xaero World Map",
  "authors": ["Ruo_Chen"],
  "contact": {
    "homepage": "https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap",
    "sources": "https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap",
    "issues": "https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap/issues"
  },
  "license": "MIT",
  "icon": "assets/mapsyncer/icon.png",
  "environment": "*",
  "entrypoints": {
    "main": ["com.mapsyncer.MapSyncer"],
    "client": ["com.mapsyncer.MapSyncerClient"]
  },
  "mixins": [],
  "depends": {
    "fabricloader": ">=0.19.2",
    "minecraft": ">=1.21.1 <1.21.2",
    "java": ">=21",
    "fabric-api": "*"
  },
  "suggests": {
    "cloth-config": "*"
  }
}
```

## 最佳实践

1. **始终提供 contact 信息**: 方便用户反馈问题
2. **使用 SPDX 许可证标识符**: 便于自动化工具识别
3. **声明准确的依赖范围**: 避免兼容性问题
4. **提供图标**: 提升 Mod 在启动画面的显示效果
5. **使用版本占位符**: 避免手动更新版本号
