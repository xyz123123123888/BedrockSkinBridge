package com.moxi.bedrockskinbridge.skin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.awt.image.BufferedImage;
import java.io.File;
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
        if (skinImage == null) {
            System.out.println("[BedrockSkinBridge] No skin image for " + uuid);
            return null;
        }

        // 注册皮肤纹理
        Identifier skinId = Identifier.fromNamespaceAndPath(NAMESPACE, "be_skin_" + uuid.toString().replace("-", ""));
        ClientAsset.Texture bodyAsset = registerTexture(skinId, skinImage);
        if (bodyAsset == null) {
            System.out.println("[BedrockSkinBridge] Failed to register skin for " + uuid);
            return null;
        }

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
     * 把 BufferedImage 注册为原版动态纹理 (最可靠方式)。
     *
     * 流程: BufferedImage -> ImageIO 写临时 PNG -> NativeImage.read 原版解码
     *       -> DynamicTexture(Supplier, NativeImage) -> 注册到 TextureManager
     * 用原版解码器, 避免手动逐像素转换的字节序/格式坑。
     */
    private static ClientAsset.Texture registerTexture(Identifier id, BufferedImage image) {
        File tempFile = null;
        try {
            // 1. BufferedImage 写入临时 PNG
            tempFile = File.createTempFile("bedrock_skin_" + id.toString().replace(":", "_") + "_", ".png");
            if (!javax.imageio.ImageIO.write(image, "png", tempFile)) {
                System.err.println("[BedrockSkinBridge] ImageIO.write returned false for " + id);
                return null;
            }

            // 2. 原版解码 PNG -> NativeImage
            //    注意: NativeImage 所有权移交给 DynamicTexture (<-lambda, NativeImage> 构造),
            //    由 DynamicTexture.close() 负责释放, 此处不可再用 try-with-resources 关闭。
            NativeImage nativeImage;
            try (java.io.InputStream in = new java.io.FileInputStream(tempFile)) {
                nativeImage = NativeImage.read(in);
            }

            // 3. 构造动态纹理并上传 (复用已注册的同 ID 纹理, 避免重复注册)
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            if (textureManager.getTexture(id) instanceof DynamicTexture existing) {
                existing.close();
            }
            DynamicTexture dynamicTexture = new DynamicTexture(() -> id.toString(), nativeImage);
            dynamicTexture.upload();
            textureManager.register(id, dynamicTexture);

            // 4. 构造 ClientAsset.Texture 返回
            final Identifier textureId = id;
            return new ClientAsset.Texture() {
                @Override
                public Identifier texturePath() { return textureId; }
                @Override
                public Identifier id() { return textureId; }
            };
        } catch (Exception e) {
            System.err.println("[BedrockSkinBridge] Failed to register texture " + id);
            e.printStackTrace();
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
        return null;
    }

    /**
     * 清除缓存 (玩家退出时调用)。
     */
    public static void clear() {
        skinCache.clear();
    }
}