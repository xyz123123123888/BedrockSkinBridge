# Minecraft 26.2 Fabric Loom Mappings 获取方案研究

研究日期：2026-08-06
目标版本：Minecraft 26.2（Mojang 2026 年度版本号方案）
构建工具：Fabric Loom 1.16.2、Gradle 9.5.1、JDK 25

---

## ① 问题陈述

项目为 Fabric Mod，目标 Minecraft 26.2。当前 `build.gradle` 配置：

```groovy
minecraft "com.mojang:minecraft:26.2"
mappings loom.officialMojangMappings()
```

已知 26.2 的 version JSON（来自 launchermeta.mojang.com / piston-meta.mojang.com）`downloads` 字段只有 `client` 和 `server`，**没有 `client_mappings` 字段**。这导致 `loom.officialMojangMappings()` 可能无法获取 ProGuard mappings 而失败。

需要研究并验证：
1. Mojang 是否在 26.2 停止发布 ProGuard client_mappings？
2. Fabric 官方如何为 26.2 提供/声明 mappings？
3. Loom 1.16.2 的 `officialMojangMappings()` 在 version JSON 无 client_mappings 时会怎样？
4. `build.gradle` 中 `mappings` 行应该怎么写？

---

## ② 已验证的事实

### 事实 1：26.2 version JSON 确实没有 client_mappings / server_mappings

直接通过 `Invoke-RestMethod` 解析了 26.2 的 version JSON：

- URL: https://piston-meta.mojang.com/v1/packages/4b74f58f68a2baae3547d5a20274079f29cafc06/26.2.json
- `id` = `26.2`
- `type` = `release`
- `javaVersion.majorVersion` = `25`（JDK 25）
- `assets` = `32`
- `downloads` 字段的 keys：**只有 `client` 和 `server`**
- `downloads.client_mappings`：**MISSING**
- `downloads.server_mappings`：**MISSING**

### 事实 2：Mojang 官方宣布移除混淆（2025-10-29）

Mojang 官方文章《Removing obfuscation in Java Edition》（发布于 2025-10-29）明确说明：

> "Starting with the first snapshot following the complete Mounts of Mayhem launch, we will no longer obfuscate Minecraft: Java Edition."
>
> "No more obfuscation maps in version .jsons – as they're no longer needed"
>
> "The client and server .jar files won't be obfuscated"

- URL: https://www.minecraft.net/en-us/article/removing-obfuscation-in-java-edition
- *Mounts of Mayhem* = 1.21.11（最后一个混淆版本）
- 1.21.11 之后的版本（即 26.x 系列）不再混淆，version JSON 不再包含 obfuscation maps，jar 文件本身也不再混淆。

### 事实 3：26.1 是第一个非混淆版本，包含参数名

Fabric 官方文档《Migrating Mappings 26.2》明确指出：

> "Minecraft: Java Edition was obfuscated from its release until 1.21.11"
>
> "Minecraft 26.1 is unobfuscated and includes parameter names, so there is no need for any obfuscation mappings."

- URL: https://docs.fabricmc.net/develop/porting/mappings/
- 26.1 = 第一个非混淆版本（对应 Loom 1.15 release notes 中的 "Minecraft 26.1 (1.21.4)"，即新版本号方案下 26.1 对应旧的 1.21.4 之后的版本）
- 26.2 同属非混淆版本，游戏代码本身已使用 Mojang 官方名称，且包含参数名与本地变量名。

### 事实 4：Fabric 官方宣布不再维护 Yarn（2025-10-31）

Fabric 官方博客《Removing Obfuscation from Fabric》（2025-10-31）：

> "the Fabric Project made the decision to not keep maintaining third-party mappings from this version onward"
>
> "Intermediary will no longer exist; the game will use Mojang's names at runtime."

- URL: https://fabricmc.net/2025/10/31/obfuscation.html
- Yarn（2016-2025）正式终止维护，不再为 26.1+ 提供新版本。

### 事实 5：Yarn maven 仓库没有任何 26.x 版本

直接解析了 Yarn 的 maven-metadata.xml：

- URL: https://maven.fabricmc.net/net/fabricmc/yarn/maven-metadata.xml
- `<latest>` = `1.21.11+build.6`
- `<release>` = `1.21.11+build.6`
- 总版本数：3412
- 匹配 `26.*` 的版本数：**0**
- 匹配 `25.*` 的版本数：**0**
- 最后 10 个版本全部是 `1.21.11` 系列

结论：`net.fabricmc:yarn:26.2` **不存在**，无法使用 Yarn mappings。

### 事实 6：Loom 1.14 引入非混淆版本的 plugin ID

Loom 1.14 release notes：

> "Initial support for the non-obfuscated versions of Minecraft"
>
> New plugin IDs:
> - `net.fabricmc.fabric-loom` — 用于**非混淆版本**（26.1+）
> - `net.fabricmc.fabric-loom-remap` — 用于**混淆版本**（1.21.11 及以下）
> - `fabric-loom` — 保留向后兼容
>
> "When you use the new plugin ID for non-obfuscated versions, loom will skip configuring everything related to remapping."
>
> "This will come after 1.21.11 which we expect to be the last obfuscated version."

- URL: https://github.com/FabricMC/fabric-loom/releases（tag 1.14）
- Loom 1.16.2 继承了这一设计，`net.fabricmc.fabric-loom` 适用于 26.2。

### 事实 7：Fabric API 官方 build.gradle 用 Loom 1.16.2 + 26.2，且没有 mappings 行

Fabric API 仓库（HEAD）的 `gradle.properties`：

```
minecraft_version=26.2
loader_version=0.18.4
version=0.156.0
```

- gradle.properties URL: https://raw.githubusercontent.com/FabricMC/fabric/HEAD/gradle.properties

Fabric API 根 `build.gradle`（使用 `net.fabricmc.fabric-loom` 1.16.2）的 subproject dependencies：

```groovy
dependencies {
    minecraft "com.mojang:minecraft:$rootProject.minecraft_version"
    api "net.fabricmc:fabric-loader:${project.loader_version}"
    // ... 没有 mappings 行
}
```

- build.gradle URL: https://raw.githubusercontent.com/FabricMC/fabric/HEAD/build.gradle
- plugin 声明：`id "net.fabricmc.fabric-loom" version "1.16.2" apply false`
- **没有任何 `mappings` 声明**，因为 26.2 是非混淆版本，不需要 mappings。

### 事实 8：fabric-example-mod（官方模板）也没有 mappings 行

fabric-example-mod（HEAD）的 `build.gradle`：

```groovy
plugins {
    id 'net.fabricmc.fabric-loom' version "${loom_version}"
    // ...
}
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc:fabric-api:fabric-api:${project.fabric_api_version}"
    // 没有 mappings 行
}
```

- build.gradle URL: https://raw.githubusercontent.com/FabricMC/fabric-example-mod/HEAD/build.gradle
- gradle.properties：`minecraft_version=26.1.2`，`loom_version=1.17-SNAPSHOT`
- 注意：非混淆版本下依赖用 `implementation`（而非 `modImplementation`），因为不需要 remap。

### 事实 9：Loom `MojangMappingLayer` 源码确认需要 client_mappings 文件

Loom `dev/1.17` 分支的 `MojangMappingLayer.java`：

```java
public record MojangMappingLayer(Path clientMappings, Path serverMappings, ...) implements MappingLayer {
    private void readMappings(MappingVisitor mappingVisitor) throws IOException {
        // ...
        try (BufferedReader clientBufferedReader = Files.newBufferedReader(clientMappings, ...);
                BufferedReader serverBufferedReader = Files.newBufferedReader(serverMappings, ...)) {
            ProGuardFileReader.read(clientBufferedReader, ...);
            ProGuardFileReader.read(serverBufferedReader, ...);
        }
    }
}
```

- URL: https://github.com/FabricMC/fabric-loom/blob/dev/1.17/src/main/java/net/fabricmc/loom/configuration/providers/mappings/mojmap/MojangMappingLayer.java
- 该类需要 `clientMappings` 和 `serverMappings` 两个文件路径，用 `ProGuardFileReader` 读取 ProGuard 格式 mappings。
- 当 version JSON 没有 `client_mappings` 时，Loom 无法下载该文件，`officialMojangMappings()` 会失败（无法获取 ProGuard mappings 的下载 URL）。

### 事实 10：Loom 有专门的非混淆版本支持

Loom 源码树中存在以下与非混淆版本相关的文件：

- `src/main/java/net/fabricmc/loom/configuration/providers/mappings/tiny/UnobfuscatedMappingNsCompleter.java` — 非混淆版本的 mapping namespace completer
- `src/test/resources/mappings/25w46a_unobfuscated-intermediary-minimal.tiny` — 非混淆版本测试资源
- `src/test/resources/mappings/25w46a_unobfuscated-named-minimal.tiny`

这证实 Loom 从 25w46a snapshot 起就开始支持非混淆版本。

---

## ③ 结论

1. **Mojang 在 26.2 确实停止发布 ProGuard client_mappings**。这不是 bug，而是 Mojang 的官方决策：从 1.21.11 之后的版本（26.x 系列）彻底移除混淆，version JSON 不再包含 `client_mappings`/`server_mappings`，client/server jar 本身也不再混淆。Mojang 官方公告明确说 "No more obfuscation maps in version .jsons – as they're no longer needed"。

2. **Fabric 官方不为 26.2 提供 Yarn mappings**。Fabric 已正式终止 Yarn 维护（2025-10-31 博客），Yarn maven 仓库没有任何 26.x 版本（最新仅到 1.21.11+build.6）。

3. **26.2 不需要任何 mappings**。因为游戏 jar 本身就是非混淆的，类名/方法名/字段名已是 Mojang 官方名称，且包含参数名和本地变量名。Fabric 官方文档明确说 "Minecraft 26.1 is unobfuscated and includes parameter names, so there is no need for any obfuscation mappings."

4. **`loom.officialMojangMappings()` 不适用于 26.2**。该方法依赖 version JSON 中的 `client_mappings`/`server_mappings` 下载 ProGuard mappings 文件，而 26.2 没有这些字段，调用会失败。该方法仅适用于 1.21.11 及以下的混淆版本。

5. **Fabric 官方的做法是使用 `net.fabricmc.fabric-loom` plugin（非混淆版本）且不声明 mappings**。Fabric API（Loom 1.16.2 + MC 26.2）和 fabric-example-mod 的 build.gradle 都没有 `mappings` 行。

6. **plugin ID 必须是 `net.fabricmc.fabric-loom`**（非混淆版本）。旧的 `fabric-loom` ID 虽然保留向后兼容，但会启用 remapping 流程，不适合 26.2。`net.fabricmc.fabric-loom` 会跳过所有 remapping 相关配置。

---

## ④ build.gradle 的 mappings 配置建议

### 推荐方案：使用非混淆 plugin，移除 mappings 行

参考 Fabric API 和 fabric-example-mod 的官方配置，26.2 下应使用 `net.fabricmc.fabric-loom` plugin 并**完全移除 `mappings` 行**：

```groovy
plugins {
    id 'net.fabricmc.fabric-loom' version '1.16.2'
    // 注意：不是旧的 'fabric-loom'，也不是 'net.fabricmc.fabric-loom-remap'
}

dependencies {
    minecraft "com.mojang:minecraft:26.2"
    // 不需要 mappings 行 —— 26.2 是非混淆版本，游戏自带 Mojang 官方名称
    modImplementation "net.fabricmc:fabric-loader:0.18.4"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.156.0+26.2"
}
```

### 关键变更说明

| 项目 | 旧配置（混淆版本，≤1.21.11） | 新配置（非混淆版本，≥26.1） |
|------|------|------|
| plugin ID | `fabric-loom` | `net.fabricmc.fabric-loom` |
| mappings 行 | `mappings loom.officialMojangMappings()` 或 `mappings "net.fabricmc:yarn:..."` | **删除**（不需要） |
| 依赖声明 | `modImplementation`（需要 remap） | `modImplementation` 或 `implementation` |

### 如果之前使用的是 Yarn

需要先迁移源码到 Mojang mappings（使用 `migrateMappings` 任务），再更新 build.gradle：

```bash
# 在旧版本上迁移源码到 Mojang mappings（必须在升级到 26.2 之前完成）
./gradlew migrateMappings --mappings "net.minecraft:mappings:1.21.11" --overrideInputsIHaveABackup
```

迁移指南见：https://docs.fabricmc.net/develop/porting/mappings/loom

### 三选一结论

| 方案 | 是否可行 | 说明 |
|------|------|------|
| Mojang 官方 mappings（`loom.officialMojangMappings()`） | ❌ 不可行 | 26.2 version JSON 没有 client_mappings，且非混淆版本不需要 |
| Yarn（`net.fabricmc:yarn:26.2`） | ❌ 不可行 | Yarn 没有发布 26.2 版本，且已停止维护 |
| **不使用 mappings（非混淆 plugin）** | ✅ 推荐方案 | 26.2 自带 Mojang 官方名称，用 `net.fabricmc.fabric-loom` plugin 即可 |

---

## 参考链接

- Mojang 官方公告：https://www.minecraft.net/en-us/article/removing-obfuscation-in-java-edition
- Fabric 官方博客：https://fabricmc.net/2025/10/31/obfuscation.html
- Fabric 迁移文档：https://docs.fabricmc.net/develop/porting/mappings/
- Fabric Loom 迁移指南：https://docs.fabricmc.net/develop/porting/mappings/loom
- 26.2 version JSON：https://piston-meta.mojang.com/v1/packages/4b74f58f68a2baae3547d5a20274079f29cafc06/26.2.json
- Yarn maven-metadata：https://maven.fabricmc.net/net/fabricmc/yarn/maven-metadata.xml
- Fabric API build.gradle：https://github.com/FabricMC/fabric/blob/HEAD/build.gradle
- Fabric API gradle.properties：https://github.com/FabricMC/fabric/blob/HEAD/gradle.properties
- fabric-example-mod build.gradle：https://github.com/FabricMC/fabric-example-mod/blob/HEAD/build.gradle
- Loom MojangMappingLayer 源码：https://github.com/FabricMC/fabric-loom/blob/dev/1.17/src/main/java/net/fabricmc/loom/configuration/providers/mappings/mojmap/MojangMappingLayer.java
- Loom releases：https://github.com/FabricMC/fabric-loom/releases
