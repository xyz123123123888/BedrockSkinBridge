# JE→BE 皮肤广播研究文档（让服务器其他 BE 玩家看到 JE 玩家的自定义皮肤）

> 研究日期：2026-08-13
> 研究方式：联网检索 Bedrock 协议文档、PMMP/Nukkit/Geyser/ViaBedrock 源码与 issue
> 可信度标注：**[确认]** = 权威来源明确记载；**[推断]** = 由源码/多方证据合理推断，建议实测验证

---

## 1. 目标与现状

- **目标**：JE 玩家（通过 ViaFabricPlus/ViaBedrock 加入 BE 服务器）让服务器上**其他 BE 玩家**看到自己的自定义皮肤（从 LittleSkin 拉取）。
- **现状**：本地客户端看得到自己的皮肤；其他 BE 玩家只能看到默认 Steve。
- **已实现但无效**：`BedrockSkinProvider.sendJavaSkin()` 已成功从 LittleSkin 拉取并发送 `PLAYER_SKIN` 数据包（日志确认 `PLAYER_SKIN packet sent successfully`），但其他玩家视角未更新。

---

## 2. PlayerSkin 数据包（客户端→服务器→广播）

**[确认]** Bedrock 协议 `PlayerSkinPacket`（id 93），官方文档原文：
> *"Used when the player changes their skin. Sent from the client to server, then processed and broadcasted to all clients. This is used by third-party servers to send custom geometry."*

- 客户端发 → 服务器处理 → **广播给所有客户端**。这正是我们要的通道。
- 外层字段：`UUID` + `SerializedSkin`。
- `SerializedSkin` 关键字段：`ID`(skinId)、`ResourcePatch`(skinResourcePatch)、`ImageData`(skinData: 宽/高/像素)、`CapeImageData`(capeData)、`GeometryData`、`ArmSize`(Slim/Wide)、`PersonaPieces` 等，**末尾一个布尔**表示"已验证/受信任"（各实现命名不同：PMMP=`verified`、Nukkit=`setTrusted()`、JSPrismarine=`trusted`）。

来源：
- https://mojang.github.io/bedrock-protocol-docs/docs/PlayerSkinPacket.html
- https://api.pmmp.io/ · https://github.com/pmmp/BedrockProtocol

---

## 3. 发送时机问题（为什么立即发可能被忽略）

**[推断]** BE 登录是状态机：`Login → StartGame → 世界数据 → AddPlayer/实体 → SetLocalPlayerAsInitialized → 游戏阶段`。

- 在 `SetLocalPlayerAsInitialized` **之前**到达的包，很多服务端会走"登录期"分支甚至 `SilentDiscard` 丢弃。
- PMMP 的 `SpawnResponsePacketHandler` 只在收到 `SetLocalPlayerAsInitializedPacket` 后才触发"生成完成"回调。
- **结论**：皮肤包应在玩家实体对他人可见后发送，进游戏后延迟并重发一次更稳。

来源：https://api.pmmp.io/ · https://github.com/pmmp/PocketMine-MP

---

## 4. 受信任 / 签名皮肤机制（★核心瓶颈）

**[确认]** 这是"其他玩家看不到"的最可能根因：

1. **BE 客户端有 "Only Allow Trusted Skins" 开关**（观看者客户端过滤）。开启后，其他玩家传来的非受信任皮肤会被替换为默认 Steve/Alex。
   - 来源：https://help.minecraft.net/hc/en-us/articles/42462166875405
   - 旧版对应 `options.txt` 的 `only_show_trusted_skins:1`：https://edusupport.minecraft.net/hc/en-us/community/posts/37051929164948

2. **皮肤如何被标记为受信任**：来自官方 Dressing Room / Marketplace / Xbox 账户的皮肤 = trusted；第三方上传/非官方皮肤 = untrusted。

3. **[确认]** Nukkit issue #1759：服务端用自定义皮肤生成实体，**必须 `skin.setTrusted(true)` 后他人才能看到**。证明服务端皮肤控制器对 untrusted 皮肤拒绝/不广播。
   - 来源：https://github.com/CloudburstMC/Nukkit/issues/1759

**对我们场景的直接含义**：
- 我们通过 ViaBedrock 以自定义客户端身份进入，从 LittleSkin 拉的皮肤**不是 Xbox 签名皮肤**，`verified` 基本为 false。
- 即使服务器正确接收并广播，**开启 trusted-only 的 BE 玩家仍会看到默认 Steve**。
- 我们当前 `sendPlayerSkinPacket` 里 `wrapper.write(Types.BOOLEAN, false)` 正是把这个布尔标成了 false。

---

## 5. ViaBedrock 现成方案

- ViaBedrock README 明确"早期开发、不建议常规使用"，皮肤部分支持。
- **没有**"把 JE 皮肤同步给 BE 其他玩家可见"的现成实现。
  - ViaBedrock issue #212（"[Feature] Skin copy to bedrocks account"）= 同类 feature request，未实现。
  - ViaBedrockUtility / BedrockSkinUtility 是**反方向**（JE 侧渲染 BE 皮肤），不适用。
- Geyser 的 SkinProvider 是给 Geyser 拉取 Java 皮肤用，非本场景。

来源：https://github.com/RaphiMC/ViaBedrock · https://github.com/RaphiMC/ViaBedrock/issues/212 · https://github.com/MemoriesOfTime/ViaBedrockUtility

---

## 6. 结论与可执行方案（按优先级）

**核心结论**：纯客户端发 PLAYER_SKIN 无法保证其他 BE 玩家看到，瓶颈是**受信任皮肤机制**（服务端皮肤控制器 + 观看者客户端过滤），而非发包逻辑。

**方案**：
1. **先修时序 + 标记受信任**：
   - 皮肤包放到 `SetLocalPlayerAsInitialized` 之后延迟发送并重发一次。
   - 客户端侧把签名布尔尝试置为 true（`trustedSkin`），看服务端是否接受。
2. **服务端兜底（最可靠，需可控 BE 服务器）**：
   - 在 BE 服务端（PMMP/Nukkit/BDS 插件）把该玩家皮肤 `setTrusted(true)` 并广播。
   - 确认 `customSkinsDisabled` 标志为 false，`server.properties` 未强制 trusted-only。
3. **引导观看者关闭 "Only Allow Trusted Skins"**（非代码可解，属客户端设置）。
4. **长期方案**：为 JE 玩家关联真实 BE 账户，把皮肤上传到官方账户系统使其成为 trusted。

---

## 7. 待验证项

- 是否需要/能否在客户端把 `verified` 置 true。
- 目标 BE 服务器的服务端软件（BDS / PMMP / Nukkit）及是否可控。
- 实际抓包确认当前发送的 `verified` 值、以及发送的生命周期时机。