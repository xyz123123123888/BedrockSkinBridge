/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 Moxi
 */
package com.moxi.bedrockskinbridge.skin;

import com.moxi.bedrockskinbridge.SkinBridgeConfig;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * JE 皮肤云端/本地监控算法。
 *
 * 进服时对比 LittleSkin 云端当前皮肤哈希 与 本地缓存记录的哈希:
 *   - 云端无皮肤          → 保留本地, 不处理
 *   - 本地无文件          → 直接下载云端皮肤并缓存 (无冲突)
 *   - 云端哈希 == 本地哈希 → 皮肤相同, 无操作
 *   - 云端哈希 != 本地哈希 → 皮肤已变更, 弹出原生确认界面让玩家选择:
 *                            云端(下载并替换) / 本地(保留)
 *   - 玩家不操作(关闭/超时) → 默认保留本地 (最安全)
 *
 * 选择云端后写入磁盘缓存, 下次进服时 getClientPlayerSkin 登录注入生效。
 * 若开启自动重连 (SkinBridgeConfig.autoReconnect), 选择云端后自动断开重连当前服务器, 立即生效。
 */
public final class SkinMonitor {

    private SkinMonitor() {
    }

    /**
     * 启动监控。应在进服后异步线程调用 (内部有网络请求会阻塞)。
     *
     * @param username JE 当前玩家用户名
     * @param client   Minecraft 实例
     */
    public static void checkAndHandle(String username, Minecraft client) {
        if (username == null || username.isEmpty()) {
            return;
        }
        try {
            // 拉取云端皮肤信息, 得到云端纹理哈希 (skinFileName)
            LittleSkinClient.SkinInfo cloud = LittleSkinClient.fetchSkin(username);
            if (cloud == null || cloud.skinFileName == null) {
                // 云端无皮肤, 保留本地
                return;
            }
            String cloudHash = cloud.skinFileName;
            String localHash = LittleSkinCache.getHash(username);

            if (localHash == null) {
                // 本地无文件 → 直接下载云端 (无冲突)
                boolean ok = downloadAndSave(username, cloud);
                if (ok) {
                    remind(client, "皮肤已缓存", "已下载云端皮肤, 下次进服生效");
                } else {
                    remind(client, "皮肤下载失败", "云端皮肤获取失败");
                }
                return;
            }

            if (cloudHash.equals(localHash)) {
                // 皮肤相同, 无操作
                return;
            }

            // 皮肤已变更 → 弹 UI 让玩家选择
            showChoiceGui(username, cloud, client);
        } catch (Exception e) {
            // 监控失败不影响登录
        }
    }

    /**
     * 下载云端皮肤并写入磁盘缓存 (校验非空, 避免空指针)。
     *
     * @return true 表示下载并缓存成功
     */
    private static boolean downloadAndSave(String username, LittleSkinClient.SkinInfo cloud) {
        try {
            BufferedImage image = LittleSkinClient.downloadImage(cloud.skinUrl);
            if (image == null) {
                return false;
            }
            boolean slim = "slim".equals(cloud.model);
            LittleSkinCache.save(username, image, slim, cloud.skinFileName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 弹出原生确认界面, 让玩家选择保留云端还是本地。
     * 关闭界面/不操作 → 默认保留本地。
     */
    private static void showChoiceGui(String username, LittleSkinClient.SkinInfo cloud, Minecraft client) {
        client.execute(() -> {
            if (client.player == null) {
                return;
            }
            BooleanConsumer callback = choice -> {
                if (choice) {
                    // 选择云端 → 异步下载避免卡 UI 线程
                    new Thread(() -> {
                        boolean ok = downloadAndSave(username, cloud);
                        boolean reconnect = ok && SkinBridgeConfig.isAutoReconnect();
                        client.execute(() -> {
                            if (ok) {
                                remind(client, "皮肤已更新", "已使用云端最新皮肤");
                                if (reconnect) {
                                    tryReconnect(client);
                                }
                            } else {
                                remind(client, "更新失败", "云端皮肤下载失败, 已保留本地");
                            }
                        });
                    }, "BSB-SkinDownload").start();
                } else {
                    // 选择本地 (或关闭界面) → 保留本地
                    remind(client, "皮肤已保留", "已保留本地缓存皮肤");
                }
            };
            client.setScreenAndShow(new ConfirmScreen(
                callback,
                Component.literal("皮肤已变更"),
                Component.literal("检测到云端皮肤与本地缓存不同(哈希校验失败)。\n请选择保留哪一个？"),
                Component.literal("云端 (下载并替换)"),
                Component.literal("本地 (保留)")));
        });
    }

    /**
     * 自动断开并重连当前服务器, 使新皮肤立即生效。
     * 仅当 SkinBridgeConfig.autoReconnect 开启时调用。
     */
    private static void tryReconnect(Minecraft client) {
        try {
            ServerData serverData = client.getCurrentServer();
            if (serverData == null) {
                return;
            }
            ServerAddress addr = ServerAddress.parseString(serverData.ip);
            client.getConnection().getConnection().disconnect(Component.literal("[BSB] 皮肤已更新, 正在重连..."));
            ConnectScreen.startConnecting(
                new TitleScreen(), client, addr, serverData, false,
                new TransferState(Map.of(), Map.of(), false));
        } catch (Exception e) {
            // 重连失败不影响
        }
    }

    private static void remind(Minecraft client, String title, String subtitle) {
        SkinReminder.show(client, title, subtitle);
    }
}