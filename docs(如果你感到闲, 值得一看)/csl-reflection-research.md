# CustomSkinLoader (CSL) 反射注入研究

> 目的：把 Bedrock 服务器收到的 BE 玩家皮肤注入 CSL 内部缓存，让 JE 客户端用 CSL 渲染。
> CSL 无公开 API，本文件记录通过反射访问所需的准确字段结构。

- 源仓库：https://github.com/xfl03/MCCustomSkinLoader
- 研究分支：`15-develop`（commit `ebea66d2611ee0f04b3864dd939e5eae4ba51c9e`，2026-05-21）
- 研究日期：2026-08-06
- 源码根：仓库下 `Common/src/main/java/customskinloader/`（源码不在仓库根，而在 `Common` 子模块；运行时由 Bootstrap jar 重映射后加载）

---

## ① CSL 主类与入口

| 项 | 值 |
|---|---|
| 主类全限定名 | `customskinloader.CustomSkinLoader` |
| 源文件 | `Common/src/main/java/customskinloader/CustomSkinLoader.java` |
| 类修饰符 | `public class`（非 final，无显式构造器，隐式 public 无参构造） |
| 是否 Mod 入口 | 否。CSL 的 Fabric/Forge 入口在 `customskinloader.mod.*` 子包（如 `FabricMod` / `ForgeMod`），`CustomSkinLoader` 是核心静态门面类，所有状态以 static 字段持有。 |
| 关键 static 字段 | `DATA_DIR`、`LOG_FILE`、`CONFIG_FILE`、`GSON`、`logger`、`config`、`profileCache`、`THREAD_POOL`、`PROFILE_THREAD_POOL` |

`CustomSkinLoader` 类关键声明（节选）：

```java
public class CustomSkinLoader {
    public static final File DATA_DIR = ...;
    public static final Config config = initConfig();
    private static final ProfileCache profileCache = new ProfileCache();   // ← 注入目标
    public static final ExecutorService THREAD_POOL = ...;

    public static UserProfile loadProfile(GameProfile gameProfile) { ... }
    public static UserProfile loadProfile0(GameProfile gameProfile, boolean isSkull) { ... }
    public static Map<...> loadProfileFromCache(final GameProfile gameProfile) { ... }
}
```

`profileCache` 是 `private static final` 实例字段，保存唯一的 `ProfileCache` 实例。我们不需要替换这个实例，只需反射拿到引用再调用其 public 方法，所以不需要解除 `final`。

---

## ② 缓存字段全路径

### 主入口字段

| 全路径 | 类型 | 修饰符 |
|---|---|---|
| `customskinloader.CustomSkinLoader.profileCache` | `customskinloader.profile.ProfileCache` | `private static final` |

反射获取方式：

```java
Class<?> cslClass = Class.forName("customskinloader.CustomSkinLoader");
Field f = cslClass.getDeclaredField("profileCache");
f.setAccessible(true);                 // 必须，因为是 private
Object profileCache = f.get(null);     // static 字段，参数传 null
// 不需要解除 final：我们只读取引用，不替换字段值
```

### ProfileCache 内部字段（`customskinloader.profile.ProfileCache`）

```java
public class ProfileCache {
    public static File PROFILE_CACHE_DIR = new File(...);

    private Map<String, CachedProfile> cachedProfiles = new ConcurrentHashMap<>();
    private Map<String, UserProfile>   localProfiles   = new ConcurrentHashMap<>();
    private Map<String, Deque<Function<UserProfile, ?>>> profileLoaders = new ConcurrentHashMap<>();

    public boolean isExist(String username) {...}
    public boolean isLoading(String username) {...}
    public boolean isReady(String username) {...}
    public boolean isExpired(String username) {...}
    public UserProfile getProfile(String username) {...}
    public long getExpiry(String username) {...}
    public UserProfile getLocalProfile(String username) {...}
    public Function<UserProfile, ?> getLastLoader(String username) {...}
    public void setLoading(String username, boolean loading) {...}
    public void updateCache(String username, UserProfile profile) {...}
    public void updateCache(String username, UserProfile profile, boolean saveLocalProfile) {...}
    private CachedProfile getCachedProfile(String username) {...}
    private UserProfile loadLocalProfile(String username) {...}
    private void saveLocalProfile(String username, UserProfile profile) {...}
}

class CachedProfile {                  // 包级私有，不能直接 new
    public UserProfile profile;
    public long expiryTime = 0;
    public boolean loading = false;
}
```

注意 `CachedProfile` 是包级私有的（无 `public` 修饰符），无法从 `com.moxi.bedrockskinbridge` 包直接 `new`。要修改单个条目只能通过反射或调用 `ProfileCache` 的 public 方法。

---

## ③ 缓存 Map 的 key/value 类型与 value 的字段

### `cachedProfiles`（主缓存）

| 维度 | 类型 | 说明 |
|---|---|---|
| Map 类型 | `ConcurrentHashMap<String, CachedProfile>` | 声明为 `Map<String, CachedProfile>`，运行时实例是 ConcurrentHashMap |
| Key | **`String`**，**已经 `toLowerCase()`** | 见下文「⚠️ key 的真实来源」 |
| Value | `CachedProfile`（包级私有类） | 含 `profile` / `expiryTime` / `loading` 三个 public 字段 |

**⚠️ key 的真实来源（关键陷阱）**：

`ProfileCache` 的方法参数名都叫 `username`，但 CSL 调用时传入的并不是单纯的用户名，而是 **credential**。看 `CustomSkinLoader.loadProfile`：

```java
String credential = MinecraftUtil.getCredential(gameProfile);
...
profileCache.updateCache(credential, profile0);
```

而 `MinecraftUtil.getCredential` 的实现是：

```java
public static String getCredential(GameProfile profile) {
    return profile == null ? null : profile.toString();
}
```

也就是 **credential = `GameProfile.toString()`**，会被 `toLowerCase()` 后作为 map key。Mojang authlib 的 `GameProfile.toString()` 输出形如 `GameProfile@<hash>{id=<uuid>, name=<name>}`（Apache commons ToStringBuilder 格式）。

**结论**：要注入一个 BE 玩家的皮肤，**必须用 JE 客户端看到的那个 BE 玩家的 `GameProfile.toString().toLowerCase()` 作为 key**，不能用单纯的用户名。否则 CSL 在 `loadProfile` / `loadProfileFromCache` 时查不到这个条目。

### `UserProfile`（值类型，`customskinloader.profile.UserProfile`）

```java
public class UserProfile {
    public final static UserProfile NULL = new UserProfile();

    public String skinUrl   = null;   // 皮肤直链 URL
    public String model     = null;   // "default" 或 "slim"
    public String capeUrl   = null;   // 披风直链 URL
    public String elytraUrl = null;   // 鞘翅直链 URL（14.5+）

    public void put(ModelManager0.Model model, String url) {...}
    public boolean isEmpty() {...}
    public boolean isFull() {...}
    public boolean hasSkinUrl() {...}
    public void mix(UserProfile profile) {...}
    public String toString() {...}
    public String toString(long expiry) {...}
}
```

| 字段 | 类型 | 修饰符 | 取值 |
|---|---|---|---|
| `skinUrl` | `String` | `public` | 直接 URL（http/https 或 CSL 内部 fake URL） |
| `model` | `String` | `public` | `"default"` 或 `"slim"`（`null` 视为 default） |
| `capeUrl` | `String` | `public` | 直接 URL，`null` 表示无披风 |
| `elytraUrl` | `String` | `public` | 直接 URL，`null` 表示无鞘翅 |

所有字段都是 `public`，反射 set 时 `setAccessible(true)` 多余但无害。UserProfile 有隐式 public 无参构造器，可直接 `getDeclaredConstructor().newInstance()`。

### `ModelManager0.Model` 枚举（参考）

```java
public enum Model { SKIN_DEFAULT, SKIN_SLIM, CAPE, ELYTRA }
```

字符串到枚举映射：`default→SKIN_DEFAULT`、`slim→SKIN_SLIM`、`cape→CAPE`、`elytra/elytron→ELYTRA`。注入时直接填字符串字段即可，无需走枚举。

---

## ④ 推荐的反射注入代码模式（伪代码）

### 模式 A：调用 public `updateCache`（推荐，最稳）

```java
// 1. 拿 profileCache 实例
Class<?> cslClass = Class.forName("customskinloader.CustomSkinLoader");
Field f = cslClass.getDeclaredField("profileCache");
f.setAccessible(true);
Object profileCache = f.get(null);

// 2. 拿 UserProfile 类与字段
Class<?> userProfileClass = Class.forName("customskinloader.profile.UserProfile");
Field skinUrlField   = userProfileClass.getDeclaredField("skinUrl");
Field modelField     = userProfileClass.getDeclaredField("model");
Field capeUrlField   = userProfileClass.getDeclaredField("capeUrl");
Field elytraUrlField = userProfileClass.getDeclaredField("elytraUrl");   // 别漏

// 3. 构造 UserProfile
Object profile = userProfileClass.getDeclaredConstructor().newInstance();
skinUrlField.set(profile, skinUrl);
modelField.set(profile, "slim".equalsIgnoreCase(model) ? "slim" : "default");
if (capeUrl != null)   capeUrlField.set(profile, capeUrl);
if (elytraUrl != null) elytraUrlField.set(profile, elytraUrl);

// 4. 计算与 CSL 一致的 credential key
//    必须用 JE 端那个 BE 玩家的 GameProfile.toString()，不能只用 username
String credential = gameProfile.toString();    // com.mojang.authlib.GameProfile

// 5. 调用 updateCache(credential, profile, saveLocalProfile=false)
//    saveLocalProfile=false 避免把临时 BE 皮肤写入 .minecraft/CustomSkinLoader/ProfileCache/*.json
Method update = profileCache.getClass().getMethod(
        "updateCache", String.class, userProfileClass, boolean.class);
update.invoke(profileCache, credential, profile, false);
```

`updateCache(credential, profile, false)` 内部会：
1. `cachedProfiles.computeIfAbsent(credential.toLowerCase(), k -> new CachedProfile())`
2. `cp.profile = profile`
3. `cp.expiryTime = TimeUtil.getUnixTimestamp(config.cacheExpiry)`（默认会过期，需定期重注入）
4. `saveLocalProfile` 跳过（因为传了 false）

### 模式 B：直接改 `cachedProfiles` map（仅当需要绕过 expiryTime 时）

```java
Field cacheMapField = profileCache.getClass().getDeclaredField("cachedProfiles");
cacheMapField.setAccessible(true);
Map<String, ?> cacheMap = (Map<String, ?>) cacheMapField.get(profileCache);

// CachedProfile 是包级私有，只能反射构造
Class<?> cachedProfileClass = Class.forName("customskinloader.profile.CachedProfile");
Object cp = cachedProfileClass.getDeclaredConstructor().newInstance();
cachedProfileClass.getField("profile").set(cp, userProfile);
cachedProfileClass.getField("expiryTime").setLong(cp, Long.MAX_VALUE);  // 永不过期
cachedProfileClass.getField("loading").setBoolean(cp, false);

cacheMap.put(credential.toLowerCase(), cp);
```

### 注入时机

- CSL 的 `loadProfile(GameProfile)` 由原版皮肤系统回调。它先查 `profileCache.isReady(credential)`，命中则直接用缓存，不再走网络。所以**只要在玩家进入视野前注入缓存即可生效**。
- 若 BE 玩家已在线才注入，CSL 可能已为该 credential 写入过期条目；`updateCache` 会覆盖 `profile` 与 `expiryTime`，但若此前 `loading=true` 卡住，需额外 `setLoading(credential, false)`。
- 注入后客户端仍可能已缓存了原版皮肤纹理（`TextureManager` 层）。CSL 用 `FakeMinecraftProfileTexture` 包装 URL，URL 变了即可重新下载，通常无需手动清 Minecraft 纹理缓存。

### credential 来源（BE 玩家）

- 通过 Geyser/Floodgate 进 JE 的 BE 玩家，JE 端会拿到一个 `GameProfile`（带 Floodgate 伪造的 UUID 与原 BE xuid 派生的 name）。
- 必须用**同一个** `GameProfile.toString()` 作为 credential，否则 key 不匹配。
- 若拿不到 GameProfile 对象，只能凭 username 构造一个等价字符串，但这依赖 authlib `GameProfile.toString()` 的内部格式，**不稳健**，应优先拿 GameProfile。

---

## ⑤ CSL 版本与 MC 26.2 兼容性

| 项 | 值 |
|---|---|
| `build.info.json` 的 `mod_version` | **15.0.1** |
| `edition` | `Universal`（一个 jar 跨 Forge/NeoForge/Fabric/Quilt） |
| 支持 loaders | `fabric`、`forge`、`neoforge`、`quilt` |
| 支持 game_versions | 1.8 ~ 1.21.11，**以及 `26.1` / `26.1.1` / `26.1.2` / `26.2`** ✅ |
| 支持 Java | 8 ~ 25 |
| 默认分支 | `15-develop` |

**结论**：CSL 15.0.1 官方支持 Minecraft 26.2 + Fabric Loader，与本项目目标环境（Fabric Loader 0.19.3 / MC 26.2）兼容。Universal 版本机制：用户安装 Bootstrap jar，运行时由 Bootstrap 重映射 `Common-15.0.1.jar` 到当前 MC 映射命名空间。

---

## ⑥ 与当前 CSLInjector.java 的差异

对照 `c:\Users\Administrator\Documents\WIKI2\BedrockSkinBridge\src\main\java\com\moxi\bedrockskinbridge\skin\CSLInjector.java`：

| # | 当前代码 | 实际情况 / 建议 |
|---|---|---|
| 1 | `Class.forName("customskinloader.CustomSkinLoader")` | ✅ 正确 |
| 2 | `getDeclaredField("profileCache")` + `setAccessible(true)` + `get(null)` | ✅ 正确（private static final，只需 accessible，不需解 final） |
| 3 | `Class.forName("customskinloader.profile.UserProfile")` | ✅ 正确 |
| 4 | `getDeclaredField("skinUrl")` / `"model"` / `"capeUrl"` + `setAccessible(true)` | ✅ 字段名正确。字段本身是 public，`setAccessible` 多余但无害 |
| 5 | **未处理 `elytraUrl` 字段** | ⚠️ 建议补上 `elytraUrl` 反射，BE 皮肤可能带鞘翅 |
| 6 | `getDeclaredConstructor().newInstance()` 创建 UserProfile | ✅ 正确（UserProfile 有隐式 public 无参构造） |
| 7 | `updateCacheMethod = profileCache.getClass().getMethod("updateCache", String.class, userProfileClass)` | ⚠️ 这拿的是 2 参数版本 `updateCache(String, UserProfile)`，它会调用 `updateCache(..., config.enableLocalProfileCache)`。若 `enableLocalProfileCache=true`，会把 BE 皮肤写到 `.minecraft/CustomSkinLoader/ProfileCache/<name>.json`，污染本地缓存。**建议改用 3 参数版本 `updateCache(String, UserProfile, boolean.class)` 并传 `false`**。 |
| 8 | `updateCacheMethod.invoke(profileCache, username, userProfile)` —— **key 用 `username`** | ❌ **不正确**。CSL 内部 key 是 `GameProfile.toString().toLowerCase()`，不是单纯 username。用 username 作 key 会导致 CSL `loadProfile` 时查不到该条目（因为它用 credential 查）。**必须改为 `gameProfile.toString()`**。 |
| 9 | `injectSkin(String username, String skinUrl, String model, String capeUrl)` 签名 | ⚠️ 缺少 `GameProfile` 参数（或 credential 字符串），导致无法算出正确 key。建议增加 `GameProfile` 参数或单独的 `credential` 参数。 |
| 10 | 无过期处理 | ⚠️ `updateCache` 设的 `expiryTime` 会过期（默认 `config.cacheExpiry`）。BE 玩家长在线时需要周期性重注入，或改用模式 B 直接写 map 并设 `Long.MAX_VALUE`。 |

### 建议的最小修复清单

1. **改 key 来源**：`username` → `gameProfile.toString()`（或在调用方先算好 credential 传入）。
2. **改用 3 参数 `updateCache(String, UserProfile, boolean)`** 并传 `false`，避免污染本地 ProfileCache 目录。
3. **补 `elytraUrl` 字段**反射。
4. **签名调整**：`injectSkin` 增加 `GameProfile`（或 `credential`）参数。
5. （可选）增加周期重注入逻辑，应对 cacheExpiry 过期。

---

## 附：关键源文件引用

| 文件 | 仓库路径 |
|---|---|
| 主类 | `Common/src/main/java/customskinloader/CustomSkinLoader.java` |
| 缓存类 | `Common/src/main/java/customskinloader/profile/ProfileCache.java` |
| 用户档案类 | `Common/src/main/java/customskinloader/profile/UserProfile.java` |
| 模型管理 | `Common/src/main/java/customskinloader/profile/ModelManager0.java` |
| credential 来源 | `Common/src/main/java/customskinloader/utils/MinecraftUtil.java`（`getCredential` = `GameProfile.toString()`） |
| Authlib 反射工具 | `Common/src/main/java/customskinloader/utils/TextureUtil.java` |
| 版本声明 | `build.info.json`（mod_version=15.0.1，game_versions 含 26.2） |
