package com.moxi.bedrockskinbridge.skin;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.util.Pair;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 覆盖 ViaBedrock 的 SkinProvider。
 *
 * 1. setSkin(): 拦截 BE 服务器发来的 BE 玩家皮肤 → 注入 CSL 缓存
 * 2. sendJavaSkin(): 从 LittleSkin 获取 JE 皮肤 → 发送 PlayerSkin 给 BE 服务器
 */
public class BedrockSkinProvider extends SkinProvider {

    /**
     * 拦截 BE 服务器广播的玩家皮肤。
     * ViaBedrock 收到 PlayerSkin 数据包后调用此方法。
     */
    @Override
    public void setSkin(UserConnection user, UUID uuid, SkinData skin) {
        // 保留默认行为 (转发给 mod 接口通道)
        super.setSkin(user, uuid, skin);

        // 日志: 确认 setSkin 被调用
        System.out.println("[BedrockSkinBridge] setSkin called: uuid=" + uuid);

        // 5D/7D/persona/premium 皮肤: JE 无法用单张贴图渲染, 跳过 (显示默认 Steve)
        if (shouldFallbackToSteve(skin)) return;

        // 提取皮肤数据
        BufferedImage skinImage = skin.skinData();
        BufferedImage capeImage = skin.capeData();

        // 获取真实用户名并缓存 (供 MixinPlayerInfo 构造真实 GameProfile)
        String realName = resolveUsername(user, uuid);
        BedrockSkinCache.cachePlayerName(uuid, realName);
        BedrockSkinCache.cacheSkin(uuid, skinImage);
        if (capeImage != null) {
            BedrockSkinCache.cacheCape(uuid, capeImage);
        }

        // MixinPlayerInfo 拦截 createSkinLookup, 用真实用户名构造 GameProfile,
        // 让 CSL 用真实用户名查 LittleSkin (JE 玩家自己: 命中)
    }

    /**
     * 判断 BE 皮肤是否应回退为默认 Steve (JE 无法渲染的情况)。
     * - 自定义几何 (5D/7D): resourcePatch 非标准 或 自带 geometryData
     * - persona: 角色编辑器拼装皮肤
     * - premium: 付费皮肤
     * - personaPieces: 含拼装部件
     */
    private static boolean shouldFallbackToSteve(SkinData skin) {
        if (BedrockSkinHandler.isCustomGeometry(skin.skinResourcePatch(), skin.geometryData())) return true;
        if (skin.persona()) return true;
        if (skin.premium()) return true;
        if (skin.personaPieces() != null && !skin.personaPieces().isEmpty()) return true;
        return false;
    }

    /**
     * 从 LittleSkin 获取 JE 玩家皮肤, 构造 SkinData, 发送 PlayerSkin 给 BE 服务器。
     * 在玩家加入世界后调用。
     *
     * @param user     ViaVersion UserConnection
     * @param uuid     JE 玩家 UUID
     * @param username JE 玩家用户名
     */
    public static void sendJavaSkin(UserConnection user, UUID jeUuid, String username) {
        System.out.println("[BedrockSkinBridge] sendJavaSkin START: user=" + username + " jeUuid=" + jeUuid);
        // 异步获取皮肤, 避免阻塞网络线程
        new Thread(() -> {
            try {
                // 1. 从 LittleSkin 获取皮肤信息
                System.out.println("[BedrockSkinBridge]   fetching from LittleSkin: " + username);
                LittleSkinClient.SkinInfo skinInfo = LittleSkinClient.fetchSkin(username);
                if (skinInfo == null) {
                    System.out.println("[BedrockSkinBridge]   LittleSkin fetch returned null for " + username);
                    return;
                }
                System.out.println("[BedrockSkinBridge]   LittleSkin OK: skinUrl=" + skinInfo.skinUrl + " model=" + skinInfo.model);

                // 2. 下载皮肤图片
                BufferedImage skinImage = LittleSkinClient.downloadImage(skinInfo.skinUrl);
                if (skinImage == null) {
                    System.out.println("[BedrockSkinBridge]   skin image download FAILED");
                    return;
                }
                System.out.println("[BedrockSkinBridge]   skin image OK: " + skinImage.getWidth() + "x" + skinImage.getHeight());

                // 3. 下载披风 (可选)
                BufferedImage capeImage = null;
                if (skinInfo.capeUrl != null) {
                    capeImage = LittleSkinClient.downloadImage(skinInfo.capeUrl);
                }

                // 4. 构造标准 Bedrock SkinData
                boolean slim = "slim".equals(skinInfo.model);
                SkinData skinData = buildStandardSkinData(skinImage, capeImage, slim);

                // 5. 发送 PlayerSkin 包给 BE 服务器。
                //    关键: 发包时 UUID 必须用 ViaBedrock 会话 UUID (服务器登记的玩家身份),
                //    而不是 JE 玩家的 profileId。否则服务器匹配不到玩家, 直接丢弃包。
                //    每次发送前现取会话 UUID, 避免 JOIN 时 protocolInfo 尚未初始化。
                UUID bedrockUuid = resolveBedrockUuid(user, jeUuid);
                System.out.println("[BedrockSkinBridge]   sending PLAYER_SKIN packet to BE server (uuid=" + bedrockUuid + ")...");
                sendPlayerSkinPacket(user, bedrockUuid, skinData);
                System.out.println("[BedrockSkinBridge]   PLAYER_SKIN packet sent successfully (uuid=" + bedrockUuid + ")");
            } catch (Exception e) {
                System.out.println("[BedrockSkinBridge] sendJavaSkin FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }, "BSB-SkinSend").start();
    }

    /**
     * 解析发包应使用的 Bedrock 会话 UUID。
     * 优先取 ViaBedrock 登记本玩家的会话 UUID (user.getProtocolInfo().getUuid()),
     * 取不到时回退到 JE UUID。
     */
    private static UUID resolveBedrockUuid(UserConnection user, UUID jeUuid) {
        try {
            UUID viaUuid = user.getProtocolInfo().getUuid();
            if (viaUuid != null) {
                return viaUuid;
            }
        } catch (Exception ignored) {
        }
        return jeUuid;
    }

    /**
     * 构造标准 Bedrock SkinData (使用默认 humanoid 几何模型)。
     */
    private static SkinData buildStandardSkinData(BufferedImage skinImage,
                                                   BufferedImage capeImage,
                                                   boolean slim) {
        return new SkinData(
            "java_skin",                              // skinId
            "",                                       // playFabId
            BedrockSkinHandler.buildStandardResourcePatch(slim), // skinResourcePatch
            skinImage,                                // skinData
            List.of(),                                // animations
            capeImage,                                // capeData
            "",                                       // geometryData (空=用标准模型)
            "",                                       // geometryDataEngineVersion
            "",                                       // animationData
            false,                                    // premium
            false,                                    // persona
            false,                                    // capeOnClassic
            true,                                     // primaryUser
            "",                                       // capeId
            "",                                       // fullSkinId
            slim ? "slim" : "wide",                   // armSize
            "#0",                                     // skinColor
            List.of(),                                // personaPieces
            List.of(),                                // tintColors
            false                                     // overridingPlayerAppearance
        );
    }

    /**
     * 发送 PlayerSkin 数据包给 Bedrock 服务器。
     */
    private static void sendPlayerSkinPacket(UserConnection user, UUID uuid, SkinData skin) {
        try {
            PacketWrapper wrapper = PacketWrapper.create(
                ServerboundBedrockPackets.PLAYER_SKIN, user
            );
            wrapper.write(BedrockTypes.UUID, uuid);
            wrapper.write(BedrockTypes.SKIN, skin);
            wrapper.write(BedrockTypes.STRING, "java_skin");  // newSkinName
            wrapper.write(BedrockTypes.STRING, "");            // oldSkinName
            wrapper.write(Types.BOOLEAN, true);               // trustedSkin (置 true, 尝试让服务端接受并广播)
            wrapper.sendToServer(BedrockProtocol.class);
        } catch (Exception e) {
            // 发送失败, 静默处理
        }
    }

    /**
     * 通过 UserConnection 解析 BE 玩家用户名。
     * ViaBedrock 用 PlayerListStorage 存储玩家列表 (UUID -> Pair<runtimeId, name>)。
     * 该类是 public 的, 直接用强类型访问 (避免反射开销和脆弱性)。
     */
    private static String resolveUsername(UserConnection user, UUID uuid) {
        try {
            PlayerListStorage pls = user.get(PlayerListStorage.class);
            if (pls != null) {
                Pair<Long, String> entry = pls.getPlayer(uuid);
                if (entry != null) {
                    return entry.value();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "player_" + uuid.toString().substring(0, 8);
    }
}
