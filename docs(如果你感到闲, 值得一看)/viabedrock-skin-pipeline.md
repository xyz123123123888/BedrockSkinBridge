# ViaBedrock BE→JE 皮肤处理链路研究报告

> **研究目标**：彻底搞清 ViaBedrock 中 BE→JE 皮肤的处理链路，确认 `SkinProvider.setSkin` 是否真的被调用，以及 JE 客户端最终如何显示 BE 玩家皮肤。验证魔汐反馈的"ViaFabricPlus 似乎直接抛弃了皮肤，所以使用了原版皮"是否属实。
>
> - **ViaBedrock 仓库**：https://github.com/RaphiMC/ViaBedrock （`main` 分支）
> - **研究日期**：2026-08-06
> - **研究方法**：逐个 fetch ViaBedrock 源码，带 URL + 行号引用

---

## 结论速览（TL;DR）

| 问题 | 结论 |
|---|---|
| `setSkin` 是否被调用？ | **是**。在 `PLAYER_LIST`(Add) 和 `PLAYER_SKIN` 两个包处理器中均调用。 |
| `setSkin` 默认实现做了什么？ | **不存储皮肤**。仅当客户端存在 `viabedrockutility:data` 或 `bedrockskin:data` Mod 通道时，把皮肤数据通过自定义 payload 包转发给客户端 Mod；否则**静默丢弃**。 |
| JE PlayerInfo 是否带皮肤？ | **不带**。GameProfile 的 properties 只含 `xuid`/`platform_online_id`/`device_os` 等元数据，**没有 `textures` 属性**。 |
| GameProfile 名字是什么？ | `StringUtil.encodeUUID(uuid)`——一段 16 字符的不可见字符串（`§`+字节），**不是真实 BE 用户名**。 |
| "抛弃皮肤"的真相 | **(B) + (C) 同时成立**：setSkin 被调用但不存储皮肤（只转发给可选 Mod 通道），且转成 JE PlayerInfo 时 GameProfile 不含 textures 属性。 |
| 当前 Mod（覆盖 setSkin 注入 CSL）为何不工作？ | **CSL 缓存 key 与 GameProfile name 不匹配**：Mod 用真实 BE 用户名作 key 注入 CSL，但 CSL 按 GameProfile name（encoded UUID）查找 → cache miss → 原版皮。 |

---

## ① BE PlayerSkin 包处理链路

### 1.1 包处理器注册位置

ViaBedrock **没有** `ClientboundBedrockPacketsHandler.java` 这个单一文件（任务描述中的路径不存在）。包处理器按功能拆分到 `src/main/java/net/raphimc/viabedrock/protocol/packet/` 下的多个文件中。

通过 GitHub 代码搜索 `PLAYER_SKIN repo:RaphiMC/ViaBedrock`，定位到 `PLAYER_SKIN` 包在 **`OtherPlayerPackets.java`** 中处理：

- 文件：[`OtherPlayerPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java)
- raw：https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java

### 1.2 PLAYER_SKIN 处理器源码（第 160–168 行）

```java
protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_SKIN, null, wrapper -> {
    wrapper.cancel();                                          // 161: 输出包类型=null，取消转发
    final UUID uuid = wrapper.read(BedrockTypes.UUID);         // 162: 玩家 UUID
    final SkinData skin = wrapper.read(BedrockTypes.SKIN);     // 163: 皮肤数据（完整 SkinData record）
    wrapper.read(BedrockTypes.STRING);                         // 164: new skin name（读后丢弃）
    wrapper.read(BedrockTypes.STRING);                         // 165: old skin name（读后丢弃）
    wrapper.read(Types.BOOLEAN);                               // 166: trusted skin（读后丢弃）
    Via.getManager().getProviders().get(SkinProvider.class)    // 167: 调用 SkinProvider.setSkin
            .setSkin(wrapper.user(), uuid, skin);
});
```

**关键事实**：
1. 第二个参数为 `null` → 该包**不转换成任何 JE 包**，`wrapper.cancel()` 后皮肤数据不会以 JE 包形式发出。
2. 皮肤数据被读出后，**唯一的去向**就是 `SkinProvider.setSkin(user, uuid, skin)`。
3. `newSkinName`/`oldSkinName`/`trustedSkin` 字段被读取后丢弃，不传入 setSkin。

### 1.3 setSkin 的另一个调用点：PLAYER_LIST(Add)

皮肤不仅在 `PLAYER_SKIN` 包中出现，BE 的 `PlayerList` 包（Add 动作）也携带每个玩家的初始皮肤。这在 **`HudPackets.java`** 中处理：

- 文件：[`HudPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java)
- raw：https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java

第 80 行读取皮肤，第 95 行调用 setSkin：

```java
final SkinData skin = wrapper.read(BedrockTypes.SKIN);   // 80: 读取皮肤
// ... 写入 GameProfile properties（不含皮肤，见 ③）
Via.getManager().getProviders().get(SkinProvider.class)  // 95: 调用 setSkin
        .setSkin(wrapper.user(), uuids[i], skin);
```

### 1.4 调用点汇总

| # | 文件 | 行号 | 触发包 | 说明 |
|---|---|---|---|---|
| 1 | `HudPackets.java` | 95 | `PLAYER_LIST`(Add) | 玩家加入列表时携带初始皮肤 |
| 2 | `OtherPlayerPackets.java` | 167 | `PLAYER_SKIN` | 玩家皮肤更新 |

> **结论：`setSkin` 确实被调用。** 覆盖 `SkinProvider.setSkin` 的 Mod 会被这两个调用点触发。选项 (A)（"根本没调用 setSkin"）**不成立**。

---

## ② SkinProvider.setSkin 真实作用

- 文件：[`SkinProvider.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java)
- raw：https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java

### 2.1 setSkin 默认实现（完整源码）

```java
public void setSkin(final UserConnection user, final UUID playerUuid, final SkinData skin) {
    final ChannelStorage channelStorage = user.get(ChannelStorage.class);
    if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CHANNEL)) {       // "viabedrockutility:data"
        ViaBedrockUtilityInterface.sendSkin(user, playerUuid, skin);
    } else if (channelStorage.hasChannel(BedrockSkinUtilityInterface.CHANNEL)) { // "bedrockskin:data"
        BedrockSkinUtilityInterface.sendSkin(user, playerUuid, skin);
    }
    // else: 什么都不做 —— 皮肤被静默丢弃
}
```

### 2.2 关键发现

1. **不存储皮肤**：setSkin 不把皮肤写入任何 ViaBedrock storage（`PlayerListStorage`、`GameSessionStorage`、`EntityTracker` 等都没有皮肤字段）。皮肤数据在 setSkin 返回后即丢失（除非被 Mod 通道转发）。
2. **依赖客户端 Mod 通道**：只有当客户端注册了 `viabrockutility:data` 或 `bedrockskin:data` 通道时，皮肤才被转发。这两个通道分别对应 **ViaBedrockUtility** 和 **BedrockSkinUtility** 两个客户端 Mod。
3. **无通道 = 丢弃**：如果两个通道都不存在（即没装 ViaBedrockUtility / BedrockSkinUtility），setSkin 的 if/else if 都不命中，方法直接返回，**皮肤数据彻底丢失**。

### 2.3 Mod 通道转发实现

两个 Interface 的 `sendSkin` 方法逻辑相同：

- [`ViaBedrockUtilityInterface.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java)（`CHANNEL = "viabedrockutility:data"`）
- [`BedrockSkinUtilityInterface.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java)（`CHANNEL = "bedrockskin:data"`）

```java
public static void sendSkin(final UserConnection user, final UUID uuid, final SkinData skin) {
    if (skin.skinData() == null || skin.persona()) {   // persona 皮肤直接跳过！
        return;
    }
    // ... 把 skinData 分块写成多个 ClientboundPackets26_1.CUSTOM_PAYLOAD 包
    //     通过 plugin channel 发给客户端 Mod
}
```

**注意**：persona 皮肤（BE 角色编辑器拼装皮肤）会被 `sendSkin` 直接跳过，不转发。

### 2.4 getClientPlayerSkin（反向：JE→BE，仅用于登录）

`SkinProvider` 还有一个 `getClientPlayerSkin` 方法，在 [`LoginPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/LoginPackets.java) 第 164 行被调用，用于构造客户端**自己的**登录皮肤 JWT（发给 BE 服务器）。这是 JE→BE 方向，与 JE 显示 BE 玩家皮肤无关。

> **结论：选项 (B) 成立**——setSkin 被调用，但默认实现不存储皮肤，只转发给可选的客户端 Mod 通道。无 ViaBedrockUtility/BedrockSkinUtility 时皮肤被丢弃。

---

## ③ BE 玩家在 JE 端的 spawn / info 流程

BE 玩家在 JE 端通过两条路径生成 PlayerInfo 条目：

### 3.1 路径 A：PLAYER_LIST(Add) → JE PLAYER_INFO_UPDATE

- 文件：`HudPackets.java`，第 55–144 行
- BE `PlayerList`(Add) → JE `ClientboundPackets26_1.PLAYER_INFO_UPDATE`

**GameProfile 构造（第 69–94 行）**：

```java
wrapper.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8,
    PlayerInfoUpdateAction.ADD_PLAYER,
    PlayerInfoUpdateAction.UPDATE_LISTED,
    PlayerInfoUpdateAction.UPDATE_DISPLAY_NAME));           // 69: actions
wrapper.write(Types.VAR_INT, length);                        // 70
for (int i = 0; i < length; i++) {
    uuids[i] = wrapper.read(BedrockTypes.UUID);              // 72
    wrapper.write(Types.UUID, uuids[i]);                     // 73: uuid
    wrapper.write(Types.STRING, StringUtil.encodeUUID(uuids[i])); // 74: username = 编码后的 UUID！
    // ... 读取 entityUniqueId, name, xuid, platformOnlineId, deviceOs
    final SkinData skin = wrapper.read(BedrockTypes.SKIN);   // 80: 读取皮肤（但不写入 GameProfile！）
    // ...
    wrapper.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[]{
        new GameProfile.Property("xuid", xuid),
        new GameProfile.Property("platform_online_id", platformOnlineId),
        new GameProfile.Property("device_os", String.valueOf(deviceOs)),
        new GameProfile.Property("is_teacher", String.valueOf(isTeacher)),
        new GameProfile.Property("is_host", String.valueOf(isHost)),
        new GameProfile.Property("is_subclient", String.valueOf(isSubClient))
    });                                                       // 85-92: properties —— 无 textures！
    wrapper.write(Types.BOOLEAN, true);                       // 93: listed
    wrapper.write(Types.OPTIONAL_TAG, TextUtil.stringToNbt(names[i])); // 94: display name = 真实用户名
    Via.getManager().getProviders().get(SkinProvider.class)
            .setSkin(wrapper.user(), uuids[i], skin);         // 95: 皮肤只进 setSkin
}
```

**关键事实**：
- GameProfile 的 `properties` 数组**不含 `textures` 属性**——只有 `xuid`/`platform_online_id`/`device_os`/`is_teacher`/`is_host`/`is_subclient`。
- GameProfile 的 `name` = `StringUtil.encodeUUID(uuid)`——**不是真实用户名**。
- 真实用户名 `names[i]` 只写入 `display name`（NBT），不在 GameProfile.name 中。
- 皮肤数据被读取后只传给 `setSkin`，**不进入 GameProfile**。

### 3.2 路径 B：ADD_PLAYER → JE ADD_ENTITY + PLAYER_INFO_UPDATE

- 文件：`OtherPlayerPackets.java`，第 54–107 行
- BE `AddPlayer` → JE `ClientboundPackets26_1.ADD_ENTITY` + `PLAYER_INFO_UPDATE`

**GameProfile 构造（第 75–86 行）**：

```java
playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8,
    PlayerInfoUpdateAction.ADD_PLAYER, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // 76
playerInfoUpdate.write(Types.VAR_INT, 1);                    // 77
playerInfoUpdate.write(Types.UUID, uuid);                    // 78
playerInfoUpdate.write(Types.STRING, StringUtil.encodeUUID(uuid)); // 79: username = 编码后的 UUID！
playerInfoUpdate.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[]{
    new GameProfile.Property("platform_online_id", platformOnlineId),
    new GameProfile.Property("device_id", wrapper.read(BedrockTypes.STRING)),    // device id
    new GameProfile.Property("device_os", wrapper.read(BedrockTypes.INT_LE).toString()) // device os
});                                                           // 80-84: properties —— 无 textures！
playerInfoUpdate.write(Types.VAR_INT, ...gameMode...);       // 85
```

**关键事实**：
- `AddPlayer` 包本身**不含 SkinData 字段**（BE 协议中 spawn 包不带皮肤）。
- GameProfile properties 同样**无 `textures`**——只有 `platform_online_id`/`device_id`/`device_os`。
- GameProfile name 同样是 `StringUtil.encodeUUID(uuid)`。
- 此路径**不调用 setSkin**（皮肤不在 AddPlayer 包中）。

### 3.3 StringUtil.encodeUUID 的实际值

- 文件：[`StringUtil.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/util/StringUtil.java)

```java
public static String encodeUUID(final UUID uuid) {
    return encodeLong(uuid.getMostSignificantBits()) + encodeLong(uuid.getLeastSignificantBits());
}
public static String encodeLong(long bits) {
    final char[] chars = new char[4];
    for (int i = 0; i < 4; i++) {
        chars[i] = (char) (bits & 0xFF);   // 取低 8 位作为 char
        bits >>= 8;
    }
    final StringBuilder builder = new StringBuilder();
    for (char c : chars) {
        builder.append('§').append(c);     // 每个 byte 前加 §
    }
    return builder.toString();
}
```

结果是一段 **16 字符的不可见字符串**（8 个 `§` + 8 个 0x00–0xFF 字节字符），例如 `§\u00xx§\u00yy...`。这是 ViaBedrock 的设计：用不可见字符串作为 GameProfile name，避免与真实玩家名冲突，同时让 JE 客户端接受它。

### 3.4 全局搜索"textures"属性

使用 GitHub 代码搜索 `"textures" repo:RaphiMC/ViaBedrock`，结果只命中 resource-pack 相关文件（`ResourcePackStorage`、`TextureDefinitions`、各种 `ResourceRewriter`），**没有任何文件给玩家 GameProfile 写入 `textures` 属性**。

> **结论：选项 (C) 成立**——转成 JE PlayerInfo 时 GameProfile 不含 `textures` 属性，皮肤数据完全不进入 JE 玩家信息。JE 客户端收到一个没有 skin 的 GameProfile，自然回退到默认 Steve/Alex。

---

## ④ "抛弃皮肤"的真相

综合 ①②③ 的源码证据：

| 选项 | 是否成立 | 证据 |
|---|---|---|
| **(A)** ViaBedrock 收到 PlayerSkin 后根本没调用 setSkin | ❌ **不成立** | `OtherPlayerPackets.java:167` 和 `HudPackets.java:95` 明确调用 setSkin |
| **(B)** 调用了 setSkin，但默认实现不存储皮肤 | ✅ **成立** | setSkin 只转发给 Mod 通道，不写入任何 storage；无通道时丢弃 |
| **(C)** 皮肤存了，但转 PlayerInfo 时没带上 | ⚠️ **部分成立** | 皮肤**没有存**（B），但确实转 PlayerInfo 时没带 textures 属性 |
| **(D)** PlayerInfo 带了皮肤，但 JE 渲染用了原版 | ❌ **不成立** | PlayerInfo 根本没带 textures 属性 |

### 最终判定：**(B) + (C) 同时成立**

ViaBedrock 的设计是**有意不把 BE 皮肤注入 JE GameProfile**。它的架构是：

```
BE 服务器
  │  PlayerList(Add) / PlayerSkin 包（携带 SkinData）
  ▼
ViaBedrock 包处理器
  │  读取 SkinData
  │  ├─→ 写入 JE PlayerInfo（GameProfile.properties = 元数据，无 textures）
  │  └─→ SkinProvider.setSkin(uuid, skin)
  │        ├─ 有 viabedrockutility:data 通道 → 转发给 ViaBedrockUtility Mod
  │        ├─ 有 bedrockskin:data 通道 → 转发给 BedrockSkinUtility Mod
  │        └─ 都没有 → 静默丢弃
  ▼
JE 客户端
  │  收到 PlayerInfo（无 textures 属性）
  │  → GameProfile 没有 skin → 加载默认 Steve/Alex
  ▼
渲染原版皮
```

**ViaBedrock 把皮肤渲染职责完全外包给了 ViaBedrockUtility / BedrockSkinUtility 两个客户端 Mod**。这两个 Mod 通过 plugin channel 接收皮肤数据，再用 mixin 直接替换 JE 的皮肤贴图渲染。如果没装这两个 Mod，BE 皮肤在 JE 端**从协议层就被丢弃了**——这不是 bug，是 ViaBedrock 的设计。

魔汐说的"ViaFabricPlus 似乎是直接抛弃了皮肤"**完全准确**：ViaFabricPlus 集成了 ViaBedrock，但不集成 ViaBedrockUtility/BedrockSkinUtility，所以皮肤被丢弃，所有人显示原版皮。

---

## ⑤ 正确的注入点建议

### 5.1 当前 Mod 的做法与问题

当前 `BedrockSkinBridge` 的架构（见 `BedrockSkinProvider.java`、`CSLInjector.java`、`BedrockSkinHandler.java`）：

1. 覆盖 `SkinProvider.setSkin`，调用 `super.setSkin` 后拦截 `SkinData`
2. 从 `PlayerListStorage` 按 UUID 解析**真实 BE 用户名**
3. 把皮肤图片存到 `.minecraft/CustomSkinLoader/bedrockskinbridge/skins/<uuid>.png`
4. 构造 `(LOCAL)bedrockskinbridge/skins/<uuid>.png` 假 URL
5. 反射调用 `CSLInjector.injectSkin(username, skinUrl, model, null)`，用**真实用户名**作 CSL 缓存 key

**核心 Bug：CSL 缓存 key 与 GameProfile name 不匹配**

```
Mod 注入 CSL 缓存:  key = "真实BE用户名".toLowerCase()   （如 "steve"）
CSL 处理 PlayerInfo: 查找 key = GameProfile.name.toLowerCase()
                                  = StringUtil.encodeUUID(uuid).toLowerCase()
                                  = "§\u00xx§\u00yy...".toLowerCase()  （不可见乱码）
→ cache miss → CSL 回退到在线查询（按乱码名查 Mojang/LittleSkin）→ 也 miss → 原版皮
```

 CSL 的 `ProfileCache.cachedProfiles` 按 `username.toLowerCase()` 作 key（见 `CSLInjector.java` 第 12 行注释，基于 CSL 15.0.1 源码 SHA `ebea66d2` 核实）。但 CSL 处理 JE PlayerInfo 时用的是 GameProfile.name，而 ViaBedrock 把 name 设成了 `StringUtil.encodeUUID(uuid)`，不是真实用户名。

**次要问题**：
- **PLAYER_SKIN 更新不触发 PlayerInfo 重发**：`PLAYER_SKIN` 处理器 `wrapper.cancel()`（`OtherPlayerPackets.java:161`），不产生任何 JE 包。即使初始 PlayerList 时注入成功，后续皮肤更新也无法传播到 JE 客户端（除非玩家重新进出 PlayerList）。
- **Provider 注册时机风险**：Mod 在 `onInitializeClient` 中 `Via.getManager().getProviders().register(SkinProvider.class, ...)` 注册覆盖。但 ViaBedrock 在 `BedrockProtocol.register(ViaProviders)` 中也会 `providers.register(SkinProvider.class, new SkinProvider())`。如果协议加载晚于 Mod 初始化，ViaBedrock 的默认 provider 可能**覆盖 Mod 的覆盖**。需验证注册顺序（建议在 setSkin 中加日志确认是否被调用）。

### 5.2 注入点选项分析

| 选项 | 做法 | 可行性 | 复杂度 |
|---|---|---|---|
| **a. 修 CSL key** | 保持覆盖 setSkin，但 CSL 缓存 key 改用 `StringUtil.encodeUUID(uuid)` 而非真实用户名 | ✅ 最小改动，可能立即生效 | 低 |
| **b. 拦截 JE PlayerInfo** | 用 ViaVersion 包处理器或 mixin 拦截 outgoing `CLIENTBOUND_PLAYER_INFO_UPDATE`，注入 `textures` property | ✅ 最可靠，完全可控 | 中高 |
| **c. 纯 CSL 机制** | 即选项 a，把 BE 皮肤喂给 CSL，由 CSL 注入 | ✅ 同 a | 低 |
| **d. 官方 Mod 通道** | 实现 `viabedrockutility:data` / `bedrockskin:data` 通道接收器 + mixin 渲染 | ✅ ViaBedrock 官方支持路径 | 高（需写 mixin） |

### 5.3 推荐方案

#### 推荐：选项 a（修复 CSL key）+ 选项 b（PlayerInfo 拦截，处理更新）

**第一步（最小修复，验证假设）**：

在 `BedrockSkinProvider.setSkin` 中，把传给 `CSLInjector.injectSkin` 的 key 从真实用户名改为 `StringUtil.encodeUUID(uuid)`：

```java
// 当前代码（BedrockSkinProvider.java:48）
String username = resolveUsername(user, uuid);
BedrockSkinHandler.handleBedrockSkin(uuid, username, skinImage, resourcePatch, capeImage);

// 修改为：用 GameProfile name（encoded UUID）作 CSL key
String gameProfileName = net.raphimc.viabedrock.api.util.StringUtil.encodeUUID(uuid);
BedrockSkinHandler.handleBedrockSkin(uuid, gameProfileName, skinImage, resourcePatch, capeImage);
```

同时在 `setSkin` 入口加一行日志，确认覆盖是否真的被调用（排除 5.1 的 provider 注册时机问题）。

**第二步（处理皮肤更新）**：

`PLAYER_SKIN` 包到达时不产生 JE PlayerInfo，CSL 无法感知更新。两种解法：
- **解法 1**：在 setSkin 中（针对已在线玩家的皮肤更新），主动构造一个 JE `CLIENTBOUND_PLAYER_INFO_UPDATE` 包（`UPDATE_DISPLAY_NAME` action），触发 CSL 重新处理该玩家的 GameProfile。
- **解法 2**：用 mixin 拦截 CSL 的皮肤加载点，直接按 UUID 注入贴图（绕过 name 查找）。

**第三步（如果 CSL 路径不稳定，转向选项 b）**：

如果 `StringUtil.encodeUUID` 产生的不可见字符串（含 `§` 和 0x00–0xFF 字节）在 CSL 的字符串处理中被过滤/截断/修改，导致 key 仍然不匹配，则放弃 CSL 集成，改用 ViaVersion 包处理器拦截 outgoing `CLIENTBOUND_PLAYER_INFO_UPDATE`，直接在 GameProfile.properties 中注入 `textures` 属性。`textures` 属性的值需要是一个 JSON，含 `SKIN` url + model。url 指向本地 HTTP 服务（CSL 本身就有这个机制，可复用 `(LOCAL)` 文件路径或自建 HTTP）。

#### 不推荐：选项 d（官方 Mod 通道）

虽然 `viabedrockutility:data` / `bedrockskin:data` 是 ViaBedrock 官方设计路径，但实现接收器需要：
1. 在 `ChannelStorage` 中注册通道（让 setSkin 的 if 命中）
2. 接收分块的 `CUSTOM_PAYLOAD` 包并重组皮肤数据
3. 用 mixin 替换 JE `SkinManager` / `PlayerSkin` 渲染逻辑

这等于重写一个 ViaBedrockUtility，复杂度远高于修复 CSL key。除非要完全摆脱 CSL 依赖，否则不值得。

---

## 附：关键源码引用索引

| 文件 | URL | 关键行 |
|---|---|---|
| `SkinProvider.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java | setSkin 方法（转发给 Mod 通道，不存储） |
| `OtherPlayerPackets.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java | 160-168: PLAYER_SKIN 处理；54-107: ADD_PLAYER 处理 |
| `HudPackets.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java | 55-144: PLAYER_LIST→PLAYER_INFO_UPDATE；80: 读皮肤；95: 调 setSkin |
| `ViaBedrockUtilityInterface.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java | sendSkin（通道 `viabedrockutility:data`，persona 跳过） |
| `BedrockSkinUtilityInterface.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java | sendSkin（通道 `bedrockskin:data`） |
| `StringUtil.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/api/util/StringUtil.java | encodeUUID（UUID→16 字符不可见字符串） |
| `LoginPackets.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/packet/LoginPackets.java | 164: getClientPlayerSkin（JE→BE 登录皮肤，非 JE 显示） |
| `SkinData.java` | https://raw.githubusercontent.com/RaphiMC/ViaBedrock/main/src/main/java/net/raphimc/viabedrock/protocol/model/SkinData.java | record 定义（19 字段） |

---

*研究日期：2026-08-06 · ViaBedrock `main` 分支 · 所有结论均基于实际 fetch 的源码*
