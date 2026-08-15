/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 Moxi
 */
package com.moxi.bedrockskinbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;

/**
 * 本 Mod 的持久化配置。
 *
 * 存储于 <游戏目录>/bedrockskinbridge/config.json
 *
 * 当前字段:
 *   autoReconnect - 皮肤更新/选择后是否自动断开重连 (默认 false)
 *                   登录注入只在进服时生效, 选择新皮肤后需重进才有效。
 *                   开启后, 本 Mod 会在选择云端皮肤后自动断开并重连当前服务器。
 */
public final class SkinBridgeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static File configFile;
    private static ConfigData data = new ConfigData();

    private SkinBridgeConfig() {
    }

    /** 配置数据结构 (与 json 对应)。 */
    public static final class ConfigData {
        public boolean autoReconnect = false;
    }

    private static File configFile() {
        if (configFile == null) {
            configFile = new File(
                FabricLoader.getInstance().getGameDir().toFile(),
                "bedrockskinbridge" + File.separator + "config.json");
        }
        return configFile;
    }

    /** 从磁盘加载配置 (幂等, 可重复调用)。 */
    public static void load() {
        try {
            File file = configFile();
            if (file.exists()) {
                try (FileReader r = new FileReader(file, StandardCharsets.UTF_8)) {
                    ConfigData loaded = GSON.fromJson(r, ConfigData.class);
                    if (loaded != null) {
                        data = loaded;
                    }
                }
            }
        } catch (Exception e) {
            // 配置损坏时使用默认值
        }
    }

    /** 保存配置到磁盘。 */
    public static void save() {
        try {
            File file = configFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
        } catch (Exception e) {
            // 保存失败不影响运行
        }
    }

    public static boolean isAutoReconnect() {
        return data.autoReconnect;
    }

    public static void setAutoReconnect(boolean value) {
        data.autoReconnect = value;
        save();
    }
}