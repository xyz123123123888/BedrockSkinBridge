/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 Moxi
 */
package com.moxi.bedrockskinbridge.skin;

import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * JE 本地皮肤磁盘缓存。
 *
 * 用途: getClientPlayerSkin 在登录握手阶段是同步调用的, 无法此时异步下载 LittleSkin 皮肤。
 * 因此首次进服时登录只能提交默认 Steve; 本缓存在进服后异步下载成功后写入磁盘,
 * 使 "第二次及以后" 进服时能在登录阶段同步读回缓存的 JE 皮肤并注入登录 JWT,
 * 让服务器从一开始就登记正确皮肤, 其他 BE 玩家即可稳定看到。
 *
 * 缓存目录: <游戏目录>/bedrockskinbridge/cache/
 *   <小写用户名>.png   - 皮肤图片
 *   <小写用户名>.model - "slim" / "wide"
 *   <小写用户名>.cape  - 披风图片 (可选)
 */
public final class LittleSkinCache {

    /** 缓存根目录 (相对游戏目录) */
    private static final String CACHE_REL = "bedrockskinbridge" + File.separator + "cache";

    private LittleSkinCache() {
    }

    private static File cacheDir() {
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), CACHE_REL);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static String safeName(String username) {
        return username == null ? "unknown" : username.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * 把下载好的 JE 皮肤写入磁盘缓存, 供下次登录注入。
     *
     * @param username JE 用户名
     * @param skin     皮肤图片
     * @param slim     是否 slim (Alex) 模型
     * @param hash     云端纹理哈希 (LittleSkin skinFileName), 用于监控算法对比
     */
    public static void save(String username, BufferedImage skin, boolean slim, String hash) {
        if (skin == null) {
            return;
        }
        try {
            File dir = cacheDir();
            String name = safeName(username);
            ImageIO.write(skin, "png", new File(dir, name + ".png"));
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(new File(dir, name + ".model")), StandardCharsets.UTF_8)) {
                w.write(slim ? "slim" : "wide");
            }
            if (hash != null && !hash.isEmpty()) {
                try (OutputStreamWriter w = new OutputStreamWriter(
                        new FileOutputStream(new File(dir, name + ".hash")), StandardCharsets.UTF_8)) {
                    w.write(hash);
                }
            }
        } catch (Exception e) {
            // 缓存失败不影响主流程
        }
    }

    /**
     * 把下载好的 JE 披风写入磁盘缓存, 供下次登录注入。
     *
     * @param username JE 用户名
     * @param cape     披风图片, null 则忽略
     */
    public static void saveCape(String username, BufferedImage cape) {
        if (cape == null) {
            return;
        }
        try {
            File dir = cacheDir();
            String name = safeName(username);
            ImageIO.write(cape, "png", new File(dir, name + ".cape"));
        } catch (Exception e) {
            // 缓存失败不影响主流程
        }
    }

    /**
     * 读取本地缓存记录的云端纹理哈希。
     * 用于和当前云端哈希对比, 判断皮肤是否变化。
     *
     * @return 云端纹理哈希, 无缓存/无记录时返回 null
     */
    public static String getHash(String username) {
        try {
            File dir = cacheDir();
            String name = safeName(username);
            File hashFile = new File(dir, name + ".hash");
            if (!hashFile.exists()) {
                return null;
            }
            try (InputStreamReader r = new InputStreamReader(
                    new FileInputStream(hashFile), StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[256];
                int n;
                while ((n = r.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                return sb.toString().trim();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从磁盘缓存读取 JE 皮肤。未命中返回 null。
     */
    public static CachedSkin load(String username) {
        try {
            File dir = cacheDir();
            String name = safeName(username);
            File png = new File(dir, name + ".png");
            if (!png.exists()) {
                return null;
            }
            BufferedImage skin = ImageIO.read(png);
            if (skin == null) {
                return null;
            }
            boolean slim = false;
            File model = new File(dir, name + ".model");
            if (model.exists()) {
                try (InputStreamReader r = new InputStreamReader(
                        new FileInputStream(model), StandardCharsets.UTF_8)) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[64];
                    int n;
                    while ((n = r.read(buf)) != -1) {
                        sb.append(buf, 0, n);
                    }
                    slim = "slim".equals(sb.toString().trim());
                }
            }

            // 读取披风 (可选)
            BufferedImage cape = null;
            File capeFile = new File(dir, name + ".cape");
            if (capeFile.exists()) {
                cape = ImageIO.read(capeFile);
            }

            return new CachedSkin(skin, slim, cape);
        } catch (Exception e) {
            return null;
        }
    }

    /** 磁盘缓存的皮肤数据。 */
    public static final class CachedSkin {
        public final BufferedImage skin;
        public final boolean slim;
        /** 披风图片, 可能为 null */
        public final BufferedImage cape;

        public CachedSkin(BufferedImage skin, boolean slim, BufferedImage cape) {
            this.skin = skin;
            this.slim = slim;
            this.cape = cape;
        }
    }
}