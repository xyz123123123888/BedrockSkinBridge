# JE 26.2 客户端皮肤渲染管线与 Mixin 注入分析

> **研究目标**：搞清 `MixinPlayerInfo` 拦截 `PlayerInfo.createSkinLookup(GameProfile)` 后、用真实用户名构造新 `GameProfile` 调 `SkinManager.createLookup` 为何皮肤仍是原版 Steve；并判定该 Mixin 能否真正覆盖 ViaFabricPlus（VFP）的拦截。
>
> - **MC 版本**：26.2（Loom 反编译 jar：`C:\Users\Administrator\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar`）
> - **CSL 仓库**：`xfl03/MCCustomSkinLoader` `15-develop`（commit `ebea66d2`）
> - **VFP 仓库**：`ViaVersion/ViaFabricPlus` `ver/26.2`（commit `b2af6a1a`，默认分支即为 `ver/26.2`）
> - **研究方法**：`javap -p -c` 实际反编译 26.2 字节码 + fetch CSL/VFP 源码逐行引用
> - **研究日期**：2026-08-07

---

## 结论速览（TL;DR）

| 问题 | 结论 |
|---|---|
| `createSkinLookup` 是 static 还是实例方法？何时调用？ | **`private static`**，且**不在构造器中调用**——在 `getSkin()` 首次调用时**懒加载**赋值给 `skinLookup` 字段。 |
| `@Inject HEAD` 能拦截 `createSkinLookup` 吗？ | **能**（Mixin 可注入 private static 方法，handler 必须也是 static）。但必须用 `cir.setReturnValue(...)` 取消原方法并返回新 `Supplier`，否则原方法体仍以原 profile 执行。 |
| Mixin 拦截 `createSkinLookup` 能绕过 VFP 的 `@Redirect` 吗？ | **不能**。VFP 的 `@Redirect` 注入在 `SkinManager.createLookup` 内部对 `get(profile)` 的调用点；用户的 Mixin 仍要调 `createLookup`，所以 VFP 的 redirect **照常触发**。 |
| VFP 的 redirect 会阻断 CSL 吗？ | **不会**。VFP 的 `fetchGameProfileProperties` 在两条分支末尾**都调用 `instance.get(profile)`**（else 分支直接调；≤1.20 分支经 `thenCompose(instance::get)` 调）。而 `SkinManager.get` 的方法体已被 CSL 用 ASM 改写（`new CacheKey` → `FakeCacheKey.createFakeCacheKey`），所以 CSL **必然触发**。 |
| 皮肤仍是 Steve 的最可能原因？ | 三选一（按概率）：① Mixin handler 未用 `setReturnValue` 取消原方法 → 原 profile 仍跑；② CSL 未安装/未生效 → 新 profile 无 `textures` 属性 → `get()` 读到 `null` → `EMPTY` textures → `DefaultPlayerSkin` → Steve；③ 真实用户名在 LittleSkin 未注册 → CSL 查询 miss → Steve。 |
| CSL 用真实用户名能查到 LittleSkin 吗？ | **能**。CSL 的 `CustomSkinAPI.toJsonUrl(root, username)` = `root + username + ".json"`，即 `https://littleskin.cn/csl/{username}.json`，纯 username 查询，与 profile 的 `textures` 属性无关。 |

---

## ① PlayerInfo.createSkinLookup 调用时机

### 1.1 字节码事实（`javap -p -c net.minecraft.client.multiplayer.PlayerInfo`）

**字段声明**（注意 `skinLookup` 不是 `final`）：
```
private final com.mojang.authlib.GameProfile profile;
private java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin> skinLookup;  // 非 final，懒加载
```

**构造器** `<init>(GameProfile, boolean)` —— **完全没调用 `createSkinLookup`**：
```
0: aload_0
1: invokespecial Object.<init>
...
17: aload_1
18: putfield profile
21: aload_0
22: iload_2
23: invokestatic fallbackMessageValidator(Z)
26: putfield messageValidator
29: return
```
构造器只设置 `profile` / `gameMode`(默认) / `showHat`(true) / `messageValidator`。**`skinLookup` 在构造后仍为 `null`**。

**`createSkinLookup` 是 `private static`**：
```
private static java.util.function.Supplier<PlayerSkin> createSkinLookup(GameProfile);
  0: invokestatic Minecraft.getInstance()
  ...
  22: invokevirtual Minecraft.getSkinManager()
  25: aload_0                      // profile
  26: iload_2                      // requireSecure
  27: invokevirtual SkinManager.createLookup(GameProfile, Z)Supplier
  30: areturn
```

**真正的调用点在 `getSkin()`**（懒加载）：
```
public PlayerSkin getSkin();
  0: aload_0
  1: getfield skinLookup
  4: ifnonnull 18
  7: aload_0
  8: aload_0
  9: getfield profile
  12: invokestatic createSkinLookup(GameProfile)Supplier   // <-- 这里才调
  15: putfield skinLookup
  18: aload_0
  19: getfield skinLookup
  22: invokeinterface Supplier.get()
  27: checkcast PlayerSkin
  30: areturn
```

### 1.2 含义

- `createSkinLookup` **不在构造期执行**，而是在游戏首次渲染该玩家（调用 `getSkin()`）时执行，且**只执行一次**（结果缓存进 `skinLookup`）。
- 它是 `private static`，所以 `@Inject` handler **必须是 `private static`**，否则 Mixin 应用失败（检查启动日志的 mixin 错误）。
- 因为是 static 方法、参数即 `GameProfile profile`，`@Inject(method="createSkinLookup", at=@At("HEAD"), cancellable=true)` + `CallbackInfoReturnable<Supplier<PlayerSkin>>` 是合法写法。
- 关键：必须在 handler 里 `cir.setReturnValue(...)`，否则原方法体继续用 `this.profile`（原始 profile）跑 → 拦截形同未生效。

---

## ② SkinManager.createLookup 流程

### 2.1 字节码事实（`javap -p -c net.minecraft.client.resources.SkinManager`）

```
public Supplier<PlayerSkin> createLookup(GameProfile profile, boolean requireSecure);
  0: aload_0
  1: aload_1
  2: invokevirtual get(GameProfile)CompletableFuture    // <-- VFP @Redirect 命中这里
  5: astore_3                                            // future
  6: aload_1
  7: invokestatic DefaultPlayerSkin.get(GameProfile)PlayerSkin
  10: astore 4                                           // defaultSkin
  12: getstatic SharedConstants.DEBUG_DEFAULT_SKIN_OVERRIDE
  15: ifeq 26
  18: ... invokedynamic get -> Supplier(defaultSkin)     // debug 短路
  25: areturn
  26: aload_3
  27: aconst_null
  28: invokevirtual CompletableFuture.getNow(null)       // future.getNow(null)
  31: checkcast Optional
  34: astore 5                                           // opt
  36: aload 5
  38: ifnull 70                                          // opt==null 走慢路径
  41: aload 5
  43: iload_2
  44: invokedynamic test(Z)Predicate                     // s -> !requireSecure || s.secure()
  49: invokevirtual Optional.filter
  52: aload 4
  54: invokevirtual Optional.orElse(defaultSkin)
  57: checkcast PlayerSkin
  60: astore 6
  62: ... invokedynamic get -> Supplier(skin)            // 快路径：返回已就绪皮肤
  69: areturn
  70: aload_3
  71: iload_2
  72: aload 4
  74: invokedynamic get(future, requireSecure, defaultSkin)Supplier  // 慢路径：每次 get() 重读 future
  79: areturn
```

`get(GameProfile)` 方法体（**CSL 改写目标**）：
```
public CompletableFuture<Optional<PlayerSkin>> get(GameProfile profile);
  0: getstatic SharedConstants.DEBUG_DEFAULT_SKIN_OVERRIDE
  3: ifeq 19
  ... (debug 短路)
  19: aload_0
  20: getfield services
  23: invokevirtual sessionService()
  26: aload_1                       // profile
  27: invokeinterface MinecraftSessionService.getPackedTextures(GameProfile)Property
  32: astore_2                      // textures（从 profile.properties 读 textures Property）
  33: aload_0
  34: getfield skinCache
  37: new SkinManager$CacheKey       // <-- CSL 用 ASM 把这里换成 FakeCacheKey.createFakeCacheKey
  40: dup
  41: aload_1
  42: invokevirtual GameProfile.id()
  45: aload_2                        // textures Property
  46: invokespecial CacheKey.<init>(UUID, Property)V
  49: invokeinterface LoadingCache.getUnchecked(CacheKey)
  54: checkcast CompletableFuture
  57: areturn
```

### 2.2 关键结论

1. **`createLookup` 第一行就调 `this.get(profile)`**（字节码偏移 2，`invokevirtual get`）。这正是 VFP `@Redirect` 的命中点（见 ③）。
2. `get(profile)` 从 `profile.properties` 读 `textures` Property。**如果传入的新 GameProfile 没有 `textures` 属性，`getPackedTextures` 返回 `null`**，CacheKey 的 `packedTextures = null`。
3. CacheLoader（`SkinManager$1.load`）的 `lambda$load$0` 检查 `packedTextures == null → 返回 MinecraftProfileTextures.EMPTY`（见 `SkinManager$1.lambda$load$0` 字节码偏移 5-12）。EMPTY textures → `registerTextures` 走 `skin == null` 分支 → `DefaultPlayerSkin.get(uuid)` → **Steve**。
4. 所以**若 CSL 未安装**，用户用「真实用户名 + 无 textures」构造的新 GameProfile 调 `createLookup`，必然得到 Steve。用户名根本没机会被使用——vanilla `get()` 只认 `profile.properties` 里的 textures，不认用户名。

---

## ③ ViaFabricPlus 与 CSL 拦截冲突

### 3.1 VFP 的 @Redirect（`ViaVersion/ViaFabricPlus` `ver/26.2` 分支，`MixinSkinManager.java`）

```java
@Mixin(SkinManager.class)
public abstract class MixinSkinManager {

    @Redirect(method = "createLookup",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/resources/SkinManager;get(Lcom/mojang/authlib/GameProfile;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<Optional<PlayerSkin>> fetchGameProfileProperties(SkinManager instance, GameProfile profile) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_20)
                && !profile.properties().containsKey("textures")) {
            // ≤1.20 服务器且 profile 无 textures：先按 UUID 回 Mojang 拉完整 profile
            return CompletableFuture.supplyAsync(() -> {
                final ProfileResult profileResult = Minecraft.getInstance().services().sessionService().fetchProfile(profile.id(), true);
                return profileResult == null ? profile : profileResult.profile();
            }, Util.backgroundExecutor()).thenCompose(instance::get);   // <-- 最终仍调 instance.get(...)
        } else {
            // 现代服务器 或 profile 已含 textures：直接走原路
            return instance.get(profile);                                 // <-- instance.get(profile)
        }
    }
}
```

### 3.2 VFP redirect 的两条分支都落到 `instance.get(...)`

- **else 分支**（现代服务器 / profile 有 textures）：`return instance.get(profile);` —— 直接调 CSL 改写过的 `get`。
- **if 分支**（≤1.20 服务器且无 textures）：异步 `fetchProfile(profile.id(), true)`，成功则用 Mojang 返回的 profile，失败回退原 profile，最后 `.thenCompose(instance::get)` —— 仍调 CSL 改写过的 `get`。

> 因此 **VFP 的 redirect 不会绕过 CSL**。CSL 改写的是 `get` 方法体（字节码层面），VFP redirect 改写的是 `createLookup` 里调用 `get` 的那个 call site；无论 redirect 把参数换成什么，最终执行的都是被 CSL 改写过的 `get` 方法体。

### 3.3 VFP redirect 对用户 Mixin 的影响（≤1.20 分支的隐患）

用户用「真实用户名 + 假 UUID」构造 `newProfile` 调 `createLookup(newProfile, false)`：
- 偏移 2 的 `get(newProfile)` 被 VFP redirect 拦下。
- 若当前是 ≤1.20 服务器：VFP 调 `fetchProfile(newProfile.id(), true)`。**`newProfile.id()` 是假 UUID → Mojang 查不到 → 返回 null → 回退 `newProfile`**（真实用户名、无 textures）→ `instance.get(newProfile)` → CSL 触发，用 `newProfile.name()` 查 LittleSkin。✓ 链路通。
- 若是现代服务器：直接 `instance.get(newProfile)` → CSL 触发。✓ 链路通。

> 结论：**只要 CSL 在场，VFP 不挡路**。VFP 只是在 ≤1.20 场景多了一次「按 UUID 回 Mojang 拉 profile」的网络往返（对假 UUID 必失败，略增延迟），不影响最终走 CSL。

### 3.4 但用户的 Mixin 无法「覆盖」VFP

用户 Mixin 在 `PlayerInfo.createSkinLookup`（PlayerInfo 类），VFP Mixin 在 `SkinManager.createLookup`（SkinManager 类）。两者**不同类、不同方法**。用户的 Mixin 调 `skinManager.createLookup(...)` 时，进入的是**已被 VFP 改写过的 `createLookup` 方法**，VFP redirect 照常触发。**不存在「用户 Mixin 优先级高于 VFP 而绕过 redirect」的机制**——redirect 是方法体内的 call site 改写，不是可被外层调用绕过的东西。

---

## ④ Mixin 不生效原因（核心诊断）

按可能性从高到低：

### 原因 A：handler 未取消原方法（最可能）

`createSkinLookup` 是 `private static` 且**有返回值** `Supplier<PlayerSkin>`。若用户写成：

```java
// ❌ 错误示范：只调用，没 setReturnValue
@Inject(method = "createSkinLookup", at = @At("HEAD"), cancellable = true)
private static void hook(GameProfile profile, CallbackInfoReturnable<Supplier<PlayerSkin>> cir) {
    GameProfile newProfile = new GameProfile(profile.id(), realUsername);
    Minecraft.getInstance().getSkinManager().createLookup(newProfile, false);  // 结果丢弃！
}
```

原方法体继续以 `this.profile`（原始 profile）执行并返回，拦截完全无效。必须：

```java
// ✅ 正确：setReturnValue 取消原方法
@Inject(method = "createSkinLookup", at = @At("HEAD"), cancellable = true)
private static void hook(GameProfile profile, CallbackInfoReturnable<Supplier<PlayerSkin>> cir) {
    GameProfile newProfile = new GameProfile(profile.id(), realUsername);
    cir.setReturnValue(Minecraft.getInstance().getSkinManager().createLookup(newProfile, false));
}
```

> 若日志里有用户自己的 "setSkin called" 但皮肤仍 Steve，极可能是这种「调了但没返回」。

### 原因 B：CSL 未安装 / 未对 26.2 生效

若环境里没有 CSL：
- `get(newProfile)` 走 vanilla：`getPackedTextures(newProfile)` = `null`（新 profile 无 textures 属性）。
- CacheKey = `(uuid, null)` → CacheLoader `lambda$load$0` 偏移 5-12：`packedTextures == null → return EMPTY`。
- `registerTextures(EMPTY)` → `skin == null` → `DefaultPlayerSkin.get(uuid)` → **Steve**。
- **用户名从未被使用**（vanilla 只认 `profile.properties.textures`）。

判定方法：查启动日志是否有 CSL 的 Bootstrap transformer 日志（`customskinloader:skin-manager-patch` 等 patch 名）；或检查 mods 文件夹是否有 `CustomSkinLoader*.jar`。

### 原因 C：CSL 在场但 LittleSkin 查不到该用户名

CSL 用 `newProfile.name()` 查 `https://littleskin.cn/csl/{username}.json`（见 ⑤）。若该用户名未在 LittleSkin 注册 → miss → CSL 返回空 UserProfile → 仍 Steve。判定方法：浏览器直接访问该 URL 看是否 404。

### 原因 D：handler 非 static 导致 Mixin 应用失败

`createSkinLookup` 是 static，handler必须也是 `static`。若漏写 `static`，Mixin 在启动期报错并跳过该 hook（日志有 `MixinApplyError` / `Handler is not static` 类错误）。判定方法：grep 启动日志 `Mixin` / `createSkinLookup`。

### 原因 E（可排除）：构造期未调用

用户原描述担心「PlayerInfo 构造时 createSkinLookup 还没调用，后续有其他路径覆盖」。**字节码证伪**：构造器根本不调 `createSkinLookup`；它是 `getSkin()` 懒加载，且 `skinLookup` 一旦赋值就不再变。不存在「构造期覆盖」问题。只要 `getSkin()` 被调用时 Mixin 能正常 `setReturnValue`，就是唯一入口。

### 原因 F（次要）：首帧 Steve 属正常

`createLookup` 慢路径（偏移 70-79）返回的 Supplier 每次 `get()` 都 `future.getNow(null)`。CSL 查 LittleSkin 是异步网络请求，首帧 CF 未完成 → `getNow(null)` → 用 `defaultSkin`（Steve）。**几秒后 CF 完成，后续帧自动切到真实皮肤**。若 Steve 是永久的，不是这个原因；若只是开头几帧，属正常。

---

## ⑤ CSL 查询 LittleSkin 流程

### 5.1 拦截链 → 拿到 GameProfile

1. `SkinManager.get(profile)`（CSL `redirectCacheKeyConstruction`，`SkinManagerPatch.java:422`）：把字节码 `new CacheKey(uuid, textures)` 替换为 `invokestatic FakeCacheKey.createFakeCacheKey(uuid, textures, profile)`——**`ALOAD 1` 把 `get` 的入参 `profile` 整个塞进 FakeCacheKey**。

2. `SkinManager$1.lambda$load$0(CacheKey, Services)`（CSL `replaceUnpackTexturesWithFakeSkinCache`，`SkinManagerPatch.java:498`）：把 `sessionService.unpackTextures(property)` 替换为 `FakeSkinManager.loadSkinFromCache(sessionService, property, cacheKey)`。

3. `FakeSkinManager.loadSkinFromCache(SessionService, Property, CacheKey)`（`FakeSkinManager.java`）：
   ```java
   public static Object loadSkinFromCache(MinecraftSessionService sessionService, Property property, SkinManager$CacheKey cacheKey) {
       if (cacheKey instanceof FakeCacheKey) {
           return FakeCacheKey.createMinecraftProfileTextures(
               loadSkinFromCache(sessionService, ((FakeCacheKey) cacheKey).profile(), false));
       }
       return sessionService.unpackTextures(property);   // 非 FakeCacheKey 才回退 vanilla
   }
   ```
   关键：**只有 `cacheKey instanceof FakeCacheKey` 才走 CSL**。FakeCacheKey 携带的 `profile()` 就是步骤 1 塞进去的 GameProfile。

4. 内部调 `getUserProfile(sessionService, profile, false)` = `ModelManager0.fromUserProfile(CustomSkinLoader.loadProfile(profile))`。`CustomSkinLoader.loadProfile(profile)` 用 `profile.getName()` 查各皮肤源。

### 5.2 LittleSkin（CustomSkinAPI）查询协议

`CustomSkinAPI.java`（`Common/src/main/java/customskinloader/loader/jsonapi/CustomSkinAPI.java`）：

```java
public static class LittleSkin extends JsonAPILoader.DefaultProfile {
    @Override public String getName()     { return "LittleSkin"; }
    @Override public int  getPriority()   { return 200; }
    @Override public String getRoot()     { return "https://littleskin.cn/csl/"; }
}

@Override
public String toJsonUrl(String root, String username) {
    return root + username + ".json";        // https://littleskin.cn/csl/{username}.json
}
```

- **查询纯靠 `username`**（即 `GameProfile.getName()`），与 profile 的 `textures` 属性、UUID 都无关。
- 返回 JSON 形如 `{ "username": ..., "skins": {"default": "abc.png", "slim": "def.png"}, "cape": "..." }`，CSL 据此构造 `UserProfile`，皮肤 URL = `root + "textures/" + 文件名`。

### 5.3 CSL 的查询优先级（标准流程）

`CustomSkinLoader.loadProfile(profile)` 的常规顺序（基于 CSL 既有架构）：
1. **本地缓存**：以 profile 的 credential（基于 `GameProfile.toString()`）为 key 查 `CustomSkinLoader` 本地缓存目录。命中且未过期 → 直接返回。
2. **在线皮肤源（loadlist）**：按配置的皮肤站优先级依次查。LittleSkin（priority 200）< BlessingSkin（priority 300）。每站用 `username` 拼 JSON URL 查询。
3. **Mojang**：若 skin properties 里已有 textures，作为最后兜底。
4. 全 miss → 返回空 UserProfile → 回退 vanilla `DefaultPlayerSkin` → Steve。

> 因 ⑤2，**只要 `newProfile.name()` 是真实用户名且该名在 LittleSkin 已注册，CSL 必命中**，与 `newProfile` 有没有 `textures` 属性无关。

### 5.4 对用户场景的判定

用户的 `newProfile` = `new GameProfile(原UUID, 真实用户名)`（无 textures 属性）：
- CSL 在 `get(newProfile)` 里捕获这个 `newProfile` → FakeCacheKey.profile = newProfile。
- CacheLoader 走 CSL → `loadProfile(newProfile)` → 用 `真实用户名` 查 LittleSkin → 命中则返回真实皮肤。
- **链路本身是通的**。若仍 Steve，回到 ④ 的 A/B/C 三项排查。

---

## 附录：诊断检查清单

| 检查项 | 方法 | 预期 |
|---|---|---|
| Mixin 是否应用成功 | 启动日志搜 `createSkinLookup` / `MixinPlayerInfo` | 无 `MixinApplyError` |
| handler 是否 static | 看自己源码 | `private static void hook(...)` |
| 是否 `setReturnValue` | 看自己源码 | `cir.setReturnValue(createLookup(...))` |
| CSL 是否在场 | mods 目录 + 日志搜 `customskinloader:skin-manager-patch` | 有 patch 应用日志 |
| 用户名是否在 LittleSkin | 浏览器访问 `https://littleskin.cn/csl/{用户名}.json` | 返回 JSON 而非 404 |
| 是否 ≤1.20 服务器 | VFP 协议版本 | 若是，VFP 会先按 UUID 查 Mojang（假 UUID 必失败，仅增延迟） |
| Steve 是否永久 | 观察几秒后是否切换 | 若仅首帧 Steve 后恢复，属正常异步延迟 |

---

## 附录：关键源码引用

- **PlayerInfo / SkinManager 字节码**：`javap -p -c` on `minecraft-merged-deobf-26.2.jar`（见正文 ①②）
- **CSL `SkinManagerPatch.java`**：`xfl03/MCCustomSkinLoader` `15-develop`，`Bootstrap/Core/src/main/java/customskinloader/bootstrap/transformer/patch/SkinManagerPatch.java`
  - `redirectCacheKeyConstruction` (line 422)：替换 `new CacheKey` → `FakeCacheKey.createFakeCacheKey(uuid, property, profile)`
  - `replaceUnpackTexturesWithFakeSkinCache` (line 498)：替换 `unpackTextures` → `FakeSkinManager.loadSkinFromCache(...)`
- **CSL `FakeSkinManager.java`**：`Common/src/main/java/customskinloader/fake/FakeSkinManager.java`
  - `loadSkinFromCache(SessionService, Property, CacheKey)`：`cacheKey instanceof FakeCacheKey` 才走 CSL
  - `FakeCacheKey.createFakeCacheKey`：把 GameProfile 存进 CacheKey
- **CSL `CustomSkinAPI.java`**：`Common/src/main/java/customskinloader/loader/jsonapi/CustomSkinAPI.java`
  - `LittleSkin` root = `https://littleskin.cn/csl/`，`toJsonUrl` = `root + username + ".json"`
- **VFP `MixinSkinManager.java`**：`ViaVersion/ViaFabricPlus` `ver/26.2`，`src/main/java/com/viaversion/viafabricplus/injection/mixin/features/skin_loading/MixinSkinManager.java`
  - `@Redirect` on `createLookup`'s `get` invocation；两条分支都调 `instance.get(...)`
