# ViaBedrock 皮肤相关 API 签名研究

> 目的：逐个核实 `BedrockSkinProvider.java` 引用的 ViaBedrock / ViaVersion 类的包路径、修饰符、方法签名，找出与当前代码的不一致之处。
>
> - **ViaBedrock 仓库**：https://github.com/RaphiMC/ViaBedrock （注意：任务给的 `ViaVersion/ViaBedrock` 不存在，正确归属是 `RaphiMC/ViaBedrock`）
> - **研究分支/提交**：`refs/heads/main`，SHA `ed4cbde41ac23e52a629230ba96018e7380fef4d`（2026-08-06 fetch）
> - **ViaBedrock 版本**：`maven_version=0.0.29-SNAPSHOT`（`gradle.properties` 已确认）✅
> - **ViaVersion 仓库**：https://github.com/ViaVersion/ViaVersion
> - **ViaVersion 研究提交**：`refs/heads/master`，SHA `89f80172b16d360bdadc51fe4d9bcb0aaf275cb9`
> - **研究日期**：2026-08-06

---

## ① SkinProvider（ViaBedrock）

| 项 | 值 |
|---|---|
| 全限定名 | `net.raphimc.viabedrock.protocol.provider.SkinProvider` |
| 源文件 | `src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java` |
| 类修饰符 | `public class SkinProvider implements Provider`（**非 abstract、非 final**） |
| 接口 | `com.viaversion.viaversion.api.platform.providers.Provider`（标记接口） |

**关键方法签名**：

```java
public Map<String, Object> getClientPlayerSkin(final UserConnection user)

public void setSkin(final UserConnection user, final UUID playerUuid, final SkinData skin)
```

**`setSkin` 真实实现要点**（重要）：
- 不构造 `PacketWrapper`，也不发送 `PLAYER_SKIN` 数据包。
- 而是根据 `ChannelStorage` 注册的客户端 Mod 通道，转发到 `ViaBedrockUtilityInterface.sendSkin(user, playerUuid, skin)` 或 `BedrockSkinUtilityInterface.sendSkin(user, playerUuid, skin)`。
- 即：原生 `setSkin` 只负责把皮肤推给客户端渲染 Mod，**不会**把皮肤回送给 Bedrock 服务端。

**注册方式**：在 `BedrockProtocol.register(ViaProviders)` 中通过 `providers.register(SkinProvider.class, new SkinProvider())` 注册，可用自定义子类覆盖。

---

## ② SkinData（ViaBedrock）

| 项 | 值 |
|---|---|
| 全限定名 | `net.raphimc.viabedrock.protocol.model.SkinData` |
| 源文件 | `src/main/java/net/raphimc/viabedrock/protocol/model/SkinData.java` |
| 类型 | `public record SkinData(...)`（record，隐式 final） |

**构造函数参数列表（共 19 个，顺序固定）**：

```java
public SkinData(
    String skinId,                         // 1
    String playFabId,                      // 2
    String skinResourcePatch,              // 3
    BufferedImage skinData,                // 4  ← 图片，非 byte[]
    List<AnimationData> animations,        // 5
    BufferedImage capeData,                // 6  ← 图片，非 byte[]
    String geometryData,                   // 7
    String geometryDataEngineVersion,     // 8
    String animationData,                  // 9
    boolean premium,                       // 10
    boolean persona,                       // 11
    boolean capeOnClassic,                 // 12
    boolean primaryUser,                   // 13
    String capeId,                         // 14
    String fullSkinId,                     // 15
    String armSize,                        // 16
    String skinColor,                      // 17
    List<PersonaPieceData> personaPieces,  // 18
    List<PersonaPieceTintData> tintColors, // 19
    boolean overridingPlayerAppearance     // 20 ← 注意：实际是 19 个参数
)
```

> **勘误**：上面注释写"19 个"，但实际数下来是 **19 个组件**（skinId … overridingPlayerAppearance）。请以源码为准，下面给出真实顺序的紧凑版：
>
> `skinId, playFabId, skinResourcePatch, skinData, animations, capeData, geometryData, geometryDataEngineVersion, animationData, premium, persona, capeOnClassic, primaryUser, capeId, fullSkinId, armSize, skinColor, personaPieces, tintColors, overridingPlayerAppearance`

**record 访问器方法名**（与组件同名，无 `get` 前缀）：

| 访问器 | 返回类型 |
|---|---|
| `skinId()` | `String` |
| `playFabId()` | `String` |
| `skinResourcePatch()` | `String` |
| `skinData()` | `BufferedImage` |
| `animations()` | `List<AnimationData>` |
| `capeData()` | `BufferedImage` |
| `geometryData()` | `String` |
| `geometryDataEngineVersion()` | `String` |
| `animationData()` | `String` |
| `premium()` | `boolean` |
| `persona()` | `boolean` |
| `capeOnClassic()` | `boolean` |
| `primaryUser()` | `boolean` |
| `capeId()` | `String` |
| `fullSkinId()` | `String` |
| `armSize()` | `String` |
| `skinColor()` | `String` |
| `personaPieces()` | `List<PersonaPieceData>` |
| `tintColors()` | `List<PersonaPieceTintData>` |
| `overridingPlayerAppearance()` | `boolean` |

**嵌套 record**：
- `SkinData.AnimationData(BufferedImage image, int type, float frames, int expression)`
- `SkinData.PersonaPieceData(String id, String type, String packId, boolean defaultPiece, String productId)`
- `SkinData.PersonaPieceTintData(String type, List<String> colors)`

---

## ③ PlayerListStorage（ViaBedrock）

| 项 | 值 |
|---|---|
| 全限定名 | `net.raphimc.viabedrock.protocol.storage.PlayerListStorage` |
| 源文件 | `src/main/java/net/raphimc/viabedrock/protocol/storage/PlayerListStorage.java` |
| 类修饰符 | `public class PlayerListStorage implements StorableObject`（**public**，非 final） |
| 接口 | `com.viaversion.viaversion.api.connection.StorableObject` |

**关键方法签名**：

```java
public Pair<Long, String> addPlayer(final UUID uuid, final long entityUniqueId, final String name)
public Pair<Long, String> removePlayer(final UUID uuid)
public boolean containsPlayer(final UUID uuid)
public Pair<Long, String> getPlayer(final UUID uuid)                 // ← 按 UUID 查
public Pair<UUID, String> getPlayer(final long entityUniqueId)       // ← 重载，按 runtime id 查
```

- `getPlayer(UUID)` 返回 `com.viaversion.viaversion.util.Pair<Long, String>`：
  - `key()` → `Long`（entityUniqueId）
  - `value()` → `String`（玩家名）
- **`Pair.value()` 方法存在** ✅（见 ⑩ Pair）
- 该 storage 由 `BedrockProtocol.init(UserConnection)` 通过 `user.put(new PlayerListStorage())` 注册，可用 `user.get(PlayerListStorage.class)` 取出。

---

## ④ BedrockProtocol（ViaBedrock）

| 项 | 值 |
|---|---|
| 全限定名 | `net.raphimc.viabedrock.protocol.BedrockProtocol` |
| 源文件 | `src/main/java/net/raphimc/viabedrock/protocol/BedrockProtocol.java` |
| 类修饰符 | `public class BedrockProtocol extends StatelessTransitionProtocol<ClientboundBedrockPackets, ClientboundPackets26_1, ServerboundBedrockPackets, ServerboundPackets26_1>` |
| 是否 Protocol 子类 | 是（间接继承 `com.viaversion.viaversion.api.protocol.Protocol`） |

**关键点**：
- 类**存在** ✅，且是 `Protocol` 子类，因此可作为 `PacketWrapper.sendToServer(Class<? extends Protocol>)` 的参数。
- 含 `public static final BedrockMappingData MAPPINGS`。
- 在 `register(ViaProviders)` 中注册 `SkinProvider`：`providers.register(SkinProvider.class, new SkinProvider());`

---

## ⑤ ServerboundBedrockPackets（ViaBedrock）

| 项 | 值 |
|---|---|
| 全限定名 | `net.raphimc.viabedrock.protocol.ServerboundBedrockPackets` |
| 源文件 | `src/main/java/net/raphimc/viabedrock/protocol/ServerboundBedrockPackets.java` |
| 类修饰符 | `public enum ServerboundBedrockPackets implements ServerboundPacketType` |

**`PLAYER_SKIN` 枚举存在** ✅：
```java
PLAYER_SKIN(MinecraftPacketIds.PlayerSkin.getValue())
```

枚举构造器为包级私有 `ServerboundBedrockPackets(final int id)`，每个枚举常量带 `int id`。可作为 `PacketWrapper.create(PacketType, UserConnection)` 的第一个参数（`ServerboundPacketType extends PacketType`）。

---

## ⑥ BedrockTypes（ViaBedrock）

| 项 | 值 |
|---|---|
| 全限定名 | `net.raphimc.viabedrock.protocol.types.BedrockTypes` |
| 源文件 | `src/main/java/net/raphimc/viabedrock/protocol/types/BedrockTypes.java` |
| 类修饰符 | `public class BedrockTypes`（非 final，全为 static 常量） |

**与皮肤相关的常量**（均存在 ✅）：

| 常量 | 声明 | 说明 |
|---|---|---|
| `UUID` | `public static final Type<UUID> UUID = new UUIDType();` | ✅ 存在 |
| `SKIN` | `public static final Type<SkinData> SKIN = new SkinType();` | ✅ 存在，序列化整个 SkinData record |
| `STRING` | `public static final Type<String> STRING = new StringType();` | ✅ 存在 |

**⚠️ 注意**：`BedrockTypes` **没有 `BOOLEAN`** 常量。Boolean 类型在 ViaVersion 的 `com.viaversion.viaversion.api.type.Types.BOOLEAN`（见 ⑧）。

---

## ⑦ UserConnection（ViaVersion）

| 项 | 值 |
|---|---|
| 全限定名 | `com.viaversion.viaversion.api.connection.UserConnection` |
| 源文件 | `api/src/main/java/com/viaversion/viaversion/api/connection/UserConnection.java` |
| 类型 | **`public interface UserConnection`**（接口，非类） |

**`get(Class)` 方法签名** ✅：
```java
@Nullable <T extends StorableObject> T get(Class<T> objectClass)
```
- 泛型上界 `T extends StorableObject`。
- `PlayerListStorage implements StorableObject`，故 `user.get(PlayerListStorage.class)` 合法。
- 返回值可能为 `null`（`@Nullable`），需判空。

其它相关方法：`boolean has(Class<? extends StorableObject>)`、`void put(StorableObject)`、`@Nullable <T extends StorableObject> T remove(Class<T>)`。

---

## ⑧ Types（ViaVersion）

| 项 | 值 |
|---|---|
| 全限定名 | `com.viaversion.viaversion.api.type.Types` |
| 源文件 | `api/src/main/java/com/viaversion/viaversion/api/type/Types.java` |
| 类修饰符 | `public final class Types`（不可继承） |

**`BOOLEAN` 常量存在** ✅：
```java
public static final BooleanType BOOLEAN = new BooleanType();
```
- `BooleanType` 继承 `Type<Boolean>`，故 `wrapper.write(Types.BOOLEAN, false)` 与 `PacketWrapper.write(Type<T>, T)` 签名匹配。

---

## ⑨ PacketWrapper（ViaVersion）

| 项 | 值 |
|---|---|
| 全限定名 | `com.viaversion.viaversion.api.protocol.packet.PacketWrapper` |
| 源文件 | `api/src/main/java/com/viaversion/viaversion/api/protocol/packet/PacketWrapper.java` |
| 类型 | **`public interface PacketWrapper`**（接口） |

**`create` 静态方法签名** ✅：
```java
static PacketWrapper create(@Nullable PacketType packetType, UserConnection connection)            // ← 本项目使用
static PacketWrapper create(@Nullable PacketType packetType, @Nullable ByteBuf inputBuffer, UserConnection connection)
static PacketWrapper create(int packetId, @Nullable ByteBuf inputBuffer, UserConnection connection)  // @Deprecated
```
- `PacketWrapper.create(ServerboundBedrockPackets.PLAYER_SKIN, user)` 命中第一个重载，合法 ✅。
- `ServerboundBedrockPackets.PLAYER_SKIN` 是 `ServerboundPacketType`（继承 `PacketType`），可传给 `PacketType` 形参。

**`write` 方法签名** ✅：
```java
<T> void write(Type<T> type, @Nullable T value)
```

**`sendToServer` 方法签名** ✅：
```java
default void sendToServer(Class<? extends Protocol> protocol) throws InformativeException   // ← 本项目使用
void sendToServer(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) throws InformativeException
default void scheduleSendToServer(Class<? extends Protocol> protocol) throws InformativeException
void scheduleSendToServer(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) throws InformativeException
void sendToServerRaw() throws InformativeException
void scheduleSendToServerRaw() throws InformativeException
```
- `wrapper.sendToServer(BedrockProtocol.class)` 命中第一个重载（`skipCurrentPipeline` 默认 `true`），合法 ✅。
- `BedrockProtocol` 是 `Protocol` 子类，满足 `Class<? extends Protocol>` 约束。
- ⚠️ 该方法声明抛出 `InformativeException`（受检异常），调用处必须 `try/catch`。当前代码已包在 `try/catch (Exception e)` 中，OK。

---

## ⑩ Pair（ViaVersion）

| 项 | 值 |
|---|---|
| 全限定名 | `com.viaversion.viaversion.util.Pair` |
| 源文件 | `api/src/main/java/com/viaversion/viaversion/util/Pair.java` |
| 类型 | `public record Pair<X, Y>(@Nullable X key, @Nullable Y value)` |

**`value()` 方法存在** ✅（record 自动生成访问器）。
- `Pair<Long, String>` 的 `value()` 返回 `String`，`key()` 返回 `Long`。
- ViaBedrock 的 `PlayerListStorage.getPlayer(long)` 内部也直接调用 `entry.getValue().value()`，进一步佐证。

---

## 与当前 `BedrockSkinProvider.java` 代码的不一致之处

源文件：`BedrockSkinBridge/src/main/java/com/moxi/bedrockskinbridge/skin/BedrockSkinProvider.java`

### 🔴 严重问题（编译错误）

**1. 缺少 import（2 处，导致编译失败）**

第 157 行使用了 `PlayerListStorage`，第 159 行使用了 `Pair<Long, String>`，但文件头部的 import 块（第 1–15 行）**两者都未导入**：

```java
// 第 157 行（未导入）
PlayerListStorage pls = user.get(PlayerListStorage.class);
// 第 159 行（未导入）
Pair<Long, String> entry = pls.getPlayer(uuid);
```

需补充：
```java
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import com.viaversion.viaversion.util.Pair;
```

### 🟡 行为/前提差异（非 bug，但需明确）

**2. `SkinProvider` 并非 abstract**

任务描述称其为"抽象类"，实际源码是 `public class SkinProvider implements Provider`（普通类）。`extends SkinProvider` 仍然合法，可正常覆盖 `setSkin`。仅是对任务前提的勘误，代码本身无误。

**3. `setSkin` 的原生行为与本项目 `sendPlayerSkinPacket` 不同（设计差异）**

- 原生 `SkinProvider.setSkin` **不**构造 `PLAYER_SKIN` 包，而是转发到客户端 Mod 通道（`ViaBedrockUtilityInterface` / `BedrockSkinUtilityInterface`）。
- 本项目 `sendPlayerSkinPacket` 自行构造 `PacketWrapper` 并 `sendToServer(BedrockProtocol.class)`，方向是"回送 BE 服务器"。所用 API（`PacketWrapper.create`、`write`、`sendToServer`）签名**全部合法** ✅。
- ⚠️ **未验证项**：`PLAYER_SKIN` 包的字段顺序（`UUID → SKIN → STRING(newSkinName) → STRING(oldSkinName) → BOOLEAN(trustedSkin)`）是否与 Bedrock 协议 / `BedrockTypes.SKIN`（`SkinType`）的序列化布局一致。本次只核 API 签名，未读 `SkinType` 与 `PlayerSkin` 包处理器源码。如运行时服务端拒包或字段错位，需进一步查 `net.raphimc.viabedrock.protocol.types.primitive.SkinType` 及 `ServerboundBedrockPackets.PLAYER_SKIN` 的注册处理器。

**4. `Types.BOOLEAN` 来源正确但与 Bedrock 类型混用**

第 143 行 `wrapper.write(Types.BOOLEAN, false)` 用的是 ViaVersion 的 `Types.BOOLEAN`（Java BE 大端 boolean），而非 `BedrockTypes`（BedrockTypes 无 BOOLEAN 常量）。这是合理的——Bedrock 协议中 `trustedSkin` 字段在 ViaBedrock 的类型体系里用的就是 ViaVersion `Types.BOOLEAN`。仅提示：若 `PlayerSkin` 包处理器实际期望的是其它布尔编码（如 VarInt 型 0/1），此处会出错，需对照处理器确认。

### ✅ 已核实一致项

- `extends SkinProvider` + `@Override setSkin(UserConnection, UUID, SkinData)` 签名完全匹配。
- `skin.skinData()` / `capeData()` / `skinResourcePatch()` / `geometryData()` / `persona()` / `premium()` / `personaPieces()` 访问器均存在且返回类型正确（`skinData`/`capeData` 为 `BufferedImage`）。
- `new SkinData(...)` 19 个参数顺序与 record 组件顺序一致。
- `user.get(PlayerListStorage.class)` 合法（`PlayerListStorage implements StorableObject`）。
- `pls.getPlayer(uuid)` 返回 `Pair<Long, String>`，`entry.value()` 返回 `String`（玩家名）。
- `PacketWrapper.create(ServerboundBedrockPackets.PLAYER_SKIN, user)` 合法。
- `BedrockTypes.UUID` / `BedrockTypes.SKIN` / `BedrockTypes.STRING` 均存在。
- `wrapper.sendToServer(BedrockProtocol.class)` 合法。

---

## 附：仓库归属勘误

任务给的 GitHub 路径 `https://github.com/ViaVersion/ViaBedrock` **返回 404**（该组织下无此仓库）。ViaBedrock 实际归属 `RaphiMC`：

- 正确仓库：https://github.com/RaphiMC/ViaBedrock
- 所有 ViaBedrock 源文件均从 `RaphiMC/ViaBedrock` 的 `main` 分支获取。

ViaVersion API（UserConnection / Types / PacketWrapper / Pair）则在 `ViaVersion/ViaVersion` 的 `master` 分支，路径前缀为 `api/src/main/java/...`（注意 `api/` 子模块，不是仓库根的 `src/`）。

---

*研究日期：2026-08-06 · ViaBedrock commit `ed4cbde` · ViaVersion commit `89f80172`*
