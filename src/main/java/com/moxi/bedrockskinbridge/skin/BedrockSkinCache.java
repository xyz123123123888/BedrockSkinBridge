/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 Moxi
 */
package com.moxi.bedrockskinbridge.skin;

import net.raphimc.viabedrock.protocol.model.SkinData;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BE 玩家皮肤缓存。
 * 在 setSkin 中缓存 UUID→真实用户名 和 UUID→皮肤图片,
 * 供 MixinPlayerInfo 查询。
 */
public class BedrockSkinCache {

    /** UUID → 真实 BE 用户名 (从 PlayerListStorage 获取) */
    private static final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    /** UUID → BE 皮肤图片 (从 SkinData.skinData() 获取) */
    private static final Map<UUID, BufferedImage> skinCache = new ConcurrentHashMap<>();

    /** UUID → 披风图片 */
    private static final Map<UUID, BufferedImage> capeCache = new ConcurrentHashMap<>();

    /** UUID → 是否 slim (Alex) 模型 */
    private static final Map<UUID, Boolean> slimCache = new ConcurrentHashMap<>();

    public static void cachePlayerName(UUID uuid, String realName) {
        nameCache.put(uuid, realName);
    }

    /**
     * 从 SkinData 提取皮肤和披风图片并缓存。
     * SkinData.skinData() / capeData() 本身就是 BufferedImage (ViaBedrock 已解码)。
     */
    public static void cacheSkinData(UUID uuid, SkinData skin) {
        if (skin == null) return;
        if (skin.skinData() != null) {
            skinCache.put(uuid, skin.skinData());
        }
        if (skin.capeData() != null) {
            capeCache.put(uuid, skin.capeData());
        }
        // 缓存模型类型 (armSize: "slim"/"wide")
        slimCache.put(uuid, "slim".equals(skin.armSize()));
    }

    public static boolean isSlim(UUID uuid) {
        Boolean slim = slimCache.get(uuid);
        return slim != null && slim;
    }

    public static String getRealName(UUID uuid) {
        return nameCache.get(uuid);
    }

    public static void cacheSkin(UUID uuid, BufferedImage skin) {
        skinCache.put(uuid, skin);
    }

    public static BufferedImage getSkinImage(UUID uuid) {
        return skinCache.get(uuid);
    }

    public static void cacheCape(UUID uuid, BufferedImage cape) {
        capeCache.put(uuid, cape);
    }

    public static BufferedImage getCapeImage(UUID uuid) {
        return capeCache.get(uuid);
    }

    public static void clear() {
        nameCache.clear();
        skinCache.clear();
        capeCache.clear();
        slimCache.clear();
    }
}
