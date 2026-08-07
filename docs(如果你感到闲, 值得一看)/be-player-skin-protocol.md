# Minecraft Bedrock Edition 1.26.30 — PlayerSkin / PlayerList 协议结构研究

> 研究目的：为 Fabric Mod（让 JE 玩家看到 BE 玩家皮肤）提供精确的 BE 服务器→客户端皮肤推送协议规格。
>
> 数据来源：ViaBedrock 源码（RaphiMC/ViaBedrock，`main` 分支，commit `ed4cbde4`），对应 BE 1.26.30 协议。所有源码引用均带 URL + 行号。
>
> **重要更正**：任务描述中提到的 `PlayerPackets.java` 在当前 ViaBedrock 仓库中**不存在**。`packet` 目录下的实际文件为 `ClientPlayerPackets.java`、`OtherPlayerPackets.java`、`HudPackets.java` 等（共 15 个）。`PLAYER_SKIN` (0x6D) 的客户端包处理器实际位于 **`OtherPlayerPackets.java`**；`PLAYER_LIST` 处理器位于 **`HudPackets.java`**。

---

## ① PlayerSkin 数据包 (ID 0x6D) 完整字段

### 1.1 包处理器位置

源码：[`OtherPlayerPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java) 第 160–168 行

```java
protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_SKIN, null, wrapper -> {
    wrapper.cancel();
    final UUID uuid = wrapper.read(BedrockTypes.UUID); // uuid
    final SkinData skin = wrapper.read(BedrockTypes.SKIN); // skin
    wrapper.read(BedrockTypes.STRING); // new skin name
    wrapper.read(BedrockTypes.STRING); // old skin name
    wrapper.read(Types.BOOLEAN); // trusted skin
    Via.getManager().getProviders().get(SkinProvider.class).setSkin(wrapper.user(), uuid, skin);
});
```

> 注意：ViaBedrock 在 BE→JE 翻译时 `wrapper.cancel()` 掉此包（不直接转发给 JE 客户端），而是把皮肤存入 `SkinProvider`，再由其它机制渲染。但对 BE 原生客户端，这个包是直接送达的。下面记录的是 **BE 原生线路格式**（即 `wrapper.read` 读取的顺序与类型）。

### 1.2 PlayerSkin 包字段表（按线路顺序）

| # | 字段 | 类型 | 说明 | 源码行 |
|---|------|------|------|--------|
| 1 | `uuid` | `UUID`（LE, 16 字节） | 目标玩家的 UUID | OtherPlayerPackets.java:162 |
| 2 | `skin` | `SkinData`（见 1.3） | 完整皮肤数据 | OtherPlayerPackets.java:163 |
| 3 | `newSkinName` | `String`（VarUInt 长度前缀 + UTF-8） | 新皮肤名 | OtherPlayerPackets.java:164 |
| 4 | `oldSkinName` | `String` | 旧皮肤名 | OtherPlayerPackets.java:165 |
| 5 | `trustedSkin` | `Boolean`（1 字节） | 是否为受信任皮肤（Xbox Live 签名校验通过） | OtherPlayerPackets.java:166 |

### 1.3 SkinData 完整结构

`BedrockTypes.SKIN` 即 `SkinType` 实例。源码：[`SkinType.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/model/SkinType.java)。数据模型：[`SkinData.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/model/SkinData.java)。

`SkinData` 是一个 record（SkinData.java:14-20）：

```java
public record SkinData(
    String skinId, String playFabId, String skinResourcePatch,
    BufferedImage skinData, List<AnimationData> animations, BufferedImage capeData,
    String geometryData, String geometryDataEngineVersion, String animationData,
    boolean premium, boolean persona, boolean capeOnClassic, boolean primaryUser,
    String capeId, String fullSkinId, String armSize, String skinColor,
    List<PersonaPieceData> personaPieces, List<PersonaPieceTintData> tintColors,
    boolean overridingPlayerAppearance
)
```

**线路读取顺序**（SkinType.java `read` 方法，逐字段）：

| # | 字段 | 线路类型 | 备注 | SkinType.java 行 |
|---|------|----------|------|------------------|
| 1 | `skinId` | `String` | 皮肤标识（如 `ge` Persona / 自定义皮肤 ID） | L36 |
| 2 | `playFabId` | `String` | PlayFab 关联 ID | L37 |
| 3 | `skinResourcePatch` | `String` | 资源补丁 JSON（几何/纹理路径覆盖） | L38 |
| 4 | `skinData` | `IMAGE` | 主体皮肤 PNG 位图（`ImageType`，含宽高+像素数据） | L39 |
| 5 | `animations` | 数组 | 动画列表，先 `intLE` 数量，再逐项 | L40-48 |
| 5a | ↳ `image` | `IMAGE` | 动画帧 PNG | L42 |
| 5b | ↳ `type` | `intLE` | 动画类型 | L43 |
| 5c | ↳ `frames` | `floatLE` | 帧数 | L44 |
| 5d | ↳ `expression` | `intLE` | 表情类型 | L45 |
| 6 | `capeData` | `IMAGE` | 披风 PNG（可为 0x0 空图） | L49 |
| 7 | `geometryData` | `String` | 几何模型 JSON（自定义模型） | L50 |
| 8 | `geometryDataEngineVersion` | `String` | 几何引擎版本 | L51 |
| 9 | `animationData` | `String` | 动画控制器 JSON | L52 |
| 10 | `capeId` | `String` | 披风标识 | L53 |
| 11 | `fullSkinId` | `String` | 完整皮肤 ID | L54 |
| 12 | `armSize` | `String` | 手臂尺寸（`wide`/`slim`） | L55 |
| 13 | `skinColor` | `String` | 皮肤底色（十六进制 `#RRGGBB`） | L56 |
| 14 | `personaPieces` | 数组 | Persona 部件列表，先 `intLE` 数量，再逐项 | L57-65 |
| 14a | ↳ `id` | `String` | 部件 ID | L59 |
| 14b | ↳ `type` | `String` | 部件类型（如 `cape`/`jacket`/`face`...） | L60 |
| 14c | ↳ `packId` | `String` | 来源包 ID | L61 |
| 14d | ↳ `defaultPiece` | `boolean` | 是否默认部件 | L62 |
| 14e | ↳ `productId` | `String` | 商店产品 ID | L63 |
| 15 | `tintColors` | 数组 | 部件染色列表，先 `intLE` 数量，再逐项 | L66-76 |
| 15a | ↳ `type` | `String` | 染色部件类型 | L68 |
| 15b | ↳ `colors` | `String` 数组 | 先 `intLE` 颜色数，再多个 `String` 颜色值 | L69-73 |
| 16 | `premium` | `boolean` | 是否付费皮肤 | L77 |
| 17 | `persona` | `boolean` | 是否 Persona（角色编辑器）皮肤 | L78 |
| 18 | `capeOnClassic` | `boolean` | 经典皮肤是否带披风 | L79 |
| 19 | `primaryUser` | `boolean` | 是否主用户 | L80 |
| 20 | `overridingPlayerAppearance` | `boolean` | 是否覆盖玩家外观 | L81 |

> 关键类型定义见 [`BedrockTypes.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/BedrockTypes.java)：
> - `STRING`（L50）= VarUInt 长度前缀 + UTF-8 字节
> - `UUID`（L54）= LE 16 字节
> - `IMAGE`（L53）= `ImageType`：`intLE width` + `intLE height` + `intLE size` + 原始 PNG 字节
> - 注意 BE 用 **小端（LE）** 整数，与 JE 的 BE（大端）不同

---

## ② PlayerList 包 (Add 动作) 中皮肤如何传递

### 2.1 处理器位置

源码：[`HudPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java) 第 55–144 行（`PLAYER_LIST` → JE `PLAYER_INFO_UPDATE`）。

### 2.2 Add 动作单条 entry 读取顺序（HudPackets.java:64–96）

每个 entry 的 BE 线路字段（按读取顺序）：

| # | 字段 | 类型 | 源码行 |
|---|------|------|--------|
| 1 | `uuid` | `UUID` | L72 |
| 2 | `entityUniqueId` | `VarLong` | L75 |
| 3 | `username` | `String` | L76（存入 `names[i]`） |
| 4 | `xuid` | `String` | L77 |
| 5 | `platformOnlineId` | `String` | L78 |
| 6 | `deviceOs` | `intLE` | L79 |
| 7 | `skin` | `SkinData`（`BedrockTypes.SKIN`） | **L80** |
| 8 | `isTeacher` | `boolean` | L81 |
| 9 | `isHost` | `boolean` | L82 |
| 10 | `isSubClient` | `boolean` | L83 |
| 11 | `color` | `intLE`（ARGB） | L84（读取后丢弃） |

**所有 entry 读完后**，再统一读取每个 entry 的 `trustedSkin`（HudPackets.java:98–102）：

```java
for (int i = 0; i < length; i++) {
    wrapper.read(Types.BOOLEAN); // trusted skin
}
```

### 2.3 与 PlayerSkin 包是否同一份皮肤数据？

**是。** PlayerList Add 的 entry 第 7 字段 `skin` 用的就是 `BedrockTypes.SKIN`（即 `SkinType`），与 PlayerSkin 包（OtherPlayerPackets.java:163）读的是**完全相同的 SkinData 结构**。读取后同样调用 `SkinProvider.setSkin(...)`（HudPackets.java:95）。

> 因此 BE 服务器在玩家加入时通过 PlayerList Add 推送一次完整皮肤；后续玩家换肤时通过 PlayerSkin (0x6D) 推送更新。两者皮肤字段结构一致。

---

## ③ displayName 字段的来源

### 3.1 关键代码

HudPackets.java:94：

```java
wrapper.write(Types.OPTIONAL_TAG, TextUtil.stringToNbt(names[i])); // display name
```

### 3.2 `names[i]` 从哪来

HudPackets.java:76：

```java
names[i] = wrapper.read(BedrockTypes.STRING); // username
```

即 `names[i]` 直接来自 **BE 服务器在 PlayerList Add 包中推送的 `username` 字段**（entry 第 3 字段）。

### 3.3 结论

`displayName` = BE 服务器原始推送的**真实用户名**（对 Xbox Live 玩家即 gamertag，对离线/局域网玩家即其自报用户名）。ViaBedrock 仅做了 `String → NBT` 的封装（`TextUtil.stringToNbt`），**未做任何改写、翻译或过滤**。

### 3.4 重要的差异：GameProfile.name ≠ displayName

注意 HudPackets.java:74：

```java
wrapper.write(Types.STRING, StringUtil.encodeUUID(uuids[i])); // username
```

JE `PLAYER_INFO_UPDATE` 的 **GameProfile.name** 字段写入的是 `StringUtil.encodeUUID(uuid)`（UUID 编码字符串），**不是**真实用户名；而 **displayName** 才是真实用户名。这是 ViaBedrock 的设计选择（避免 JE 端名字冲突/校验问题）。Fabric Mod 若要拿到真实 BE 用户名，应取 displayName，而非 GameProfile.name。

---

## ④ JE 玩家自己在 BE 服务器中的 PlayerInfo

### 4.1 BE 服务器是否在 PlayerList 中包含 JE 玩家自己？

**是。** 在 BE 协议中，服务器在玩家完成登录/spawn 阶段后会发送 `PlayerList` (Add) 包，列出当前在线所有玩家，**包含刚加入的玩家本人**。ViaBedrock 的 PlayerList Add 处理器（HudPackets.java:64–124）对**所有 entry 一视同仁**地处理——没有任何针对"自己 UUID"的跳过逻辑。因此 JE 玩家自己也会收到一条属于自己的 PlayerList Add entry，并被翻译成 JE 的 `PLAYER_INFO_UPDATE`。

### 4.2 JE 玩家自己的 GameProfile 怎么来？

由 ViaBedrock 在 PlayerList Add 翻译时构造（HudPackets.java:71–96），字段如下：

| JE GameProfile 字段 | 取值 | 源码行 |
|---------------------|------|--------|
| `uuid` | BE 服务器分配的玩家 UUID（entry 第 1 字段） | L72–73 |
| `name` | `StringUtil.encodeUUID(uuid)`（UUID 编码字符串，**非真实名**） | L74 |
| `properties` | 含 `xuid`、`platform_online_id`、`device_os`、`is_teacher`、`is_host`、`is_subclient` | L85–92 |
| `listed` | `true` | L93 |
| `displayName` | BE 推送的真实用户名 | L94 |

### 4.3 UUID 是 BE 服务器分配的吗？

**是。** BE 服务器在登录流程中为每个玩家分配 UUID（基于 Xbox Live XUID 派生，或离线模式随机生成）。PlayerList Add 的 `uuid` 字段（HudPackets.java:72）即为此 BE 分配的 UUID，ViaBedrock 直接透传给 JE 客户端作为 GameProfile.uuid。

> 因此：JE 玩家经 ViaBedrock 加入 BE 服务器后，自己看到的"我"的 GameProfile.uuid = BE 分配的 UUID；GameProfile.name = UUID 编码字符串；Tab 列表显示名 = BE 真实用户名。皮肤则通过 SkinProvider 在 BE→JE 渲染层注入，不走 JE 标准 textures property。

### 4.4 自己的皮肤如何上传给 BE 服务器

JE 玩家自己的皮肤是**主动上传**方向，不在本研究的 BE→客户端推送范围内。仅记录入口：登录流程中 ViaBedrock 用 `SkinProvider.getClientPlayerSkin(...)` 生成 `SkinData` 并签名成 JWT（见 [`LoginPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/LoginPackets.java) 第 159–166 行，`ClientToServerHandshake` 处理中构造 `skinJwt`）。BE 服务器收到后，再通过 PlayerList Add / PlayerSkin 包回推给所有客户端（含自己）。

---

## ⑤ SkinData 在 PlayerSkin vs PlayerList Add 的字段差异

### 5.1 SkinData 本身：完全相同

两处都通过 `BedrockTypes.SKIN`（`SkinType`）读写（OtherPlayerPackets.java:163 与 HudPackets.java:80）。**SkinData 的 20 个字段在线路上完全一致，没有任何字段差异。**

### 5.2 包级别差异

| 维度 | PlayerSkin (0x6D) | PlayerList Add |
|------|-------------------|----------------|
| SkinData 结构 | 完整 SkinData（20 字段） | 完整 SkinData（20 字段） |
| SkinData 之外的字段 | `uuid` + `newSkinName` + `oldSkinName` + `trustedSkin` | `uuid` + `entityUniqueId` + `username` + `xuid` + `platformOnlineId` + `deviceOs` + SkinData + `isTeacher` + `isHost` + `isSubClient` + `color`；末尾统一 `trustedSkin` 数组 |
| `trustedSkin` 位置 | 紧跟在 `oldSkinName` 之后（包末尾，单条） | 在**所有 entry** 读完之后，按 entry 顺序统一读取（HudPackets.java:98–102） |
| `newSkinName` / `oldSkinName` | **有**（OtherPlayerPackets.java:164–165） | **无**（PlayerList Add 不含换皮肤名） |
| `entityUniqueId` / `xuid` / `deviceOs` 等 | **无** | **有** |
| 触发时机 | 玩家换肤时推送 | 玩家加入/列表同步时推送（含初始皮肤） |

### 5.3 字段差异结论

- **SkinData 内部**：无差异，两边同一份结构。
- **SkinData 外部**：PlayerSkin 独有 `newSkinName`、`oldSkinName`；PlayerList Add 独有 `entityUniqueId`、`username`、`xuid`、`platformOnlineId`、`deviceOs`、`isTeacher`、`isHost`、`isSubClient`、`color`。
- `trustedSkin`：两边都有，但读取位置不同（PlayerSkin 在包内单条；PlayerList Add 在所有 entry 之后批量）。
- PlayerList Add 中没有 `newSkinName`/`oldSkinName`，因为它是首次下发皮肤，不存在"旧皮肤"概念。

---

## ⑥ 对 Fabric Mod 开发的实操要点

1. **监听两个 BE 包**即可拿到所有玩家皮肤：`PlayerList Add`（首次下发）+ `PlayerSkin`（换肤更新）。两者 SkinData 结构一致，可用同一解析器。
2. **UUID 一致性**：两个包都用同一个 BE 玩家 UUID 作为 key，可直接以此 UUID 映射到 JE 端皮肤缓存。
3. **真实用户名取 displayName**：不要取 GameProfile.name（那是 UUID 编码字符串）。
4. **trustedSkin 字段**：PlayerSkin 包末尾的 `trustedSkin` 表示 BE 服务器已校验过 Xbox Live 皮肤签名；Fabric Mod 若只渲染不校验，可忽略此字段。
5. **ImageType 解析**：BE 皮肤 PNG 在包中是 `intLE width` + `intLE height` + `intLE size` + 原始 PNG 字节（不是裸 RGBA）。详见 `ImageType.java`。
6. **小端整数**：BE 协议整数多为 LE 或 VarUInt，与 JE 大端不同；解析时注意字节序。
7. **Persona 皮肤**：`persona=true` 的皮肤依赖 `personaPieces` + `tintColors` + `geometryData`，不能仅靠 `skinData` PNG 还原。Mod 若要完整支持 Persona 皮肤，需实现 BE 几何渲染管线（复杂）；若只支持自定义 PNG 皮肤，可只取 `skinData` + `skinResourcePatch` + `geometryData`。

---

## ⑦ 源码引用清单

| 文件 | URL | 关键行 |
|------|-----|--------|
| OtherPlayerPackets.java（PLAYER_SKIN 处理） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java | 160–168 |
| HudPackets.java（PLAYER_LIST 处理） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java | 55–144（Add: 64–124；displayName: 94；username 读取: 76；skin 读取: 80；trustedSkin 批量: 98–102） |
| SkinType.java（SkinData 线路编解码） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/model/SkinType.java | 36–81（read），83–117（write） |
| SkinData.java（SkinData record 定义） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/model/SkinData.java | 14–20 |
| BedrockTypes.java（SKIN/STRING/UUID/IMAGE 类型常量） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/BedrockTypes.java | SKIN=L95, STRING=L50, UUID=L54, IMAGE=L53 |
| LoginPackets.java（自己皮肤 JWT 上传，参考） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/LoginPackets.java | 159–166 |

---

## ⑧ 版本与提交

- ViaBedrock commit: `ed4cbde41ac23e52a629230ba96018e7380fef4d`（`main` 分支，2026-08 抓取）
- 对应 Bedrock Edition 协议版本：1.26.30
- 抓取方式：GitHub MCP `get_file_contents`（PLAYER_SKIN/PLAYER_LIST/SkinType/SkinData/BedrockTypes）+ WebFetch（HudPackets/SkinType/SkinData）
- 文档生成时间：2026-08-07
