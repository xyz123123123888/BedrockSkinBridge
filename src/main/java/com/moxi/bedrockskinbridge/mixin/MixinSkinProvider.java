package com.moxi.bedrockskinbridge.mixin;

import com.moxi.bedrockskinbridge.skin.BedrockSkinCache;
import com.moxi.bedrockskinbridge.skin.BedrockSkinHandler;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
