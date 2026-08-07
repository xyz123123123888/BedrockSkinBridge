# BE 皮肤 5D/7D/Persona/Premium 识别与 JE 回退判定

> **研究目标**：精确搞清如何从 `SkinData` 字段判断 BE 皮肤是 5D/7D（自定义几何）/Persona/Premium 皮肤，以及 JE 端在什么条件下必须回退为 Steve。
>
> - **ViaBedrock 仓库**：https://github.com/RaphiMC/ViaBedrock （`main` 分支）
> - **可选客户端 Mod**：[Oryxel/ViaBedrockUtility](https://github.com/Oryxel/ViaBedrockUtility)、[Camotoy/BedrockSkinUtility](https://github.com/Camotoy/BedrockSkinUtility)
> - **研究日期**：2026-08-07
> - **研究方法**：逐个 fetch ViaBedrock 与两个客户端 Mod 的源码，带 URL + 行号引用
>
> **关联文档**：协议字段全表见 [`be-player-skin-protocol.md`](./be-player-skin-protocol.md)；BE→JE 转发链路见 [`viabedrock-skin-pipeline.md`](./viabedrock-skin-pipeline.md)。本文聚焦"识别 + 回退判定"。

---

## 结论速览（TL;DR）

| 皮肤类型 | `persona` | `premium` | `geometryData` | `personaPieces` | ViaBedrock 是否转发 | JE 端结果 |
|---|---|---|---|---|---|---|
| 普通贴图皮肤（含标准 humanoid） | `false` | `false` | `""` 或 `"null"` | 空 List | **转发** | 用贴图 + 默认 JE 模型渲染 ✅ |
| 5D/7D 自定义几何皮肤 | `false` | 任意 | 非空且 ≠`"null"`（自定义几何 JSON） | 空 List | **转发**（含 geometryData + skinResourcePatch） | 需客户端 Mod 转几何；无 Mod 则贴图错位/回退 ⚠️ |
| Persona（角色编辑器）皮肤 | `true` | 任意 | 任意 | 非空 List | **不转发**（`sendSkin` 开头 `persona()` 为 true 直接 return） | 保持 Steve ❌→回退 |
| Premium（Marketplace 付费）皮肤 | 通常 `false` | `true` | 视皮肤而定（可能含自定义几何） | 通常空 | **转发**（ViaBedrock 不检查 `premium`） | 同 5D/7D：有几何需 Mod，无几何可渲染 |
| `skinData == null` | 任意 | 任意 | 任意 | 任意 | **不转发**（`sendSkin` 开头 null 检查） | 保持 Steve ❌→回退 |

**一句话结论**：ViaBedrock 在 [`BedrockSkinUtilityInterface.sendSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java) 与 [`ViaBedrockUtilityInterface.sendSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java) 开头用 `if (skin.skinData() == null || skin.persona()) return;` 拦截 Persona 皮肤；5D/7D/Premium 皮肤**不被拦截**，会照常转发到客户端 Mod 通道，由客户端 Mod 决定是否能渲染（有 `geometryData` 则需把 BE 几何转成 JE 模型，转换失败则退化为只贴图或回退）。

---

## ① SkinData 字段含义

### 1.1 record 定义

源码：[`SkinData.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/model/SkinData.java)

```java
public record SkinData(
    String skinId, String playFabId, String skinResourcePatch,
    BufferedImage skinData, List<AnimationData> animations, BufferedImage capeData,
    String geometryData, String geometryDataEngineVersion, String animationData,
    boolean premium, boolean persona, boolean capeOnClassic, boolean primaryUser,
    String capeId, String fullSkinId, String armSize, String skinColor,
    List<PersonaPieceData> personaPieces, List<PersonaPieceTintData> tintColors,
    boolean overridingPlayerAppearance
) {
    public record AnimationData(BufferedImage image, int type, float frames, int expression) {}
    public record PersonaPieceData(String id, String type, String packId, boolean defaultPiece, String productId) {}
    public record PersonaPieceTintData(String type, List<String> colors) {}
}
```

### 1.2 逐字段含义

| 字段 | 类型 | 含义 | 在识别中的用途 |
|---|---|---|---|
| `skinId` | `String` | 皮肤标识符（常为 UUID 或包内 ID） | 不用于判定 |
| `playFabId` | `String` | PlayFab 服务 ID | 不用于判定 |
| `skinResourcePatch` | `String` | **几何名称映射 JSON**，形如 `{"geometry":{"default":"geometry.humanoid.custom"}}` | **核心**：判断是否标准 humanoid（见 ②） |
| `skinData` | `BufferedImage` | 皮肤贴图（RGBA，任意尺寸） | **核心**：`null` → 回退 Steve |
| `animations` | `List<AnimationData>` | 动画帧贴图列表 | 不影响回退判定 |
| `capeData` | `BufferedImage` | 披风贴图，可为 `null` | 不影响回退判定 |
| `geometryData` | `String` | **自定义几何 JSON 字符串**（format_version 1.8.0 / 1.12.0） | **核心**：非空且 ≠`"null"` = 5D/7D 皮肤（见 ③） |
| `geometryDataEngineVersion` | `String` | 几何数据引擎版本（如 `"0.0.0"`） | 不用于判定 |
| `animationData` | `String` | 动画数据 JSON | 不用于判定 |
| `premium` | `boolean` | 是否 Marketplace 付费皮肤 | ViaBedrock **不检查**；仅作元数据 |
| `persona` | `boolean` | 是否 Character Creator（角色编辑器）皮肤 | **核心**：`true` → ViaBedrock 直接不转发（见 ③） |
| `capeOnClassic` | `boolean` | 经典皮肤是否带披风 | 不用于判定 |
| `primaryUser` | `boolean` | 是否主用户 | 不用于判定 |
| `capeId` | `String` | 披风 ID | 不用于判定 |
| `fullSkinId` | `String` | 完整皮肤 ID | 不用于判定 |
| `armSize` | `String` | 手臂尺寸：`"wide"` 或 `"slim"` | 不影响回退，但影响 JE 模型选 slim/wide |
| `skinColor` | `String` | 皮肤底色（如 `"#0"`） | 不用于判定 |
| `personaPieces` | `List<PersonaPieceData>` | Persona 部件列表（id/type/packId/defaultPiece/productId） | **辅助**：非空通常伴随 `persona=true` |
| `tintColors` | `List<PersonaPieceTintData>` | Persona 部件染色 | 不用于判定 |
| `overridingPlayerAppearance` | `boolean` | 是否覆盖玩家外观 | 不用于判定 |

### 1.3 协议线路顺序（≠ record 字段顺序）

源码：[`SkinType.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/model/SkinType.java)

`BedrockTypes.SKIN` 用 `SkinType` 读取。注意 **协议线路中 `premium`/`persona` 等 boolean 读在 `personaPieces`/`tintColors` 之后**（与 record 构造参数顺序不同）：

```
skinId, playFabId, skinResourcePatch, skinData, animations[], capeData,
geometryData, geometryDataEngineVersion, animationData, capeId, fullSkinId,
armSize, skinColor, personaPieces[], tintColors[],
premium, persona, capeOnClassic, primaryUser, overridingPlayerAppearance
```

> **重要**：在 BE 协议包（`PLAYER_SKIN` / `PLAYER_LIST`）中，`skinResourcePatch` 与 `geometryData` 都是**裸 UTF-8 字符串**（`BedrockTypes.STRING`，VarUInt 长度前缀），**不是 base64**。  
> 只有在 BE 客户端登录的 JWT claims 里它们才被 base64 编码——见 [`SkinProvider.getClientPlayerSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java) 用 `Base64.getEncoder().encodeToString(...)`。  
> ViaBedrock 解析后存入 `SkinData` 的已是**解码后的裸字符串**。

---

## ② skinResourcePatch 标准值

### 2.1 标准 JE 风格皮肤（vanilla humanoid）

源码：[`SkinProvider.java` — `getClientPlayerSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java)（ViaBedrock 为自己构造的默认 Steve 客户端皮肤 claims）：

```java
claims.put("SkinResourcePatch", Base64.getEncoder().encodeToString(
    "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8)));
claims.put("PremiumSkin", false);
claims.put("PersonaSkin", false);
claims.put("PersonaPieces", new ArrayList<>());
claims.put("ArmSize", "wide");
```

**标准值**：`{"geometry":{"default":"geometry.humanoid.custom"}}`

- `geometry.humanoid.custom` 是 BE vanilla 资源包 `geometry.json` 里的标准玩家几何标识符，对应 JE 的经典 64×64 双层皮肤模型（wide）。
- BE 原生 `geometry.json`（vanilla 资源包）里同时提供 `geometry.humanoid.customSlim`（slim 手臂，对应 JE `slim` 模型）。

### 2.2 5D/7D 自定义几何皮肤的 skinResourcePatch

5D/7D 是中文社区术语，本质是"皮肤包自带 `geometry.json` + 自定义几何标识符"的 BE 皮肤。其 `skinResourcePatch` 形如：

```json
{"geometry":{"default":"geometry.custom_player_skin_name"}}
```

其中 `geometry.custom_player_skin_name` 是该皮肤包 `geometry.json` 里声明的自定义 `identifier`（非 `humanoid.custom`）。此时 `geometryData` 字段必非空，包含该自定义几何的完整 bones/cubes JSON。

### 2.3 如何判断 resourcePatch 是"标准"还是"自定义"

判定逻辑（建议在 Fabric Mod 里复用）：

```java
/** 解析 skinResourcePatch，返回 default geometry identifier */
public static String getDefaultGeometryId(String skinResourcePatch) {
    if (skinResourcePatch == null || skinResourcePatch.isEmpty()) return "";
    try {
        JsonObject obj = JsonParser.parseString(skinResourcePatch).getAsJsonObject();
        return obj.getAsJsonObject("geometry").get("default").getAsString();
    } catch (Exception e) {
        return ""; // 解析失败视为无几何
    }
}

/** 是否为 BE 标准 humanoid 几何（可用 JE 默认模型渲染） */
public static boolean isStandardHumanoid(String skinResourcePatch) {
    String id = getDefaultGeometryId(skinResourcePatch);
    return "geometry.humanoid.custom".equals(id) || "geometry.humanoid.customSlim".equals(id);
}
```

> **注意**：`skinResourcePatch` 单独不能完全判定 5D/7D。有些皮肤包 `skinResourcePatch` 仍写 `geometry.humanoid.custom` 但 `geometryData` 为空（纯贴图皮肤）；也有些自定义皮肤 `skinResourcePatch` 指向自定义 id 但 `geometryData` 为空（数据缺失）。**最终判定以 `geometryData` 是否非空为准**（见 ③）。

---

## ③ 5D/7D/Persona/Premium 识别最终逻辑

### 3.1 ViaBedrock 的拦截逻辑（服务端转发层）

ViaBedrock 把 BE 皮肤转发给 JE 客户端 Mod 的两个通道（`bedrockskin:data` / `viabedrockutility:data`）使用**完全相同**的拦截条件。

源码：[`BedrockSkinUtilityInterface.sendSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java) 与 [`ViaBedrockUtilityInterface.sendSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java)：

```java
public static void sendSkin(final UserConnection user, final UUID uuid, final SkinData skin) {
    if (skin.skinData() == null || skin.persona()) {
        return;  // ← Persona 皮肤或无贴图：完全不发送，JE 保持 Steve
    }
    final boolean hasGeometry = !skin.geometryData().isEmpty()
            && !skin.geometryData().toLowerCase(Locale.ROOT).equals("null");
    // ... 发送 SKIN_INFORMATION(width, height, hasGeometry, [geometryData, skinResourcePatch]) + 分块 SKIN_DATA
}
```

**两个判定条件**：
1. `skin.skinData() == null` → 不转发（贴图缺失）
2. `skin.persona() == true` → 不转发（Persona 皮肤）

**`hasGeometry` 的精确判定**（ViaBedrock 官方写法）：
```java
hasGeometry = !skin.geometryData().isEmpty()
           && !skin.geometryData().toLowerCase(Locale.ROOT).equals("null");
```
- 空字符串 `""` → `hasGeometry = false`
- 字符串 `"null"`（任意大小写）→ `hasGeometry = false`（BE 客户端有时用 `"null"` 占位）
- 非空且 ≠`"null"` → `hasGeometry = true`（5D/7D 皮肤）

> **注意**：ViaBedrock **不检查 `premium` 字段**。Premium 皮肤照常转发。  
> **也不检查 `personaPieces` 是否非空**——只看 `persona` boolean。理论上 `persona=false` 但 `personaPieces` 非空的异常数据不会被拦截。

### 3.2 personaPieces 字段结构

源码：[`SkinData.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/model/SkinData.java) 内部 record：

```java
public record PersonaPieceData(String id, String type, String packId, boolean defaultPiece, String productId) {}
```

- `personaPieces` 非空**通常**代表 Persona 皮肤，但 ViaBedrock **不以它为判定依据**，只看 `persona` boolean。
- 普通皮肤 `personaPieces` 为空 List（见 [`SkinProvider.getClientPlayerSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java)：`claims.put("PersonaPieces", new ArrayList<>())`）。
- 在 Fabric Mod 里若想冗余判断，可把 `!personaPieces.isEmpty()` 作为 `persona=true` 的二次确认，但**不应**单独用它回退（普通皮肤理论上也可能带 pieces 数据）。

### 3.3 geometryData 非空 vs 空

- 普通皮肤 `geometryData`：**空字符串 `""`**（最常见）或 `"null"`（占位）。**不是 `null`**（`SkinType.read` 用 `BedrockTypes.STRING` 读取，String 类型不会读成 Java `null`，空数据是 `""`）。
- 5D/7D 皮肤 `geometryData`：**裸 JSON 字符串**（在 `SkinData` 里已解码；在 BE 协议包里也是裸字符串，不是 base64）。内容形如：
  ```json
  {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.xxx","texture_width":128,"texture_height":128},"bones":[...]}]}
  ```
  或旧版 `format_version: "1.8.0"` 格式。

### 3.4 精确判定函数（Fabric Mod 可直接复用）

```java
public enum SkinRenderDecision {
    /** ViaBedrock 不会转发，JE 自然保持 Steve */
    FALLBACK_STEVE_PERSONA,
    /** 贴图缺失，ViaBedrock 不会转发 */
    FALLBACK_STEVE_NO_TEXTURE,
    /** 无自定义几何：可用贴图 + JE 默认模型渲染 */
    RENDER_DEFAULT_MODEL,
    /** 有自定义几何：需客户端 Mod 转 BE 几何→JE 模型；无 Mod 或转换失败则回退 */
    RENDER_CUSTOM_GEOMETRY
}

public static SkinRenderDecision decide(SkinData skin) {
    if (skin.skinData() == null) return SkinRenderDecision.FALLBACK_STEVE_NO_TEXTURE;
    if (skin.persona())           return SkinRenderDecision.FALLBACK_STEVE_PERSONA;

    final boolean hasGeometry = !skin.geometryData().isEmpty()
            && !skin.geometryData().toLowerCase(Locale.ROOT).equals("null");
    if (hasGeometry) return SkinRenderDecision.RENDER_CUSTOM_GEOMETRY;
    return SkinRenderDecision.RENDER_DEFAULT_MODEL;
}
```

### 3.5 各字段组合判定表

| `skinData` | `persona` | `geometryData` | `premium` | `personaPieces` | 判定 | JE 结果 |
|---|---|---|---|---|---|---|
| `null` | * | * | * | * | FALLBACK_STEVE_NO_TEXTURE | Steve |
| 非 null | `true` | * | * | 非空 | FALLBACK_STEVE_PERSONA | Steve（ViaBedrock 不转发） |
| 非 null | `false` | `""` 或 `"null"` | * | 空 | RENDER_DEFAULT_MODEL | 贴图 + JE 默认模型 ✅ |
| 非 null | `false` | 非空非`"null"` | * | 空 | RENDER_CUSTOM_GEOMETRY | 需 Mod 转几何 ⚠️ |
| 非 null | `false` | 非空非`"null"` | `true` | 空 | RENDER_CUSTOM_GEOMETRY | 同上（premium 不影响转发） |

> **关键**：`premium` 字段**不参与**任何回退判定。Premium 皮肤若 `geometryData` 为空，仍可像普通皮肤一样用贴图渲染；若有自定义几何，则与 5D/7D 同等对待。

---

## ④ 图片格式

### 4.1 BE 皮肤图片格式

源码：[`ImageType.java`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/primitive/ImageType.java)

```java
public BufferedImage read(ByteBuf buffer) {
    final int width = buffer.readIntLE();
    final int height = buffer.readIntLE();
    final byte[] data = BedrockTypes.BYTE_ARRAY.read(buffer);
    if (width <= 0 || height <= 0 || data.length == 0 || data.length != width * height * 4) {
        return null;
    }
    // ... RGBA 每像素 4 字节，按 (y*width+x)*4 索引
}
```

- **格式**：RGBA，每像素 4 字节，字节序为 R,G,B,A。
- **尺寸**：**无固定限制**，任意 `width × height`，只要 `data.length == width * height * 4`。
- **BE 常见尺寸**：
  - `64×64`（经典，对应 `geometry.humanoid.custom`）
  - `128×128`（HD 皮肤，常见于 5D/7D 皮肤包，配合自定义几何的 `texture_width/texture_height: 128`）
  - `64×128`、`128×64` 等也非常见
- **校验失败**（width/height ≤0 或长度不匹配）→ `ImageType.read` 返回 `null` → `skinData == null` → ViaBedrock 不转发 → Steve。

### 4.2 JE 1.21.11 / 26.1 皮肤图片尺寸

- **JE 原版皮肤贴图标准**：`64×64`（自 1.8 起）。双层皮肤（hat/jacket/sleeve/pants）都在这 64×64 内。
- **JE slim 模型**：同样是 64×64，只是手臂 UV 布局不同（`armSize: "slim"` 对应 BE `geometry.humanoid.customSlim`）。
- **JE 原版不支持** 128×128 等高分辨率皮肤贴图（需 OptiFine/HD 修复或客户端 Mod 才能用）。

### 4.3 BE 128×128 皮肤在 JE 端如何处理

ViaBedrock **原样转发** width/height 给客户端 Mod 通道（见 [`BedrockSkinUtilityInterface.sendSkin`](https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java)：`pluginMessage.write(Types.INT, skin.skinData().getWidth())` + `getHeight()`）。

**两种情况**：

1. **有自定义几何（5D/7D）+ 128×128 贴图**：客户端 Mod（如 BedrockSkinUtility）用 `NativeImage(width, height)` 创建原尺寸贴图，并按几何 JSON 里的 `texture_width/texture_height: 128` 采样 UV → 可正确渲染。
   - 源码：[`GeometryUtil.bedrockGeoToJava`](https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/GeometryUtil.java) 从 `description.texture_height/texture_width` 读取 UV 尺寸。
2. **无自定义几何（`geometryData` 空）+ 128×128 贴图**：客户端 Mod 退化为只用贴图 + JE 默认 humanoid 模型。但 JE 默认模型 UV 假定 64×64，**128×128 贴图会被错误采样**（只用到左上 1/4 区域或错位）。
   - 此时建议在 Fabric Mod 里**回退为 Steve**，或把贴图缩放/裁剪到 64×64 后再贴。

> **结论**：`RENDER_DEFAULT_MODEL`（无自定义几何）情况下，若 `skinData` 尺寸 ≠ 64×64，应视为不可渲染并回退 Steve。`RENDER_CUSTOM_GEOMETRY` 情况下，任意尺寸都可由客户端 Mod 处理（几何 JSON 自带 UV 尺寸）。

---

## ⑤ JE 端处理建议

### 5.1 整体判定流程（Fabric Mod 客户端侧）

```
收到 bedrockskin:data / viabedrockutility:data 的 SKIN_INFORMATION
  ├─ 读取 width, height, hasGeometry, (hasGeometry 时再读 geometryData + skinResourcePatch)
  ├─ 若 hasGeometry == false（geometryData 空）:
  │    ├─ 若 width==64 && height==64 → 接收贴图，用 JE 默认 humanoid 模型渲染 ✅
  │    └─ 否则（如 128×128 无几何）→ 回退 Steve（UV 不匹配，渲染会错乱）
  └─ 若 hasGeometry == true（5D/7D/自定义几何）:
       ├─ 尝试把 geometryData(JSON) + skinResourcePatch(几何 id) 转成 JE ModelPart
       ├─ 转换成功 → 用自定义模型 + 原尺寸贴图渲染 ✅
       └─ 转换失败 → 回退 Steve（几何解析异常，宁可不显示）
```

### 5.2 客户端 Mod 参考实现

[Camotoy/BedrockSkinUtility](https://github.com/Camotoy/BedrockSkinUtility) 的 [`BedrockMessageHandler.handle(SkinData)`](https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/pluginmessage/BedrockMessageHandler.java) 给出了标准做法：

```java
boolean setModel = info.getGeometry() != null;  // hasGeometry
PlayerRenderer renderer;
if (setModel) {
    BedrockPlayerEntityModel<AbstractClientPlayer> model = GeometryUtil.bedrockGeoToJava(info);
    renderer = (model != null) ? new PlayerRenderer(...) : null;  // 转换失败→null
} else {
    renderer = null;  // 无几何→只用贴图
}
// 用 NativeImage(width,height) 创建贴图 → 注册 DynamicTexture → 应用到 PlayerSkin
```

[`GeometryUtil.bedrockGeoToJava`](https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/GeometryUtil.java) 的关键点：
- 从 `skinResourcePatch`（即 `jsonGeometryName`）读 `geometry.default` 取几何 identifier。
- 支持 `format_version: "1.8.0"`（旧，`geometry.<id>.bones`）与 `"1.12.0"`（新，`minecraft:geometry[]` + `description.identifier`）。
- 从 `description.texture_width/texture_height` 读取 UV 尺寸（解决 128×128 贴图采样）。
- 任何解析异常 → `return null` → 退化为只用贴图。

### 5.3 BedrockSkinUtility 协议版本注意事项

源码：[`BaseSkinInfo.STREAM_DECODER`](https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/pluginmessage/data/BaseSkinInfo.java)

```java
int version = buf.readInt();
if (version != 1) { // Version 2 is probably going to be reserved for persona skins
    throw new RuntimeException("Could not load skin info! Is the mod and plugin updated?");
}
```

- `bedrockskin:data` 通道的 SKIN_INFORMATION 消息有版本号字段，**当前仅支持 version=1**。
- 注释明确：**version 2 预留给 persona 皮肤**——即 BedrockSkinUtility 作者预期 Persona 皮肤会用不同协议格式，当前实现不处理（且 ViaBedrock 在服务端已过滤 `persona=true`，客户端根本收不到）。

### 5.4 给本项目（BedrockSkinBridge）的建议

1. **服务端拦截复用 ViaBedrock 逻辑**：在转发皮肤前用 `if (skin.skinData() == null || skin.persona()) return;` 过滤，与 ViaBedrock 保持一致，避免发送 Persona 皮肤导致客户端异常。
2. **`hasGeometry` 判定**：直接抄 ViaBedrock 的 `!geometryData.isEmpty() && !"null".equalsIgnoreCase(geometryData)` 写法，不要自作主张加 `!= null`（`SkinType` 读出的 String 不会是 Java null）。
3. **无几何 + 非 64×64 贴图**：建议回退 Steve（或裁剪贴图）。不要尝试用 JE 默认模型贴 128×128 贴图，会错位。
4. **有几何但转换失败**：建议回退 Steve 而非用错位贴图。`GeometryUtil` 返回 `null` 即说明几何无法转换。
5. **`premium` 字段**：不要用它做回退判定。Premium 皮肤只是付费标记，渲染能力取决于 `geometryData` 是否非空，与 `premium` 无关。
6. **`personaPieces` 非空但 `persona=false`**：理论异常数据，建议当作 5D/7D 处理（按 `geometryData` 判定），不单独因 pieces 非空而回退。
7. **`armSize`**：`"wide"` → JE 默认模型；`"slim"` → JE slim 模型。无几何皮肤应据此选择 JE 模型变体。

---

## 附：关键源码引用索引

| 文件 | URL | 关键行 |
|---|---|---|
| `SkinData.java`（record 定义） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/model/SkinData.java | 全文件 |
| `SkinType.java`（协议解析顺序） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/model/SkinType.java | `read()` 方法 |
| `SkinProvider.java`（默认 Steve claims） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/provider/SkinProvider.java | `getClientPlayerSkin` |
| `BedrockSkinUtilityInterface.java`（转发 + persona 过滤） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/BedrockSkinUtilityInterface.java | `sendSkin` 开头 |
| `ViaBedrockUtilityInterface.java`（转发 + persona 过滤） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/api/modinterface/ViaBedrockUtilityInterface.java | `sendSkin` 开头 |
| `ImageType.java`（图片格式） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/types/primitive/ImageType.java | `read()` 校验 |
| `OtherPlayerPackets.java`（PLAYER_SKIN 处理） | https://github.com/RaphiMC/ViaBedrock/blob/main/src/main/java/net/raphimc/viabedrock/protocol/packet/OtherPlayerPackets.java | 160–168 |
| `BaseSkinInfo.java`（客户端协议版本） | https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/pluginmessage/data/BaseSkinInfo.java | `STREAM_DECODER` |
| `BedrockMessageHandler.java`（客户端渲染决策） | https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/pluginmessage/BedrockMessageHandler.java | `handle(SkinData)` |
| `GeometryUtil.java`（BE 几何→JE 模型） | https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/GeometryUtil.java | `bedrockGeoToJava` |
| `SkinInfo.java`（geometry 可为 null） | https://github.com/Camotoy/BedrockSkinUtility/blob/master/src/main/java/net/camotoy/bedrockskinutility/client/SkinInfo.java | `getGeometry()` |
| ViaBedrock README（可选客户端 Mod） | https://github.com/RaphiMC/ViaBedrock/blob/main/README.md | "Optional clientside mods" |
