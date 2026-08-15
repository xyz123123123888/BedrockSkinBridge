/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 Moxi
 */
package com.moxi.bedrockskinbridge.skin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.sounds.SoundEvents;

/**
 * 屏幕大字提醒 + 村民交易成功音效。
 *
 * 通过直接调用客户端 title 包的 handler (setTitleText/setSubtitleText/setTitlesAnimation)
 * 在屏幕中央显示大字提醒, 无需服务器权限 (原版 /title 指令需要 op 权限)。
 * 提醒后播放村民交易成功音效 (VILLAGER_YES) 提示玩家。
 */
public final class SkinReminder {

    private SkinReminder() {
    }

    /**
     * 在屏幕中央显示 title + subtitle 大字提醒, 并播放村民交易成功音效。
     * 必须在主线程 (渲染线程) 调用。
     *
     * @param client     Minecraft 实例
     * @param title      title 正文 (绿色大字)
     * @param subtitle   subtitle 副标题 (白色小字)
     */
    public static void show(Minecraft client, String title, String subtitle) {
        if (client.player == null || client.getConnection() == null) {
            return;
        }
        try {
            // 淡入 10tick / 停留 50tick / 淡出 15tick
            client.getConnection().setTitlesAnimation(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
            client.getConnection().setTitleText(new ClientboundSetTitleTextPacket(
                Component.literal(title).withColor(0x55FF55)));  // 亮绿大字
            client.getConnection().setSubtitleText(new ClientboundSetSubtitleTextPacket(
                Component.literal(subtitle).withColor(0xFFFFFF)));  // 白色副标题
            // 村民交易成功音效
            client.player.playSound(SoundEvents.VILLAGER_YES, 1.0f, 1.0f);
        } catch (Exception e) {
            // 提醒失败不影响主流程
        }
    }
}