package com.moxi.bedrockskinbridge.skin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 BE 皮肤 BufferedImage 转为 JE PlayerSkin。
 *
 * BE 玩家不在 LittleSkin 注册, CSL 查询必然 miss。
 * 本类直接用 BedrockSkinCache 缓存的 BE 皮肤图片,
 * 注册为 Minecraft 动态纹理, 构造 PlayerSkin 返回。
 */
public class BedrockSkinTextureManager {

    private static final String NAMESPACE = "bedrockskinbridge";
    private static final Map<UUID, PlayerSkin> skinCache = new ConcurrentHashMap<>();

    /**
     * 从 BedrockSkinCache 获取 BE 皮肤图片, 构造 PlayerSkin。
     * 必须在渲染线程调用 (MixinPlayerInfo.createSkinLookup 在 Render thread)。
     *
     * @return PlayerSkin, 如果缓存无图片返回 null
     */
    public static PlayerSkin createPlayerSkin(UUID uuid) {
        // 先查已构造的缓存
        PlayerSkin cached = skinCache.get(uuid);
        if (cached != null) return cached;

        BufferedImage skinImage = BedrockSkinCache.getSkinImage(uuid);
        if (skinImage == null) return null;

        // 注册皮肤纹理
        Identifier skinId = Identifier.fromNamespaceAndPath(NAMESPACE, "be_skin_" + uuid.toString().replace("-", ""));
        ClientAsset.Texture bodyAsset = registerTexture(skinId, skinImage);
        if (bodyAsset == null) return null;

        // 注册披风纹理 (可选)
        ClientAsset.Texture capeAsset = null;
        BufferedImage capeImage = BedrockSkinCache.getCapeImage(uuid);
        if (capeImage != null) {
            Identifier capeId = Identifier.fromNamespaceAndPath(NAMESPACE, "be_cape_" + uuid.toString().replace("-", ""));
            capeAsset = registerTexture(capeId, capeImage);
        }

        // 构造 PlayerSkin (按 armSize 选择模型, slim=Alex 细手臂, 否则 WIDE)
        PlayerModelType model = BedrockSkinCache.isSlim(uuid) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
        PlayerSkin skin = PlayerSkin.insecure(bodyAsset, capeAsset, null, model);
        skinCache.put(uuid, skin);
        return skin;
    }

    /**
     * 把 BufferedImage 注册为 Minecraft 动态纹理。
     * 逐像素拷入 NativeImage, 避免 PNG 编解码 (省一次完整编码+解码)。
     */
    private static ClientAsset.Texture registerTexture(Identifier id, BufferedImage image) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            NativeImage nativeImage = new NativeImage(w, h, true);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    nativeImage.setPixelABGR(x, y, argbToNative(image.getRGB(x, y)));
                }
            }

            // 注册动态纹理 (DynamicTexture 接管 NativeImage, 不再手动 close)
            DynamicTexture dynamicTexture = new DynamicTexture(() -> id.toString(), nativeImage);
            dynamicTexture.upload();
            Minecraft.getInstance().getTextureManager().register(id, dynamicTexture);

            // 构造 ClientAsset.Texture (接口, 匿名实现)
            final Identifier texturePath = id;
            return new ClientAsset.Texture() {
                @Override
                public Identifier texturePath() { return texturePath; }
                @Override
                public Identifier id() { return texturePath; }
            };
        } catch (Exception e) {
            System.out.println("[BedrockSkinBridge] registerTexture failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * BufferedImage 的 ARGB(0xAARRGGBB) → NativeImage 内部 ABGR(0xAABBGGRR)。
     * 仅交换 R/B, A 与 G 位不变。
     */
    private static int argbToNative(int argb) {
        return (argb & 0xFF00FF00)                  // 保留 A(高8) 和 G(byte1)
            | ((argb >>> 16) & 0x000000FF)          // 原 R → 低8位(新 B)
            | ((argb & 0x000000FF) << 16);          // 原 B → 16-23位(新 R)
    }

    /**
     * 清除缓存 (玩家退出时调用)。
     */
    public static void clear() {
        skinCache.clear();
    }
}
