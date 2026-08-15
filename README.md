# BedrockSkinBridge

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Fabric](https://img.shields.io/badge/Mod_Loader-Fabric-blue)](https://fabricmc.net/)
[![MC Version](https://img.shields.io/badge/Minecraft-26.2-green)](https://www.minecraft.net/)
[![纯手工打造](https://img.shields.io/badge/纯手工打造-赛博石墩子-orange?style=flat-square&logo=codewars)](https://github.com/your-repo/BedrockSkinBridge)
[![我的 Nomling](https://nomlings.cc/badge/YOUR-USERNAME?pet=wispling&scale=2)](https://nomlings.cc/badge)

![Jokes Card](https://readme-jokes.vercel.app/api?theme=onedark)

> ⚠️ **警告**：本项目由半赛博人格驱动，情绪稳定，绝不破防。(AI + 人工 开发)

**BedrockSkinBridge** 是一个 Fabric 客户端模组，旨在解决 Java 版玩家通过 **ViaFabricPlus + ViaBedrock** 进入基岩版（Bedrock）服务器时的皮肤显示问题。

- **JE → BE**：将 Java 版玩家的皮肤（通过 LittleSkin 等皮肤站获取）上传给 Bedrock 服务器，让 BE 玩家看到你的自定义皮肤。
- **BE → JE**：将 Bedrock 玩家（非 Persona/5D/7D）的皮肤注入到 Java 版的皮肤渲染管线中，让 JE 玩家看到 BE 玩家的真实皮肤。

---

## ✨ 功能特性

- **双向皮肤桥接**：同时支持 Java 版玩家皮肤上传与基岩版玩家皮肤显示。
- **CustomSkinLoader 集成**：在 JE 侧利用 CSL 强大的皮肤加载生态（支持 LittleSkin、BlessingSkin 等任意 CSL 皮肤站）。
- **精准的 5D/7D 回退**：针对 BE 自定义几何模型（5D/7D 皮肤）和 Persona 角色编辑器皮肤，自动回退为默认 Steve，避免渲染错乱。
- **低侵入实现**：通过 Mixin 与反射实现，不修改游戏核心 jar 文件，兼容 Fabric 环境。
- **高性能纹理管理**：将 BE 皮肤图片直接转换为 JE `NativeImage` 动态纹理，避免不必要的磁盘 I/O 和 PNG 编解码。

---

## 🗿 项目形容："石"墩子

> *“我会把代码刻在"石"头"上"，然后"石"头就运行起来了。”*

本项目暂无官方吉祥物，但开发团队一致认为——**石**是最能代表本项目精神的图腾：

- **朴实无华**：就像路边默默无闻的"石"。
- **坚如磐石**：经过反复测试与打磨，稳定性值得信赖（至少我不这么认为）。
- **赛博包浆**：每一行代码都经过反复 review、重构，留存下来的每一行都有独特的“shi感”。

如果你在路边看到一个石墩子，请对它说一声：“谢谢你的坚守。”——它可能正在默默运行着某个开源项目。

---

## 📦 依赖环境

> 本模组**不包含** ViaBedrock 和 CustomSkinLoader，请确保已正确安装以下前置模组。

| 依赖 | 版本要求 | 备注 |
| :--- | :--- | :--- |
| **Minecraft** | `26.2` | - |
| **Fabric API** | `>= 0.154.2` | - |
| **ViaFabricPlus** | `= 4.6.1` | 必须包含 ViaBedrock 协议支持 |
| **CustomSkinLoader** | `>= 15.0.1` | 万用皮肤补丁，用于 JE 侧皮肤渲染 |

---

## 🚀 安装与使用

> ## 自己编译去! (Bro懒得去编译项目给你)

---

## ⚙️ 工作原理（技术架构）

### BE → JE（基岩玩家皮肤显示）

1. **拦截 `PlayerList` 包**：`MixinSkinProvider` 在 Netty IO 线程拦截 `SkinProvider.setSkin`，提取 `SkinData`（含皮肤图片、几何标识等）。
2. **缓存真实用户名**：`MixinClientPacketListener` 从 `PlayerInfo.displayName` 提取真实 BE 用户名，解决 ViaBedrock 将 `GameProfile.name` 改为乱码（`StringUtil.encodeUUID`）导致 CSL 无法查询的问题。
3. **注入渲染管线**：
   - 普通皮肤：`MixinPlayerInfo` 拦截 `createSkinLookup`，通过 `BedrockSkinTextureManager` 将 BufferedImage 注册为动态纹理并返回 `PlayerSkin`。
   - 本地玩家（JE 自己）：强制走 `CustomSkinLoader` 查询 LittleSkin，确保自己看到自己的 JE 皮肤。
   - 回退策略：若缓存中无 BE 皮肤，则回退至 CSL（LittleSkin）查询，兼容纯 JE 玩家。

### JE → BE（Java 玩家皮肤上传）

1. **玩家加入事件**：`ClientPlayConnectionEvents.JOIN` 触发 `BedrockSkinProvider.sendJavaSkin`。
2. **异步获取皮肤**：通过 `LittleSkinClient` 请求 `https://littleskin.cn/csl/{username}.json` 获取皮肤图片 URL。
3. **构造并发送包**：将下载的图片构造为标准 Bedrock `SkinData`，通过 `PLAYER_SKIN (0x6D)` 数据包发送给 Bedrock 服务器。

---

## 🚫 已知限制

- **5D/7D 自定义几何皮肤**：因 Java 版渲染引擎与 Bedrock 几何格式（`format_version: 1.8.0/1.12.0`）不兼容，本模组会检测 `geometryData` 字段并自动回退为 Steve (原皮)。
- **Persona 角色编辑器皮肤**：由多个部件拼合而成，无法通过单张贴图还原，同样回退为 Steve (原皮)。
- **动态换肤**：若 BE 玩家在游戏中通过角色编辑器更换皮肤，需重新进出服务器或等待 PlayerList 刷新（`PLAYER_SKIN` 包更新可能受 ViaBedrock 转换限制）。

---

## 🛠️ 开发者构建

如果你想自行编译或二次开发：

```bash
# 克隆仓库
git clone https://github.com/xyz123123123888/BedrockSkinBridge.git
cd BedrockSkinBridge

# 构建 (使用 Gradle)
./gradlew build

# 产物位于 build/libs/bedrockskinbridge-<version>.jar




## <!> 本项目基于 **GNU General Public License v3.0 (GPL-3.0)** 开源。

本模组通过 Mixin 拦截、SPI 接口实现与编译期 API 深度集成了以下 **GPL-3.0** 许可的依赖库。根据 GPL 的 copyleft 条款，本项目的衍生作品性质决定了它必须以 **GPL-3.0** 许可证发布，并开放对应源代码。**我们不能使用更宽松的许可证（如 MIT）发布本项目。**

> ⚠️ 这意味着：任何基于本项目的二次分发作品，也必须以 GPL-3.0（或兼容许可证）发布并公开源代码。

### 第三方依赖版权声明

| 库 - 作者 - 许可证
| ------------------------
| ViaBedrock - RaphiMC - GPL-3.0
| ViaFabricPlus - ViaVersion - GPL-3.0
| CustomSkinLoader - xfl03 - GPL-3.0-only

以上各库均保留其原作者版权，本项目仅作正当引用与集成，未修改其原始二进制。完整许可条款见 [LICENSE](LICENSE) 与各依赖库对应仓库。
