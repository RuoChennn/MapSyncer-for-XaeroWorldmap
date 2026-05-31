# Fabric Loom 构建配置

Fabric Loom 是 Fabric 生态系统的 Gradle 插件，用于开发 Minecraft Mod。

## 插件选择

### 按 Minecraft 版本选择

- **Minecraft 1.21.11 及更早**（混淆版本）: 使用 `fabric-loom-remap`
- **Minecraft 26.1 及更新**（非混淆版本）: 使用 `fabric-loom`

```gradle
// Minecraft 1.21.1
plugins {
    id 'net.fabricmc.fabric-loom-remap' version '1.16-SNAPSHOT'
}

// Minecraft 26.1+
plugins {
    id 'net.fabricmc.fabric-loom' version '1.16-SNAPSHOT'
}
```

### 插件 ID 说明

| 插件 ID | 说明 |
|---------|------|
| `net.fabricmc.fabric-loom-remap` | 用于混淆版本（推荐） |
| `net.fabricmc.fabric-loom` | 用于非混淆版本 |
| `fabric-loom` | 旧名称，仅向后兼容 |

## 基础配置

### Java 工具链

```gradle
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
```

### Minecraft 依赖

```gradle
dependencies {
    minecraft "com.mojang:minecraft:1.21.1"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:0.19.2"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.116.12+1.21.1"
}
```

### Mappings 选择

```gradle
// 官方 Mojang 映射（推荐，与其他平台一致）
mappings loom.officialMojangMappings()

// Yarn 映射（社区维护）
mappings "net.fabricmc:yarn:1.21.1+build.1:v2"
```

## 依赖配置类型

### 标准依赖

```gradle
dependencies {
    // 普通依赖（不重映射）
    implementation "com.google.code.gson:gson:2.10.1"

    // Mod 依赖（会重映射）
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.116.12+1.21.1"

    // Mod 依赖（不包含在运行时）
    modCompileOnly "com.example:optional-mod:1.0.0"

    // 运行时依赖
    modRuntimeOnly "com.example:runtime-mod:1.0.0"
}
```

### 嵌套 JAR（Jar-in-Jar）

```gradle
dependencies {
    // 打包到最终 JAR 中
    include "com.example:library:1.0.0"

    // 同时作为依赖和嵌套 JAR
    modImplementation "com.example:mod:1.0.0"
    include "com.example:mod:1.0.0"
}
```

**注意**: 嵌套的非 Mod JAR 会自动生成 `fabric.mod.json`。

## 多项目构建

### 依赖其他 Loom 项目

```gradle
dependencies {
    // 使用 namedElements 配置（推荐）
    implementation project(path: ":other-mod", configuration: "namedElements")

    // 普通项目依赖（会重映射）
    implementation project(':libs:core')
}
```

### 源码复用（非独立编译模块）

```gradle
// 引用其他模块的源码目录
sourceSets.main {
    java {
        srcDir '../../../shared/common/src/main/java'
        exclude 'com/example/client/**'  // 排除客户端代码
    }
    resources {
        srcDir '../../../shared/common/src/main/resources'
    }
}
```

## 客户端/服务端代码分离

### 启用分离

```gradle
loom {
    splitEnvironmentSourceSets()

    mods {
        "modid" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}
```

### 目录结构

```
src/
├── main/
│   ├── java/          # 公共代码（客户端和服务端）
│   └── resources/
├── client/
│   ├── java/          # 客户端专用代码
│   └── resources/     # 客户端专用资源
└── server/            # 服务端专用代码（可选）
    ├── java/
    └── resources/
```

### 配置源码目录

```gradle
sourceSets.main {
    java {
        srcDir '../../../shared/common/src/main/java'
        exclude 'com/modid/client/**'
    }
}

sourceSets.client {
    java {
        srcDir '../../../shared/common/src/main/java'
        srcDir 'src/client/java'
        include 'com/modid/client/**'
    }
}
```

## 资源处理

### 版本占位符替换

```gradle
processResources {
    inputs.property "version", project.version
    inputs.property "minecraft_version", "1.21.1"
    inputs.property "loader_version", "0.19.2"

    filesMatching("fabric.mod.json") {
        expand "version": project.version,
                "minecraft_version": "1.21.1",
                "loader_version": "0.19.2"
    }
}
```

### 在 fabric.mod.json 中使用

```json
{
  "version": "${version}",
  "depends": {
    "minecraft": "${minecraft_version}",
    "fabricloader": ">=${loader_version}"
  }
}
```

## 编译配置

### Java 编译选项

```gradle
tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = 21  // 设置 Java 版本
}
```

### 生成源码 JAR

```gradle
java {
    withSourcesJar()

    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
```

## 发布配置

### Maven 发布

```gradle
publishing {
    publications {
        register('mavenJava', MavenPublication) {
            from components.java
        }
    }
    repositories {
        maven {
            url "file://${project.projectDir}/repo"
        }
    }
}
```

### 本地文件发布

```gradle
publishing {
    publications {
        register('local', MavenPublication) {
            from components.java
        }
    }
    repositories {
        maven {
            url "file://${project.buildDir}/repo"
        }
    }
}
```

## 仓库配置

### 推荐仓库

```gradle
repositories {
    mavenCentral()
    maven { url = 'https://maven.fabricmc.net/' }
    maven { url = 'https://maven.shedaniel.me/' }  // Cloth Config
    maven { url = 'https://maven.terraformersmc.com/releases/' }  // Mod Menu
}
```

### 阿里云镜像（国内加速）

```gradle
repositories {
    maven { url = 'https://maven.aliyun.com/repository/public' }
}
```

## 任务说明

### 常用任务

```bash
# 生成源码（反编译 Minecraft）
./gradlew genSources

# 启动客户端
./gradlew runClient

# 启动服务端
./gradlew runServer

# 构建 Mod JAR
./gradlew build

# 刷新依赖
./gradlew build --refresh-dependencies

# 清理构建
./gradlew clean
```

### Loom 特定任务

```bash
# 重新映射 JAR
./gradlew remapJar

# 重新映射源码 JAR
./gradlew remapSourcesJar

# 生成运行配置
./gradlew genIdeaRuns  # IntelliJ IDEA
./gradlew genEclipseRuns  # Eclipse
```

## 缓存说明

### 缓存位置

- **用户缓存**: `${GRADLE_HOME}/caches/fabric-loom`
  - Minecraft 资源、JAR、合并 JAR、中间 JAR、映射 JAR
- **项目缓存**: `.gradle/loom-cache`
  - 重映射的 Mod、生成的嵌套 Mod JAR
- **构建缓存**: `**/build/loom-cache`

### 清理缓存

```bash
# 清理 Loom 缓存
./gradlew clean
rm -rf .gradle/loom-cache

# 强制刷新所有依赖
./gradlew build --refresh-dependencies
```

## MapSyncer 项目配置示例

```gradle
plugins {
    id 'net.fabricmc.fabric-loom-remap' version '1.16-SNAPSHOT'
}

version = mod_version + '-fabric-1.21.1'
group = mod_group_id

base {
    archivesName = mod_id
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenCentral()
    maven { url = 'https://maven.fabricmc.net/' }
    maven { url = 'https://maven.aliyun.com/repository/public' }
    maven { url = 'https://maven.shedaniel.me/' }
}

// 公共代码（排除客户端）
sourceSets.main {
    java {
        srcDir '../../../shared/common/src/main/java'
        srcDir 'src/main/java'
        exclude 'com/mapsyncer/client/**'
    }
    resources {
        srcDir '../../../shared/common/src/main/resources'
        srcDir 'src/main/resources'
    }
}

// 客户端代码分离
loom {
    splitEnvironmentSourceSets()

    mods {
        "mapsyncer" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}

sourceSets.client {
    java {
        srcDir '../../../shared/common/src/main/java'
        srcDir 'src/client/java'
        include 'com/mapsyncer/client/**'
    }
    resources {
        srcDir '../../../shared/common/src/main/resources'
        srcDir 'src/client/resources'
    }
}

dependencies {
    minecraft "com.mojang:minecraft:1.21.1"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:0.19.2"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.116.12+1.21.1"
    modImplementation "me.shedaniel.cloth:cloth-config-fabric:15.0.127"

    implementation project(':libs:core')
    implementation project(':libs:platform-api')
    include project(':libs:core')
    include project(':libs:platform-api')
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}

processResources {
    inputs.property "version", project.version
    inputs.property "minecraft_version", "1.21.1"
    inputs.property "loader_version", "0.19.2"

    filesMatching("fabric.mod.json") {
        expand "version": project.version,
                "minecraft_version": "1.21.1",
                "loader_version": "0.19.2"
    }
}
```

## 常见问题

### 1. 编译错误：找不到客户端类

**原因**: 在公共代码中引用了客户端类（如 `net.minecraft.client.Minecraft`）

**解决**: 启用 `splitEnvironmentSourceSets()` 并将客户端代码移到 `src/client/java`

### 2. 依赖冲突

**原因**: 多个 Mod 依赖同一库的不同版本

**解决**: 使用 `./gradlew dependencies` 查看依赖树，排除冲突版本

### 3. 缓存损坏

**原因**: Gradle 或 Loom 缓存损坏

**解决**: 运行 `./gradlew build --refresh-dependencies`

### 4. 运行时 ClassNotFoundException

**原因**: 依赖未正确打包或重映射

**解决**: 检查 `include` 配置，确保嵌套 JAR 正确配置
