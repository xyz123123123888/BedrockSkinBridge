/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 Moxi
 */
package com.moxi.bedrockskinbridge.mixin;

import com.moxi.bedrockskinbridge.skin.BedrockSkinCache;
import com.moxi.bedrockskinbridge.skin.BedrockSkinHandler;
import com.moxi.bedrockskinbridge.skin.LittleSkinCache;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.types.primitive.ImageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Mixin 拦截 SkinProvider.setSkin, 绕过 Provider 注册时序问题。
 *
 * 根因: ViaBedrock 在 PlayerList Add (Netty 线程) 时就调 setSkin,
 * 但 Mod 的 Provider 覆盖注册在 JOIN 事件 (客户端主线程) — 太晚了。
 * Mixin 直接拦截 SkinProvider 类本身, 无论哪个 Provider 实例被注册都能拦截到。
 */
@Mixin(value = SkinProvider.class, remap = false)
public abstract class MixinSkinProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BedrockSkinBridge");

    @Inject(method = "setSkin", at = @At("HEAD"))
    private void bsb$onSetSkin(UserConnection user, UUID playerUuid, SkinData skin, CallbackInfo ci) {
        if (skin == null || playerUuid == null) return;

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("setSkin called: uuid={} thread={}", playerUuid, Thread.currentThread().getName());
        }

        // 只跳过 persona 皮肤 (角色编辑器, JE 无法渲染)
        if (skin.persona()) return;
        if (skin.personaPieces() != null && !skin.personaPieces().isEmpty()) return;
        // 5D/7D: skinResourcePatch 指向非标准几何
        if (BedrockSkinHandler.isCustomGeometry(skin.skinResourcePatch(), skin.geometryData())) return;
        if (skin.skinData() == null) return;

        // 缓存皮肤图片
        BedrockSkinCache.cacheSkinData(playerUuid, skin);

        // 缓存真实用户名
        String realName = resolveUsername(user, playerUuid);
        if (realName != null && !realName.isEmpty()) {
            BedrockSkinCache.cachePlayerName(playerUuid, realName);
        }
    }

    /**
     * 拦截 SkinProvider.getClientPlayerSkin, 在登录握手阶段向 BE 服务器提交初始皮肤。
     *
     * ViaBedrock 的 LoginPackets HELLO 处理中, 若 authData.getSkinJwt()==null,
     * 会用 getClientPlayerSkin 返回的 claims 构造登录 JWT (皮肤随登录提交给服务器)。
     * 默认返回 Steve 皮肤。本方法在缓存命中时替换为缓存的 JE 皮肤,
     * 使服务器从一开始就登记正确皮肤, 其他 BE 玩家即可看到。
     *
     * 注意: 该方法在登录握手阶段同步执行, 无法异步下载 LittleSkin,
     * 因此仅在磁盘缓存命中时生效 (第二次及以后进服)。
     */
    @Inject(method = "getClientPlayerSkin", at = @At("RETURN"), cancellable = true)
    private void bsb$onGetClientPlayerSkin(UserConnection user, CallbackInfoReturnable<Map<String, Object>> cir) {
        try {
            Map<String, Object> claims = cir.getReturnValue();
            if (claims == null) return;

            String username = user.getProtocolInfo().getUsername();
            if (username == null || username.isEmpty()) return;

            LittleSkinCache.CachedSkin cached = LittleSkinCache.load(username);
            if (cached == null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("getClientPlayerSkin: 缓存未命中, 保留默认 Steve (username={})", username);
                }
                return;
            }

            // 替换为缓存的 JE 皮肤
            String skinBase64 = Base64.getEncoder().encodeToString(ImageType.getImageData(cached.skin));
            String resourcePatch = BedrockSkinHandler.buildStandardResourcePatch(cached.slim);
            claims.put("SkinData", skinBase64);
            claims.put("SkinImageWidth", cached.skin.getWidth());
            claims.put("SkinImageHeight", cached.skin.getHeight());
            claims.put("SkinResourcePatch",
                Base64.getEncoder().encodeToString(resourcePatch.getBytes(StandardCharsets.UTF_8)));
            claims.put("ArmSize", cached.slim ? "slim" : "wide");
            claims.put("TrustedSkin", true);
            claims.put("SkinColor", "#0");

            // 注入披风 (若有缓存), 使服务器从登录起就登记披风
            if (cached.cape != null) {
                String capeBase64 = Base64.getEncoder()
                    .encodeToString(ImageType.getImageData(cached.cape));
                claims.put("CapeData", capeBase64);
                claims.put("CapeImageWidth", cached.cape.getWidth());
                claims.put("CapeImageHeight", cached.cape.getHeight());
                claims.put("CapeOnClassicSkin", true);
                LOGGER.info("[BedrockSkinBridge] 已注入缓存 JE 披风到登录 JWT (username={})", username);
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("getClientPlayerSkin: 无披风缓存, 不注入 CapeData (username={})", username);
                }
            }
            cir.setReturnValue(claims);

            LOGGER.info("[BedrockSkinBridge] 已注入缓存 JE 皮肤到登录 JWT (username={}, slim={})",
                username, cached.slim);
        } catch (Exception e) {
            // 注入失败不影响登录, 保持默认皮肤
            LOGGER.warn("[BedrockSkinBridge] getClientPlayerSkin 注入失败: {}", e.getMessage());
        }
    }

    private static String resolveUsername(UserConnection user, UUID uuid) {
        try {
            net.raphimc.viabedrock.protocol.storage.PlayerListStorage pls =
                user.get(net.raphimc.viabedrock.protocol.storage.PlayerListStorage.class);
            if (pls != null) {
                com.viaversion.viaversion.util.Pair<Long, String> entry = pls.getPlayer(uuid);
                if (entry != null) {
                    return entry.value();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
