package com.moxi.bedrockskinbridge.mixin;

import com.mojang.authlib.GameProfile;
import com.moxi.bedrockskinbridge.skin.BedrockSkinCache;
import com.moxi.bedrockskinbridge.skin.BedrockSkinTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 拦截 PlayerInfo.createSkinLookup, 修复 ViaBedrock 把 GameProfile.name 改成 encodeUUID 乱码
 * 导致 CSL 无法加载皮肤的问题。
 *
 * 简单策略:
 * - 本地玩家 (JE 玩家自己): 强制走 LittleSkin (用真实用户名查 CSL), 绝不用 BE 缓存图片
 * - 其他玩家: 优先用 BE 缓存图片 (BE 玩家不在 LittleSkin 注册, 查 CSL 必 miss)
 *            若无 BE 图片则回退 LittleSkin (可能是 JE 玩家)
 *
 * 性能: 本方法是渲染线程高频路径, 所有日志用 debug 级别 (默认不输出)。
 */
@Mixin(PlayerInfo.class)
public abstract class MixinPlayerInfo {

    private static final Logger LOGGER = LoggerFactory.getLogger("BedrockSkinBridge");
    @Unique
    private static volatile String cachedLocalName;

    @Inject(method = "createSkinLookup", at = @At("HEAD"), cancellable = true)
    private static void bsb$onCreateSkinLookup(GameProfile profile,
                                                 CallbackInfoReturnable<Supplier<PlayerSkin>> cir) {
        // 检测 ViaBedrock 的 encodeUUID 格式 name (以 § 开头的不可见字符串)
        String name = profile.name();
        if (name == null || name.isEmpty() || !name.startsWith("§")) return;

        UUID uuid = profile.id();
        if (uuid == null) return;

        // 从缓存获取真实用户名
        String realName = BedrockSkinCache.getRealName(uuid);
        boolean local = isLocalPlayer(realName);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("createSkinLookup: uuid={} realName={} isLocal={}", uuid, realName, local);
        }
        if (realName == null || realName.isEmpty()) return;

        // 本地玩家: 强制走 LittleSkin (CSL 用真实用户名查 JE 皮肤库)
        // 注意: ViaBedrock 给本地玩家分配 BE UUID, 与 JE UUID 不同,
        // 所以不能用 UUID 比较, 必须用用户名比较
        if (local) {
            GameProfile newProfile = new GameProfile(uuid, realName);
            SkinManager skinManager = Minecraft.getInstance().getSkinManager();
            Supplier<PlayerSkin> supplier = skinManager.createLookup(newProfile, false);
            cir.setReturnValue(supplier);
            LOGGER.debug("-> LittleSkin (local player) {}", realName);
            return;
        }

        // 其他玩家: 优先 BE 缓存图片
        PlayerSkin beSkin = BedrockSkinTextureManager.createPlayerSkin(uuid);
        if (beSkin != null) {
            cir.setReturnValue(() -> beSkin);
            LOGGER.debug("-> BE skin applied for {}", realName);
            return;
        }

        // 回退: LittleSkin (可能是 JE 玩家)
        GameProfile newProfile = new GameProfile(uuid, realName);
        SkinManager skinManager = Minecraft.getInstance().getSkinManager();
        Supplier<PlayerSkin> supplier = skinManager.createLookup(newProfile, false);
        cir.setReturnValue(supplier);
        LOGGER.debug("-> LittleSkin fallback for {}", realName);
    }

    /**
     * 判断是否本地玩家。
     * ViaBedrock 给本地玩家分配 BE UUID (不同于 JE UUID),
     * 所以不能用 UUID 比较, 必须用用户名比较。
     * 本地玩家名缓存, 避免高频路径反复调用 getUser()。
     */
    @Unique
    private static boolean isLocalPlayer(String realName) {
        if (realName == null || realName.isEmpty()) return false;
        String localName = cachedLocalName;
        if (localName == null) {
            try {
                localName = Minecraft.getInstance().getUser().getName();
                cachedLocalName = localName;
            } catch (Exception e) {
                return false;
            }
        }
        return realName.equals(localName);
    }
}