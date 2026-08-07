# ViaFabricPlus 拦截 JE 玩家 PlayerInfo 致 CSL 失效研究报告

> **研究目标**：搞清在 ViaFabricPlus + ViaBedrock 环境下进入 BE 服务器后，**JE 玩家自己（本地玩家）的皮肤为何变成原版 Steve**，而 CustomSkinLoader (CSL) 15.0.1 无法加载本地皮肤。
>
> - **CSL 仓库**：https://github.com/xfl03/MCCustomSkinLoader （`15-develop` 分支，commit `ebea66d2611ee0f04b3864dd939e5eae4ba51c9e`）—— 任务给的 `EncodedBug/CustomSkinLoader` 不存在，实际归属 `xfl03`
> - **ViaFabricPlus 仓库**：https://github.com/ViaVersion/ViaFabricPlus （`ver/26.1` 分支）
> - **ViaBedrock 仓库**：https://github.com/RaphiMC/ViaBedrock （`main` 分支）—— 任务给的 `ViaVersion/ViaBedrock` 不存在，实际归属 `RaphiMC`
> - **ViaVersion 仓库**：https://github.com/ViaVersion/ViaVersion （`master` 分支）
> - **研究日期**：2026-08-06
> - **研究方法**：逐个 fetch 上述仓库源码，带 URL + 行号引用

---

## 结论速览（TL;DR）

| 问题 | 结论 |
|---|---|
| 魔汐的猜测是否正确？ | **部分正确**。ViaFabricPlus（集成 ViaBedrock）确实改写了 JE 玩家自己的 PlayerInfo，但不是"拦截 PlayerInfo 包"，而是在 BE→JE 协议转换时**重新构造了 GameProfile**。 |
| JE 玩家自己的 GameProfile 被改成什么？ | `name = StringUtil.encodeUUID(uuid)`（16 字符不可见乱码字符串），`properties` 不含 `textures`（只有 `xuid`/`platform_online_id`/`device_os` 等元数据）。 |
| CSL 为何拿不到皮肤？ | CSL 用 `GameProfile.toString()` 作缓存 key（credential），用 `GameProfile.getName()`（乱码）查在线皮肤站。乱码名在本地皮肤站/Mojang/LittleSkin 都查不到 → cache miss → 回退原版 Steve。 |
| 拦截点在哪？ | ViaBedrock `HudPackets.java:74`（PLAYER_LIST→PLAYER_INFO_UPDATE 转换），对所有玩家（含 JE 玩家自己）一视同仁地构造 GameProfile。 |
| 是否有 ViaFabricPlus 的二次拦截？ | **有**。ViaFabricPlus `MixinSkinManager.java` 拦截 `SkinManager.createLookup`，对无 textures 的 profile 调用 `sessionService().fetchProfile(profile.id(), true)`。但 BE UUID 在 Mojang 查不到 → 返回 null → 仍用原 profile（无 textures）→ Steve。 |
| CSL 与 ViaFabricPlus Mixin 是否冲突？ | CSL 用 ASM 拦截 `SkinManager.get(GameProfile)` 和 `SkinManager$1.load`；ViaFabricPlus 用 Mixin @Redirect 拦截 `createLookup` 内对 `get(profile)` 的调用。两者注入点不同层面，可能叠加导致 CSL 的 `get` 方法体被 ViaFabricPlus 的 redirect 绕过。 |

---

## ① CSL 15.0.1 皮肤加载机制

### 1.1 CSL 用 ASM（非 Mixin）注入 SkinManager

CSL 15.0.1 是 **Universal 版本**（一个 jar 跨 Forge/NeoForge/Fabric/Quilt），不使用 Mixin，而是通过 **Bootstrap 的 `TargetedClassTransformer`** 在类加载时用 ASM 修改字节码。

- 注册入口：[`Bootstrap/Core/src/main/resources/META-INF/services/customskinloader.bootstrap.transformer.TargetedClassTransformer`](https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Bootstrap/Core/src/main/resources/META-INF/services/customskinloader.bootstrap.transformer.TargetedClassTransformer) 列出 `SkinManagerPatch` 等 patch 类
- 核心 patch：[`Bootstrap/Core/src/main/java/customskinloader/bootstrap/transformer/patch/SkinManagerPatch.java`](https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Bootstrap/Core/src/main/java/customskinloader/bootstrap/transformer/patch/SkinManagerPatch.java)
- 目标类常量定义：[`PatchSupport.java`](https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Bootstrap/Core/src/main/java/customskinloader/bootstrap/transformer/patch/PatchSupport.java) 中 `SKIN_MANAGER`、`SKIN_MANAGER_1`、`SKIN_MANAGER_CACHE_KEY`、`SKIN_MANAGER_TEXTURE_CACHE`

### 1.2 MC 26.2（25w35a+ / 1.21.9+）下的注入点

CSL 按 MC 版本数据包 ID 分支注入。MC 26.2 命中 `[773,800],[804,0x40000000],[0x40000109,]` 区间（见 `SkinManagerPatch.java` 构造器与各 `applyIfMatches` 调用）。注入点如下：

#### (a) SkinManager 构造器注入
- `SkinManagerPatch.injectSkinManagerConstructors` → `skin-manager.<init>.v5`（行 ~107）
- 在 `<init>(Path, Services, SkinTextureDownloader, Executor)V` 的 RETURN 前插入 `FakeSkinManager.setSkinCacheDir(path)`，设置皮肤缓存目录

#### (b) SkinManager.get(GameProfile) 拦截 —— 关键注入点
- `SkinManagerPatch.patchSkinManagerCacheKey` → `skin-manager.cache-key.v2`（行 ~137）
- 拦截 `SkinManager.get(GameProfile)` 方法体内的 `new SkinManager$CacheKey(uuid, property)` 构造
- 替换为 `FakeCacheKey.createFakeCacheKey(uuid, property, profile)` —— **把整个 GameProfile 偷偷塞进 CacheKey**
- 实现：`SkinManagerPatch.redirectCacheKeyConstruction`（行 ~265）

#### (c) SkinManager$1.load(CacheKey) 拦截
- `SkinManagerPatch.patchSkinManagerLoader`（行 ~150）
- 替换 CompletableFuture 的 executor 为 `CustomSkinLoader.THREAD_POOL`（`replaceCompletableFutureExecutor`）
- 拦截 `lambda$load$0(CacheKey, Services)` → `skin-manager-1.lambda-load-0.v3`（行 ~168）：把 `sessionService.unpackTextures(property)` 替换为 `FakeSkinManager.loadSkinFromCache(sessionService, property, cacheKey)`

### 1.3 FakeSkinManager 的中转逻辑

源文件：[`Common/src/main/java/customskinloader/fake/FakeSkinManager.java`](https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/fake/FakeSkinManager.java)

```java
// FakeSkinManager.java 第 67-72 行（23w42a+ / MC 26.2 命中）
public static Object loadSkinFromCache(MinecraftSessionService sessionService, Property property, SkinManager$CacheKey cacheKey) {
    if (cacheKey instanceof FakeCacheKey) {
        return FakeCacheKey.createMinecraftProfileTextures(
            loadSkinFromCache(sessionService, ((FakeCacheKey) cacheKey).profile(), false));
    }
    return sessionService.unpackTextures(property);
}
```

- 若 cacheKey 是 CSL 伪造的 `FakeCacheKey`，取出其中包装的 `GameProfile`，调用 `loadSkinFromCache(sessionService, profile, false)`
- `loadSkinFromCache(sessionService, profile, false)` → `getUserProfile(sessionService, profile, false)`（第 53-55 行）→ `CustomSkinLoader.loadProfile(profile)`

### 1.4 CSL 缓存 key（credential）—— 失效的根源

源文件：[`Common/src/main/java/customskinloader/utils/MinecraftUtil.java`](https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/utils/MinecraftUtil.java) 第 64-66 行

```java
public static String getCredential(GameProfile profile) {
    return profile == null ? null : profile.toString();
}
```

**credential = `GameProfile.toString()`**。Mojang authlib 的 `GameProfile.toString()` 输出形如：
```
GameProfile@<hash>{id=<uuid>, name=<name>, properties=[Property{name=xuid, value=...}, ...]}
```

即 credential 包含 id、name、properties 三者。**只要任一字段不同，credential 就不同**。

`CustomSkinLoader.loadProfile(GameProfile)`（[`Common/src/main/java/customskinloader/CustomSkinLoader.java`](https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/CustomSkinLoader.java) 第 49-78 行）的关键逻辑：

```java
public static UserProfile loadProfile(GameProfile gameProfile) {
    String username = TextureUtil.AuthlibField.GAME_PROFILE_NAME.get(gameProfile);
    String credential = MinecraftUtil.getCredential(gameProfile);
    // ...
    if (username == null || username.isEmpty() || username.equals(" ")) {
        return ModelManager0.toUserProfile(GameProfileLoader.getTextures(...properties...));
    }
    UserProfile profile;
    if (profileCache.isReady(credential)) {        // 用 credential 查缓存
        profile = profileCache.getProfile(credential);
        // ...
    } else {
        profile = loadProfile0(gameProfile, false); // 用 username 查在线皮肤站
    }
    return profile == null ? new UserProfile() : profile;
}
```

`ProfileCache` 内部所有方法都以 `credential.toLowerCase()` 作 map key（见 `ProfileCache.java` 的 `isExist`/`getProfile`/`updateCache` 等，均 `username.toLowerCase()`，参数名虽叫 username 实为 credential）。

**CSL 失效条件总结**：
1. `GameProfile.getName()` 为 null/空/空格 → 直接返回 properties 里的 textures（无则空 → Steve）
2. `GameProfile.getName()` 是乱码（非 null/空）→ 走 loadlist 用乱码名查在线皮肤站 → miss → Steve
3. credential（toString）与本地缓存的 key 不匹配 → cache miss

---

## ② ViaFabricPlus / ViaBedrock 对 JE 玩家 PlayerInfo 的处理

### 2.1 ViaBedrock 重写所有玩家的 GameProfile（含 JE 玩家自己）

源文件：[`HudPackets.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java)（PLAYER_LIST → PLAYER_INFO_UPDATE 转换）

```java
// HudPackets.java 第 64-96 行（PLAYER_LIST Add 动作）
case Add -> {
    final int length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
    final UUID[] uuids = new UUID[length];
    wrapper.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8,
        PlayerInfoUpdateAction.ADD_PLAYER, PlayerInfoUpdateAction.UPDATE_LISTED,
        PlayerInfoUpdateAction.UPDATE_DISPLAY_NAME));
    wrapper.write(Types.VAR_INT, length);
    for (int i = 0; i < length; i++) {
        uuids[i] = wrapper.read(BedrockTypes.UUID);              // 72: BE 分配的 UUID
        wrapper.write(Types.UUID, uuids[i]);                     // 73: 写入 JE 包
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
            .setSkin(wrapper.user(), uuids[i], skin);             // 95: 皮肤只进 setSkin
    }
}
```

**关键事实**：
1. **没有针对"JE 玩家自己"的特殊处理**。BE 的 PlayerList 中所有玩家（含 JE 玩家自己）走同一套逻辑。
2. GameProfile.name = `StringUtil.encodeUUID(uuid)`（第 74 行）—— **不是真实用户名**，是 16 字符不可见字符串。
3. GameProfile.properties **不含 `textures`**（第 85-92 行），只有 `xuid`/`platform_online_id`/`device_os`/`is_teacher`/`is_host`/`is_subclient`。
4. 真实用户名 `names[i]` 只写入 `display name`（NBT，第 94 行），不在 GameProfile.name 中。
5. 皮肤数据只传给 `SkinProvider.setSkin`（第 95 行），不进入 GameProfile。

### 2.2 StringUtil.encodeUUID 的实际值

源文件：[`StringUtil.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/util/StringUtil.java)

```java
public static String encodeUUID(final UUID uuid) {
    return encodeLong(uuid.getMostSignificantBits()) + encodeLong(uuid.getLeastSignificantBits());
}
public static String encodeLong(long bits) {
    final char[] chars = new char[4];
    for (int i = 0; i < 4; i++) {
        chars[i] = (char) (bits & 0xFF);
        bits >>= 8;
    }
    final StringBuilder builder = new StringBuilder();
    for (char c : chars) {
        builder.append('§').append(c);   // 每个 byte 前加 §
    }
    return builder.toString();
}
```

结果是一段 **16 字符的不可见字符串**（8 个 `§` + 8 个 0x00–0xFF 字节字符），例如 `§\u00xx§\u00yy...§\u00zz`。

### 2.3 ViaFabricPlus 的 MixinSkinManager 二次拦截

源文件：[`MixinSkinManager.java`](https://github.com/ViaVersion/ViaFabricPlus/blob/ver/26.1/src/main/java/com/viaversion/viafabricplus/injection/mixin/features/skin_loading/MixinSkinManager.java)

```java
@Mixin(SkinManager.class)
public abstract class MixinSkinManager {
    @Redirect(method = "createLookup",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/resources/SkinManager;get(Lcom/mojang/authlib/GameProfile;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<Optional<PlayerSkin>> fetchGameProfileProperties(
            SkinManager instance, GameProfile profile) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_20)
                && !profile.properties().containsKey("textures")) {
            return CompletableFuture.supplyAsync(() -> {
                final ProfileResult profileResult =
                    Minecraft.getInstance().services().sessionService().fetchProfile(profile.id(), true);
                return profileResult == null ? profile : profileResult.profile();
            }, Util.backgroundExecutor()).thenCompose(instance::get);
        } else {
            return instance.get(profile);
        }
    }
}
```

**作用**：当目标协议版本 ≤ 1.20 且 GameProfile 无 `textures` 属性时，用 `sessionService().fetchProfile(profile.id(), true)` 重新向 Mojang 拉取 profile。

**对 JE 玩家自己的影响**：
- ViaBedrock 给的 `profile.id()` 是 **BE 服务器分配的 UUID**，不是 JE 玩家原来的 session UUID
- `fetchProfile(BE_UUID, true)` 在 Mojang 服务器查不到（BE UUID 不在 Mojang 数据库）→ 返回 `null`
- 返回 null 时回退用原 profile（无 textures）→ 原版 Steve
- 即使该 mixin 触发，也无法修复皮肤

**是否触发**：条件是 `ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_20)`。ViaBedrock 转换的 Bedrock 协议对应的 JE 版本映射需进一步确认，但无论触发与否，结果都是 Steve（见下文 ③ 的链路分析）。

### 2.4 ViaVersion 核心是否拦截 PlayerInfo

在 ViaVersion/ViaVersion `master` 分支中搜索 outgoing PlayerInfo transformer，未发现针对 JE 玩家自己 GameProfile 的重写。ViaVersion 核心处理的是 JE↔JE 跨版本，PlayerInfo 的 GameProfile.name/properties 在 c1.7-c1.8 等旧版本兼容中可能转换格式，但**不涉及 BE 协议**。BE→JE 的 PlayerInfo 重写完全由 ViaBedrock（RaphiMC/ViaBedrock）负责，ViaVersion 核心不参与。

---

## ③ 拦截点定位：JE 玩家自己皮肤变 Steve 的完整链路

```
JE 客户端连接 BE 服务器
  │
  ▼
BE 服务器发送 PlayerList(Add) 包，包含 JE 玩家自己（用 BE 分配的 UUID）
  │
  ▼
ViaBedrock HudPackets.java 第 55-144 行 转换包
  │  第 74 行: GameProfile.name = StringUtil.encodeUUID(BE_UUID)  ← 16字符乱码
  │  第 85-92 行: properties = [xuid, platform_online_id, device_os, ...]  ← 无 textures
  │  第 95 行: 皮肤数据 → SkinProvider.setSkin（默认实现转发给 Mod 通道，无通道则丢弃）
  ▼
JE 客户端 ClientPacketListener 收到 CLIENTBOUND_PLAYER_INFO_UPDATE
  │  存入 playerInfoMap（含 JE 玩家自己的条目）
  │  JE 玩家自己的 PlayerInfo.gameProfile = {name=乱码, id=BE_UUID, properties=[无textures]}
  ▼
渲染 JE 玩家自己的皮肤（第三人称/Tab列表/F3）
  │  SkinManager.get(GameProfile) 被调用
  │  GameProfile 来自 PlayerInfo（name=乱码, 无textures）
  ▼
[CSL ASM 拦截] SkinManagerPatch.patchSkinManagerCacheKey
  │  把 new CacheKey(uuid, property) 替换为 FakeCacheKey.createFakeCacheKey(uuid, property, profile)
  │  profile 被塞进 FakeCacheKey
  ▼
[CSL ASM 拦截] SkinManager$1.load(FakeCacheKey)
  │  lambda$load$0 被 replaceUnpackTexturesWithFakeSkinCache 替换
  │  调用 FakeSkinManager.loadSkinFromCache(sessionService, property, cacheKey)
  ▼
FakeSkinManager.loadSkinFromCache (第 67-72 行)
  │  cacheKey instanceof FakeCacheKey → 取出 profile
  │  调用 CustomSkinLoader.loadProfile(profile)
  ▼
CustomSkinLoader.loadProfile (第 49-78 行)
  │  username = profile.getName() = "§\u00xx§\u00yy..." (乱码, 非 null/empty)
  │  credential = profile.toString() (含乱码name + BE_UUID + xuid properties)
  │  profileCache.isReady(credential) → false (本地缓存无此 key)
  │  → loadProfile0(gameProfile, false)
  ▼
CustomSkinLoader.loadProfile0 (第 80-141 行)
  │  遍历 config.loadlist（LittleSkin/本地皮肤站等）
  │  每个 loader.loadProfile(ssp, gameProfile) 用 username=乱码 查询
  │  皮肤站没有 "§\u00xx..." 这个用户 → 全部返回 null
  │  profile0.isEmpty() → true
  │  config.enableLocalProfileCache → 本地 ProfileCache/<credential>.json 也不存在
  │  返回 null
  ▼
loadProfile 返回 new UserProfile()（空）
  → ModelManager0.fromUserProfile(空) → 无皮肤纹理
  → SkinManager 回退到默认 Steve/Alex
  ▼
JE 玩家自己显示原版 Steve
```

**核心拦截点**：ViaBedrock `HudPackets.java:74`。这是所有问题的源头——把 GameProfile.name 从真实用户名改成 `StringUtil.encodeUUID(uuid)`。

**次要拦截点**：ViaFabricPlus `MixinSkinManager.java` 的 `createLookup` @Redirect。它在 CSL 的 `get` 方法上层再套一层拦截，可能使 CSL 的 `get` 方法体被 redirect 绕过（`createLookup` 内调用 `get(profile)` 被 redirect 到 `fetchGameProfileProperties`，后者再调 `instance.get(profile)` 才会命中 CSL 的 ASM 修改）。但由于 BE UUID 在 Mojang 查不到，这层拦截只会加重问题，不会修复。

---

## ④ CSL 与 ViaFabricPlus/ViaBedrock 的兼容性

### 4.1 CSL 是否有针对 Via 的兼容处理

在 CSL 15.0.1 源码（`15-develop` 分支）中搜索 "ViaVersion"、"ViaFabricPlus"、"viaversion"，**未发现任何兼容代码**。CSL 的 ASM patch 只针对原版 `SkinManager`，不感知 ViaFabricPlus 的 Mixin 存在。

### 4.2 两者注入点的潜在冲突

| 层 | 修改者 | 目标方法 | 修改方式 |
|---|---|---|---|
| Mixin | ViaFabricPlus | `SkinManager.createLookup` | @Redirect 拦截其中对 `get(GameProfile)` 的 INVOKE |
| ASM | CSL | `SkinManager.get(GameProfile)` | 替换方法体内 `new CacheKey` 为 `FakeCacheKey.createFakeCacheKey` |
| ASM | CSL | `SkinManager$1.load(CacheKey)` | 替换 executor + `lambda$load$0` 内 `unpackTextures` |

**冲突分析**：
- MC 26.2 的 `SkinManager.createLookup(GameProfile)` 内部调用 `this.get(profile)` 来获取 `CompletableFuture<Optional<PlayerSkin>>`
- ViaFabricPlus 的 @Redirect 把这个 `get(profile)` 调用 redirect 到 `fetchGameProfileProperties(instance, profile)`
- `fetchGameProfileProperties` 在无 textures 时走 `fetchProfile` 异步拉取，然后 `thenCompose(instance::get)` —— 这里再次调用 `instance.get(profile)`，此时才会进入 CSL ASM 修改后的 `get` 方法体
- **结论**：两者技术上能叠加（ViaFabricPlus 的 redirect 最终仍会调 `instance.get`，命中 CSL 的 ASM），但 ViaFabricPlus 的 `fetchProfile(BE_UUID)` 返回 null 后用的是原 profile（无 textures），CSL 拿到这个 profile 仍然因 name 乱码而失效

### 4.3 已知讨论

CSL 官方仓库 issues 与 wiki 中未找到针对 ViaFabricPlus 的官方兼容方案。CSL 的设计假设 GameProfile.name 是真实用户名（或至少是可查皮肤站的标识），而 ViaBedrock 的设计故意用不可见乱码作 name 以避免与真实玩家名冲突——两者设计前提直接冲突。

---

## ⑤ 解决方案建议

### 方案 a：Mixin 拦截 ViaBedrock 的 PlayerInfo 重写，保留原始 GameProfile（推荐度：中）

在 BedrockSkinBridge 中加一个 Mixin，拦截 ViaBedrock `HudPackets.java` 的 PLAYER_LIST 转换，当检测到是 JE 玩家自己（UUID == 本地 session UUID）时，把 GameProfile.name 改回真实用户名，并补上 textures 属性。

**问题**：
- ViaBedrock 的包处理器是 ViaVersion 的 PacketHandlers，不是 MC 的类，Mixin 难以直接拦截
- 需要用 ViaVersion 的包拦截 API（`protocol.registerClientbound` 重新注册覆盖），而非 Mixin
- JE 玩家自己的 session UUID 与 BE 分配的 UUID 如何映射需确认（ViaBedrock 登录时是否保留原 UUID）

### 方案 b：主动构造带 textures 的 GameProfile 注入 CSL 缓存（推荐度：高，最小改动）

这是当前 BedrockSkinBridge 已在尝试的方向，但**关键 bug 在于 credential key 用错**。修正方案：

1. **用正确的 credential 作 key**：CSL 15.0.1 的 credential = `GameProfile.toString()`，不是单纯 username。注入 CSL 缓存时必须用 JE 客户端实际看到的那个 GameProfile 的 `toString()`。
   - 对 JE 玩家自己：需拿到 `playerInfoMap` 中自己的 PlayerInfo.getProfile()（name=乱码, id=BE_UUID, properties=[xuid...]），调其 `toString()`
   - 对 BE 玩家：同理，用其 PlayerInfo 的 GameProfile.toString()

2. **主动把自己的本地皮肤注入 CSL 缓存**：
   - 监听 PLAYER_INFO_UPDATE，当自己的条目出现时，用真实用户名从 CSL 的 loadlist（LittleSkin/本地）查出皮肤
   - 用 ViaBedrock 构造的 GameProfile.toString() 作 key，调 `ProfileCache.updateCache(credential, userProfile, false)`

3. ** CSLInjector.java 的修复**（当前代码的缺陷）：
   - 当前 `CSLInjector.injectSkin(String username, ...)` 用 `username` 作 key（第 107 行）—— **错误**
   - 应改为用 `gameProfile.toString()` 作 key，并增加 `GameProfile` 参数

### 方案 c：CSL 本身的配置项（推荐度：低）

CSL 配置（`CustomSkinLoader.json`）中有 `forceLoadAllTextures`、`enableLocalProfileCache`、`forceUpdateSkull` 等项，但**没有任何选项能改变 credential 的计算方式或绕过 name 乱码问题**。CSL 的 loadlist 查询始终用 `gameProfile.getName()`，无法配置成用别的字段。故配置项无法解决此问题。

### 方案 d：绕过 CSL，直接用 Mixin 拦截 SkinManager 注入 textures（推荐度：高，最可靠）

放弃 CSL 集成，自己写 Mixin 拦截 `SkinManager.get(GameProfile)` 或 `createLookup`：
- 当检测到 GameProfile.name 是 `StringUtil.encodeUUID` 格式（以 `§` 开头的 16 字符串）时，判定为 ViaBedrock 构造的 profile
- 对 JE 玩家自己：用 `Minecraft.getInstance().getUser().getProfile()` 替换（session profile 有真实 name/id/textures）
- 对 BE 玩家：用本地缓存的 BE 皮肤图片构造 `textures` property 注入

**优点**：完全可控，不依赖 CSL 的 ASM 与 credential 机制
**缺点**：需自己实现皮肤贴图下载与缓存（但 BedrockSkinBridge 已有 LittleSkinClient 和 BedrockSkinHandler，可复用）

### 方案 e（推荐组合）：方案 b + 方案 d 兜底

1. **首选方案 b**：修复 CSLInjector 的 key 为 `gameProfile.toString()`，在 PLAYER_INFO_UPDATE 时把自己的本地皮肤注入 CSL 缓存。这是最小改动，能复用 CSL 的整个皮肤站生态。
2. **兜底方案 d**：若 CSL 的 ASM 与 ViaFabricPlus Mixin 冲突导致 CSL 的 `get` 方法体不执行（skin 仍 Steve），则加一个自己的 Mixin 拦截 `SkinManager.createLookup`，优先级高于 ViaFabricPlus 的 @Redirect，直接对 JE 玩家自己的 profile 替换为 session profile。

### 方案 f（针对 JE 玩家自己的特例）：用 session profile 覆盖 PlayerInfo

JE 玩家自己的皮肤信息其实本地就有——`Minecraft.getInstance().getUser().getProfile()` 是 session profile，含真实 name/id/textures。可以加一个 Mixin 拦截 `ClientPacketListener.handlePlayerInfoUpdate`，当条目的 UUID 匹配本地玩家 UUID（或 ViaBedrock 映射后的 UUID）时，把 GameProfile 替换为 session profile。这样 CSL 拿到的就是真实 GameProfile，credential 正确，本地皮肤站能查到。

**注意**：需确认 ViaBedrock 是否把 LocalPlayer 的 UUID 改成了 BE UUID。若是，则匹配条件用 BE UUID；若否，则 PlayerInfo 中可能根本没有自己的条目（session profile 直接用）。

---

## 附：关键源码引用索引

| 文件 | URL | 关键行 |
|---|---|---|
| CSL `MinecraftUtil.java` | https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/utils/MinecraftUtil.java | 64-66: `getCredential` = `profile.toString()` |
| CSL `CustomSkinLoader.java` | https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/CustomSkinLoader.java | 49-78: `loadProfile`（用 credential 查缓存，用 username 查皮肤站） |
| CSL `FakeSkinManager.java` | https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/fake/FakeSkinManager.java | 67-72: `loadSkinFromCache`（FakeCacheKey 中转） |
| CSL `SkinManagerPatch.java` | https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Bootstrap/Core/src/main/java/customskinloader/bootstrap/transformer/patch/SkinManagerPatch.java | 137: `patchSkinManagerCacheKey`；265: `redirectCacheKeyConstruction` |
| CSL `ProfileCache.java` | https://github.com/xfl03/MCCustomSkinLoader/blob/15-develop/Common/src/main/java/customskinloader/profile/ProfileCache.java | 全方法以 `credential.toLowerCase()` 作 key |
| ViaBedrock `HudPackets.java` | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/HudPackets.java | 74: name=encodeUUID；85-92: 无textures；95: setSkin |
| ViaBedrock `StringUtil.java` | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/util/StringUtil.java | `encodeUUID`（UUID→16字符不可见字符串） |
| ViaBedrock `OtherPlayerPackets.java` | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java | 79: name=encodeUUID（ADD_PLAYER 路径） |
| ViaFabricPlus `MixinSkinManager.java` | https://github.com/ViaVersion/ViaFabricPlus/blob/ver/26.1/src/main/java/com/viaversion/viafabricplus/injection/mixin/features/skin_loading/MixinSkinManager.java | `createLookup` @Redirect（fetchProfile 兜底，BE UUID 查不到） |
| ViaFabricPlus `skin_loading` 目录 | https://github.com/ViaVersion/ViaFabricPlus/tree/ver/26.1/src/main/java/com/viaversion/viafabricplus/injection/mixin/features/skin_loading | 仅 MixinSkinManager.java 一个文件 |

---

## 仓库归属勘误

任务描述中给出的两个仓库地址均不存在：
- `https://github.com/EncodedBug/CustomSkinLoader` → 404。CSL 实际仓库为 **`xfl03/MCCustomSkinLoader`**（`15-develop` 分支）
- `https://github.com/ViaVersion/ViaBedrock` → 404。ViaBedrock 实际仓库为 **`RaphiMC/ViaBedrock`**（`main` 分支），由 RaphiMC（RK_01）维护，ViaVersion 组织仅托管 ViaFabricPlus

---

*研究日期：2026-08-06 · 所有结论均基于实际 fetch 的源码（CSL commit `ebea66d2`、ViaBedrock `main`、ViaFabricPlus `ver/26.1`）*
