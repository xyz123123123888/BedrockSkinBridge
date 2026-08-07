# ViaBedrock `SkinProvider.setSkin` 触发时机研究报告

> 研究目标：查清 ViaBedrock 在处理 Bedrock `PlayerList`/`PlayerSkin` 包时是否调用 `SkinProvider.setSkin`，以及为何自定义 Mod 中覆盖的 `setSkin` 方法从未被触发。
>
> 源码版本：`RaphiMC/ViaBedrock` commit `ed4cbde41ac23e52a629230ba96018e7380fef4d`（main 分支 HEAD @ 2026-08-07）
>
> 关联文档：`docs/viabedrock-skin-pipeline.md`、`docs/viabedrock-api-research.md`、`../bedrock-untrusted-skin.md`

---

## ① PlayerList (Add) 是否调用 `setSkin`？—— **调用**

**源文件**：[`HudPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java)

`ClientboundBedrockPackets.PLAYER_LIST` 被映射为 JE `ClientboundPackets26_1.PLAYER_INFO_UPDATE`。`Add` 分支在第 80 行读取每个玩家的 `SkinData`，并在第 95 行**无条件**调用 `setSkin`：

```java
// 行 64
case Add -> {
    final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // length
    // ...
    for (int i = 0; i < length; i++) {
        // ...
        final SkinData skin = wrapper.read(BedrockTypes.SKIN); // skin   ← 行 80
        // ...
        wrapper.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[]{
                new GameProfile.Property("xuid", xuid),
                new GameProfile.Property("platform_online_id", platformOnlineId),
                new GameProfile.Property("device_os", String.valueOf(deviceOs)),
                new GameProfile.Property("is_teacher", String.valueOf(isTeacher)),
                new GameProfile.Property("is_host", String.valueOf(isHost)),
                new GameProfile.Property("is_subclient", String.valueOf(isSubClient))
        }); // properties                                                                  ← 行 85-92
        // ...
        Via.getManager().getProviders().get(SkinProvider.class).setSkin(wrapper.user(), uuids[i], skin); // ← 行 95
    }
    // ...
}
```

### 关键观察

1. **`setSkin` 在 `Add` 分支中确实被调用**，对每个玩家执行一次，传入从包中读取的 `SkinData`。
2. **JE 侧 `GameProfile.Property` 数组不包含任何 `textures` 属性**（行 85-92 只写 `xuid`、`platform_online_id`、`device_os`、`is_teacher`、`is_host`、`is_subclient`）。这意味着 ViaBedrock **不把皮肤图片放进 JE GameProfile**——皮肤只能通过 `setSkin` 回调路径传递给客户端 Mod。
3. **没有 `trustedSkin` 条件判断**：行 99-102 在循环结束后统一读取 `trusted skin` 布尔值，仅用于消费字节流；`setSkin` 调用与该标志无关。
4. 调用发生在 packet handler 内部，**同步执行于 Netty IO 线程**（不是客户端主线程）。

---

## ② `PLAYER_SKIN` (0x6D) 是否调用 `setSkin`？—— **调用**

**源文件**：[`OtherPlayerPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java)（注意：ViaBedrock 的包处理类没有 `PlayerPackets.java`，玩家相关包分散在 `OtherPlayerPackets` / `PlayPackets` / `HudPackets` 等）

行 160-168 注册 `PLAYER_SKIN` 处理器：

```java
// 行 160
protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_SKIN, null, wrapper -> {
    wrapper.cancel();                                                    // 行 161 ← 先 cancel
    final UUID uuid = wrapper.read(BedrockTypes.UUID);                   // 行 162
    final SkinData skin = wrapper.read(BedrockTypes.SKIN);               // 行 163
    wrapper.read(BedrockTypes.STRING); // new skin name                  // 行 164
    wrapper.read(BedrockTypes.STRING); // old skin name                  // 行 165
    wrapper.read(Types.BOOLEAN); // trusted skin                         // 行 166
    Via.getManager().getProviders().get(SkinProvider.class).setSkin(wrapper.user(), uuid, skin); // 行 167
});
```

### 关键观察

1. **`setSkin` 同样被无条件调用**，传入从 `PLAYER_SKIN` 包读取的 `SkinData`。
2. **`wrapper.cancel()` 在第 161 行先执行**——该包不会转发到 JE 客户端，仅供 ViaBedrock 内部消费（用于把皮肤推送给客户端 Mod）。
3. **`trustedSkin` 字段被读取但未用作条件**（行 166）。无论 trusted 与否，`setSkin` 都会被调用。这意味着即使是 untrusted 自定义皮肤，ViaBedrock 也会推送。
4. 第二参数为 `null`（不映射到任何 JE 包类型）+ `wrapper.cancel()`，等于完全吞掉该包，仅触发 `setSkin` 副作用。

---

## ③ `SkinProvider.setSkin` 默认实现 —— **非空方法，依赖外部 Mod 注册 Channel**

**源文件**：[`SkinProvider.java`](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java)

```java
public void setSkin(final UserConnection user, final UUID playerUuid, final SkinData skin) {
    final ChannelStorage channelStorage = user.get(ChannelStorage.class);
    if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CHANNEL)) {           // "viabedrockutility:data"
        ViaBedrockUtilityInterface.sendSkin(user, playerUuid, skin);
    } else if (channelStorage.hasChannel(BedrockSkinUtilityInterface.CHANNEL)) {   // "bedrockskin:data"
        BedrockSkinUtilityInterface.sendSkin(user, playerUuid, skin);
    }
}
```

### 关键观察

1. **`setSkin` 不是空方法**，但**也不是直接把皮肤塞进 JE GameProfile**。它的默认行为是查询 `ChannelStorage` 中是否注册了以下两个自定义 Channel 之一：
   - `viabedrockutility:data`（[`ViaBedrockUtilityInterface.CHANNEL`](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java)）—— 对应 [ViaBedrockUtility](https://github.com/Oryxel/ViaBedrockUtility) Mod
   - `bedrockskin:data`（[`BedrockSkinUtilityInterface.CHANNEL`](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java)）—— 对应 [BedrockSkinUtility](https://github.com/Camotoy/BedrockSkinUtility) Mod
2. **如果两个 Channel 都未注册，`setSkin` 等于空操作（静默 no-op）**——既不报错也不缓存皮肤。这正是默认 ViaBedrock + 无配套皮肤 Mod 时的行为。
3. `ChannelStorage` 在 [`BedrockProtocol.init(UserConnection)` 第 137 行](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/BedrockProtocol.java) 为每个连接新建，Channel 通过 `MultiStatePackets.CUSTOM_PAYLOAD_HANDLER` 在配置阶段由客户端声明添加。
4. `ViaBedrockUtilityInterface.sendSkin` 会拆分为 `SKIN_INFORMATION` + 多个 `SKIN_DATA` + 可选 `CAPE` 自定义负载包，通过 JE `ClientboundPackets26_1.CUSTOM_PAYLOAD` 发送给客户端。**persona 皮肤或 null 皮肤会被跳过**（`if (skin.skinData() == null || skin.persona()) return;`）。

### 含义

- **默认 `setSkin` 实际上是"事件分发器"**：它把皮肤数据分发给注册了对应 Channel 的客户端 Mod。
- 想让 JE 客户端看到 BE 玩家皮肤，要么：
  - (A) 让自己的 Mod 注册 `viabedrockutility:data` 或 `bedrockskin:data` Channel（走默认 `setSkin` 路径），或
  - (B) 覆盖 `setSkin` 方法，自己处理 `SkinData`（用户当前尝试的路径）。

---

## ④ BDS 何时发送 `PlayerSkin` 包？—— PlayerList Add 已含皮肤，PlayerSkin 仅在皮肤变化时发送

### 4.1 PlayerList Add 已包含完整 SkinData

Bedrock 协议中 `PlayerList` 包的 `Add` action 携带每个玩家的完整 `SkinData`（皮肤图片 + 几何 + cape + persona 信息等），这是 BDS 在新玩家加入时把"当前在线玩家列表 + 皮肤"一次性下发的标准路径。ViaBedrock 在 `HudPackets.java` 行 80 直接 `wrapper.read(BedrockTypes.SKIN)` 读取该结构。

### 4.2 `PlayerSkin` (0x6D) 包的发送时机

`PlayerSkin` 包**不在玩家首次加入时发送**——首次皮肤数据随 `PlayerList Add` 下发。`PlayerSkin` 包的用途是**在会话期间玩家皮肤发生变化时广播**（例如玩家在角色创建器中切换皮肤、上传新皮肤包等）。

### 4.3 BDS 配置确认

参考本仓库 `bedrock-server-1.26.30.5/server.properties`：

```
disable-custom-skins=false
# If true, disable players customized skins that were customized outside of the
# Minecraft store assets or in game assets.

disable-persona=false
```

`disable-custom-skins=false`（默认值）→ BDS **不会禁用自定义皮肤**，会正常在 `PlayerList Add` 中下发 untrusted skin 数据。BDS 没有"禁用 PlayerSkin 推送"的配置项；它总是按协议规范发送。

### 4.4 含义

- **日常测试场景下，`PLAYER_SKIN` 包几乎不会被触发**（除非测试期间手动切换皮肤）。
- 因此 `setSkin` 的主要触发源是 **PlayerList Add**，而 PlayerList Add 在玩家加入流程的早期就发送（见第 ⑦ 节时序分析）。

---

## ⑤ ViaFabricPlus 是否拦截了 `PlayerSkin` 包？—— **没有**

### 5.1 代码搜索结果

在 `ViaVersion/ViaFabricPlus` 仓库全量代码搜索：

| 搜索词 | 命中数 |
|---|---|
| `SkinProvider` | 0 |
| `setSkin` | 0 |
| `ViaBedrockUtilityInterface` | 0 |
| `BedrockSkinUtilityInterface` | 0 |
| `registerProvider viabedrock` | 0 |

### 5.2 ViaFabricPlus 对 ViaBedrock 的介入点

ViaFabricPlus 仅通过以下方式与 ViaBedrock 集成（见 [`ProtocolTranslator.java`](https://github.com/ViaVersion/ViaFabricPlus/blob/main/src/main/java/com/viaversion/viafabricplus/protocoltranslator/ProtocolTranslator.java)）：

- 加载 ViaBedrock 平台及协议
- 提供 [`ViaFabricPlusNettyPipelineProvider`](https://github.com/ViaVersion/ViaFabricPlus/tree/main/src/main/java/com/viaversion/viafabricplus/protocoltranslator/impl/provider/viabedrock)（覆盖 ViaBedrock 的 `NettyPipelineProvider`，用于 RakNet/NetherNet 管线建立）
- 通过 Mixin 微调 `ViaBedrockConfig`（`MixinViaBedrockConfig`）

**ViaFabricPlus 完全不碰 `SkinProvider`、不拦截 `PLAYER_SKIN` / `PLAYER_LIST` 包、不重写 `setSkin`**。可以排除 ViaFabricPlus 作为"setSkin 不被调用"的嫌疑。

---

## ⑥ `PlayerListStorage` 是否缓存 SkinData？—— **不缓存**

**源文件**：[`PlayerListStorage.java`](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/storage/PlayerListStorage.java)

```java
public class PlayerListStorage implements StorableObject {
    private final Map<UUID, Pair<Long, String>> playerList = new HashMap<>();

    public Pair<Long, String> addPlayer(final UUID uuid, final long entityUniqueId, final String name) {
        return this.playerList.put(uuid, new Pair<>(entityUniqueId, name));
    }
    // removePlayer / containsPlayer / getPlayer(UUID) / getPlayer(long entityUniqueId)
}
```

### 关键观察

1. `PlayerListStorage` 只缓存 `Pair<Long, String>`，即 **(entityUniqueId, name)**，**完全不存储 SkinData**。
2. `HudPackets.java` 行 106 调用 `playerListStorage.addPlayer(uuids[i], entityUniqueIds[i], names[i])` 时，并未把第 80 行读到的 `skin` 传入。
3. **无法从 `PlayerListStorage` 反查皮肤**——皮肤数据只在 `setSkin` 调用那一刻"路过"一次；如果 `setSkin` 没有把皮肤转储到自己的缓存，皮肤数据就永久丢失。
4. 这也解释了为何用户 Mod 中 `BedrockSkinCache` 为空——`setSkin` 是把皮肤写入缓存的唯一入口，而它没被触发。

---

## ⑦ 结论：为什么 `setSkin` 没被调用 + 修复建议

### 7.1 根因诊断

汇总前 6 节证据：

| 假说 | 验证结果 |
|---|---|
| ViaBedrock 不调用 `setSkin` | ❌ 假。`HudPackets.java:95` 与 `OtherPlayerPackets.java:167` 明确调用 |
| `setSkin` 默认是空方法 | ❌ 假。默认实现会通过 Channel 推送皮肤（若 Channel 已注册） |
| ViaFabricPlus 拦截了 PlayerSkin/PlayerList | ❌ 假。ViaFabricPlus 完全不碰皮肤路径 |
| BDS 不发送皮肤数据 | ❌ 假。`disable-custom-skins=false`，PlayerList Add 含完整 SkinData |
| PlayerListStorage 缓存皮肤可绕过 setSkin | ❌ 假。只存 entityUniqueId+name |
| **用户 Mod 注册 SkinProvider 的时机晚于 PlayerList Add 处理** | ✅ **真——这是根因** |

### 7.2 时序分析（根因）

用户 Mod 在 `ClientPlayConnectionEvents.JOIN`（Fabric API 事件，客户端主线程）时注册 `SkinProvider` 覆盖。但 ViaBedrock 处理 `PLAYER_LIST Add` 包发生在 **Netty IO 线程**，且该包在 JE `GameJoin` 包**之前或几乎同时**被翻译并转发。具体时序：

```
[Netty IO 线程]
  1. 收到 Bedrock StartGame
  2. 翻译为 JE GameJoin，写入客户端管道
  3. 收到 Bedrock PlayerList Add        ← 含其他在线玩家的 SkinData
  4. 调用 Via.getManager().getProviders()
     .get(SkinProvider.class).setSkin(...)
     ↑ 此时用户覆盖尚未注册 → 调用的是默认 SkinProvider
     ↑ 默认实现因 Channel 未注册 → 静默 no-op，皮肤数据被丢弃
  5. 翻译为 JE PlayerInfoUpdate，写入客户端管道

[客户端主线程]
  6. 处理 GameJoin → 触发 ClientPlayConnectionEvents.JOIN
  7. 用户 Mod 在此时 register SkinProvider 覆盖       ← 太晚了
  8. 处理 PlayerInfoUpdate（皮肤数据早已丢失）
```

**关键点**：步骤 4 的 `setSkin` 调用发生在步骤 7 的覆盖注册**之前**。由于 `ViaProviders.register` 是"最后注册者胜出"的简单 `Map.put`（见下），用户的覆盖确实会替换默认实现——但替换发生在 PlayerList Add 处理**之后**，对那次 `setSkin` 调用无效。

### 7.3 `ViaProviders` 注册语义佐证

**源文件**：[`ViaProviders.java`](https://github.com/ViaVersion/ViaVersion/blob/master/api/src/main/java/com/viaversion/viaversion/api/platform/providers/ViaProviders.java)

```java
public <T extends Provider> void register(Class<T> provider, T value) {
    providers.put(provider, value);   // 简单覆盖，最后注册者胜
}
```

ViaBedrock 自身在 [`BedrockProtocol.register(ViaProviders)` 第 116 行](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/BedrockProtocol.java) 注册默认 `new SkinProvider()`——此调用发生在 ViaVersion 平台启动时（**仅一次，非每连接**）。因此：

- 用户在 `JOIN` 时 `register` 覆盖，确实能覆盖默认实现（不会失败）。
- **但** PlayerList Add 的 `setSkin` 调用已在此之前发生 → 用户覆盖"注册成功"日志准确，但首次（也是唯一一次）`setSkin` 调用已错过。
- 后续若 BDS 发送 `PLAYER_SKIN` 包（玩家换肤），覆盖的 `setSkin` 才会被调用——但日常测试场景不会触发换肤，所以日志中"setSkin called"从未出现。

### 7.4 次要佐证：`PLAYER_SKIN` 在测试中不触发

如第 ④ 节所述，`PlayerSkin` 包仅在会话期间玩家主动换肤时发送。用户测试时若只是加入服务器观察其他 BE 玩家，不会触发 `PLAYER_SKIN`，因此唯一可能的 `setSkin` 触发点是 PlayerList Add——而该调用又因时序问题命中默认实现。

### 7.5 修复建议（按推荐度排序）

#### 方案 A（推荐）：注册 Channel，走默认 `setSkin` 路径

不覆盖 `SkinProvider`，而是让 Mod 在 **配置阶段** 注册 `viabedrockutility:data` Channel，让默认 `setSkin` 实现把皮肤以 `CUSTOM_PAYLOAD` 包形式推给客户端：

1. Mod 在客户端配置阶段声明 `viabedrockutility:data` 为已知 Channel（通过 `ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD` 或 JE 的 `RegisterCustomPayloadC2SPacket` 等机制），使其进入 `ChannelStorage`。
2. 监听 JE 客户端收到的 `CustomPayloadS2C` 包，过滤 `viabedrockutility:data`，按 [`ViaBedrockUtilityInterface.sendSkin`](https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java) 的格式解析 `SKIN_INFORMATION` / `SKIN_DATA` / `CAPE` 三类消息。
3. 自行渲染或缓存皮肤。

**优点**：
- 完全规避时序问题——Channel 注册发生在配置阶段，早于 PlayerList Add。
- 这是 ViaBedrock 设计的官方扩展机制，与 [ViaBedrockUtility](https://github.com/Oryxel/ViaBedrockUtility) Mod 兼容。
- 默认 `setSkin` 会自动跳过 persona 皮肤和 null 皮肤，逻辑已内置。

**注意**：
- 需要在配置阶段（不是 JOIN）注册 Channel，参考 ViaBedrock 的 `ConfigurationPackets.java` 与 `MultiStatePackets.CUSTOM_PAYLOAD_HANDLER`。

#### 方案 B：在 Mod 初始化早期注册 `SkinProvider` 覆盖

将 `SkinProvider` 覆盖的注册从 `ClientPlayConnectionEvents.JOIN` 改为 **Mod 初始化阶段**（`ClientModInitializer.onInitializeClient`），并确保在 ViaBedrock 平台初始化之后执行：

```java
// 在 onInitializeClient 中，确保 ViaBedrock 已加载后
Via.getManager().getProviders().register(SkinProvider.class, new MySkinProvider());
```

**优点**：简单，只需改注册时机。
**风险**：
- 需保证注册在 ViaBedrock `BedrockProtocol.register` 之后，否则会被 ViaBedrock 的默认实现覆盖（因为 `register` 是 `Map.put`，后注册者胜）。
- ViaBedrock 的初始化时机由 ViaFabricPlus 控制，可能晚于普通 Mod 的 `onInitializeClient`——需要监听 Via 平台就绪事件或使用 `Entrypoint` 顺序保证。
- 即使时机正确，`setSkin` 仍在 Netty 线程被调用，需注意线程安全（不能直接操作 Minecraft 客户端对象，需 `MinecraftClient.execute(...)` 调度到主线程）。

#### 方案 C：Mixin 直接拦截 `SkinProvider.setSkin`

用 Mixin 注入 `SkinProvider.setSkin` 的方法头，无论哪个 Provider 实例被注册都能拦截：

```java
@Mixin(SkinProvider.class)
public abstract class MixinSkinProvider {
    @Inject(method = "setSkin", at = @At("HEAD"))
    private void onSetSkin(UserConnection user, UUID playerUuid, SkinData skin, CallbackInfo ci) {
        // 在此缓存 skin 到 BedrockSkinCache
        BedrockSkinCache.put(playerUuid, skin);
    }
}
```

**优点**：完全绕过 Provider 注册时序问题，所有 `setSkin` 调用都会被拦截。
**风险**：
- Mixin 目标是 `SkinProvider` 类本身，不是某个实例——即使 `Via.getManager().getProviders().register` 替换了实例，Mixin 仍生效。
- 需注意 Mixin 的 `remap` 设置（ViaBedrock 不是 Minecraft 原版类，需 `remap = false`）。
- 仍需线程安全：`setSkin` 在 Netty 线程调用，缓存写入需考虑并发。

#### 方案 D（不推荐）：Mixin 拦截 `HudPackets` / `OtherPlayerPackets` 的包处理器

直接在 ViaBedrock 的包处理逻辑中插入拦截代码，提取 `SkinData`。**不推荐**：耦合 ViaBedrock 内部实现，版本升级易碎。

### 7.6 验证方案

无论采用哪种修复，建议加入以下诊断日志以验证 `setSkin` 是否被触发：

```java
// 在 setSkin 覆盖/Mixin 入口处
ViaBedrock.getPlatform().getLogger().info(
    "[BedrockSkinBridge] setSkin called for " + playerUuid +
    " skin=" + (skin != null ? skin.skinData() : "null") +
    " thread=" + Thread.currentThread().getName()
);
```

若修复后日志在**玩家加入服务器时**立即出现（且线程为 Netty IO 线程），则时序问题已解决。若仅在换肤时出现，说明仍仅命中 `PLAYER_SKIN` 路径。

### 7.7 总结

| 问题 | 答案 |
|---|---|
| PlayerList Add 是否调 setSkin？ | ✅ 是（`HudPackets.java:95`） |
| PLAYER_SKIN 是否调 setSkin？ | ✅ 是（`OtherPlayerPackets.java:167`） |
| setSkin 默认实现是什么？ | 通过 Channel 推送皮肤给配套 Mod，无 Channel 则 no-op |
| BDS 是否发 PlayerSkin？ | PlayerList Add 已含皮肤；PlayerSkin 仅在换肤时发；BDS 默认不禁用 |
| ViaFabricPlus 是否拦截？ | ❌ 完全不碰 |
| PlayerListStorage 是否缓存 skin？ | ❌ 只存 entityUniqueId+name |
| **为什么用户覆盖没被调用？** | **注册时机晚于 PlayerList Add 处理（Netty 线程 vs 客户端主线程 JOIN 事件）** |

---

## 附录：关键源码引用索引

| 文件 | URL | 关键行 |
|---|---|---|
| `HudPackets.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java | 80（读 SkinData）、95（调 setSkin）、85-92（GameProfile 不含 textures） |
| `OtherPlayerPackets.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java | 160-168（PLAYER_SKIN 处理） |
| `SkinProvider.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java | setSkin 默认实现 |
| `ViaBedrockUtilityInterface.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java | CHANNEL = "viabedrockutility:data"、sendSkin 拆包逻辑 |
| `BedrockSkinUtilityInterface.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java | CHANNEL = "bedrockskin:data" |
| `PlayerListStorage.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/storage/PlayerListStorage.java | 不缓存 SkinData |
| `ChannelStorage.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/storage/ChannelStorage.java | Channel 集合 |
| `BedrockProtocol.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/BedrockProtocol.java | 116（register 默认 SkinProvider）、137（init ChannelStorage） |
| `ClientboundBedrockPackets.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/ClientboundBedrockPackets.java | 93（PLAYER_SKIN 枚举） |
| `ViaProviders.java` | https://github.com/ViaVersion/ViaVersion/blob/master/api/src/main/java/com/viaversion/viaversion/api/platform/providers/ViaProviders.java | register = Map.put（后注册者胜） |
| `ConfigurationPackets.java` | https://github.com/RaphiMC/ViaBedrock/blob/ed4cbde41ac23e52a629230ba96018e7380fef4d/src/main/java/net/raphimc/viabedrock/protocol/packet/ConfigurationPackets.java | 配置阶段包处理（CUSTOM_PAYLOAD_HANDLER 在此引用） |

---

*研究日期：2026-08-07*
*源码 commit：`ed4cbde41ac23e52a629230ba96018e7380fef4d`*
