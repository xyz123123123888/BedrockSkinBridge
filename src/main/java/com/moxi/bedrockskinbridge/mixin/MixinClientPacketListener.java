package com.moxi.bedrockskinbridge.mixin;

import com.moxi.bedrockskinbridge.skin.BedrockSkinCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 handlePlayerInfoUpdate, 从 Entry.displayName 提前缓存真实用户名。
 *
 * 时序问题: ViaBedrock 把 GameProfile.name 改成 encodeUUID 乱码, 但真实用户名写在 displayName 里。
 * PlayerInfo 构造时会调 createSkinLookup —— 此时 BedrockSkinCache 可能为空 (setSkin 还没触发)。
 * 本 Mixin 在 handlePlayerInfoUpdate 入口处提前缓存 displayName → 真实用户名,
 * 确保 createSkinLookup 时 MixinPlayerInfo 能查到真实用户名。
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"))
    private void bsb$onHandlePlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet,
                                                CallbackInfo ci) {
        // 遍历所有 entry, 从 displayName 提取真实用户名缓存
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            Component displayName = entry.displayName();
            if (displayName == null) continue;

            String realName = displayName.getString();
            if (realName == null || realName.isEmpty()) continue;

            // 缓存 UUID → 真实用户名 (供 MixinPlayerInfo 查询)
            BedrockSkinCache.cachePlayerName(entry.profileId(), realName);
        }
    }
}
