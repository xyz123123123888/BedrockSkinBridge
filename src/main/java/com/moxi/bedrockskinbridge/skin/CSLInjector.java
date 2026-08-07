package com.moxi.bedrockskinbridge.skin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通过反射注入 CustomSkinLoader (CSL) 的皮肤缓存。
 *
 * CSL 15.0.1 核实结论 (源码 SHA ebea66d2):
 *   - profileCache 字段: customskinloader.CustomSkinLoader.profileCache (private static)
 *   - 缓存 Map: ProfileCache.cachedProfiles, key = username.toLowerCase()
 *   - updateCache(String username, UserProfile profile, boolean saveLocalProfile)
 *   - 用 3 参数版本传 false: 避免把 BE 皮肤写入 ProfileCache/<username>.json 污染本地缓存
 *
 * CSL 没有提供直接的运行时注入 API，只能反射。
 */
public class CSLInjector {

    private static boolean initialized = false;
    private static boolean available = false;
    private static Object profileCache;
    private static Method updateCacheMethod;
    private static Class<?> userProfileClass;

    // UserProfile 字段
    private static Field skinUrlField;
    private static Field modelField;
    private static Field capeUrlField;
    private static Field elytraUrlField;

    /**
     * 初始化反射引用。如果 CSL 未安装则返回 false。
     */
    public static boolean init() {
        if (initialized) return available;
        initialized = true;

        try {
            // 获取 CustomSkinLoader 主类
            Class<?> cslClass = Class.forName("customskinloader.CustomSkinLoader");

            // 获取 profileCache 字段 (private static)
            Field profileCacheField = cslClass.getDeclaredField("profileCache");
            profileCacheField.setAccessible(true);
            profileCache = profileCacheField.get(null);

            // 获取 UserProfile 类
            userProfileClass = Class.forName("customskinloader.profile.UserProfile");

            // 获取 updateCache(String, UserProfile, boolean) 3 参数版本
            // 传 false 避免 BE 皮肤落盘污染本地 ProfileCache 目录
            updateCacheMethod = profileCache.getClass().getMethod(
                "updateCache", String.class, userProfileClass, boolean.class
            );

            // 获取 UserProfile 字段
            skinUrlField = userProfileClass.getDeclaredField("skinUrl");
            skinUrlField.setAccessible(true);

            modelField = userProfileClass.getDeclaredField("model");
            modelField.setAccessible(true);

            capeUrlField = userProfileClass.getDeclaredField("capeUrl");
            capeUrlField.setAccessible(true);

            // elytraUrl 字段 (CSL 15.x 新增, 鞘翅贴图)
            try {
                elytraUrlField = userProfileClass.getDeclaredField("elytraUrl");
                elytraUrlField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // 旧版 CSL 无此字段, 忽略
            }

            available = true;
            return true;
        } catch (Exception e) {
            available = false;
            return false;
        }
    }

    /**
     * 将皮肤 URL 注入 CSL 缓存，使 JE 客户端渲染指定玩家的自定义皮肤。
     *
     * @param username 玩家用户名 (CSL 内部 toLowerCase 作 key)
     * @param skinUrl  皮肤图片的直接 URL
     * @param model    模型类型: "default" 或 "slim"
     * @param capeUrl  披风 URL, 可为 null
     * @return true 表示注入成功
     */
    public static boolean injectSkin(String username, String skinUrl, String model, String capeUrl) {
        if (!init() || profileCache == null) return false;

        try {
            // 创建 UserProfile 实例
            Object userProfile = userProfileClass.getDeclaredConstructor().newInstance();

            // 设置字段
            skinUrlField.set(userProfile, skinUrl);
            modelField.set(userProfile, model != null ? model : "default");
            if (capeUrl != null) {
                capeUrlField.set(userProfile, capeUrl);
            }

            // 调用 updateCache(username, userProfile, false)
            // false = 不写入本地 ProfileCache/<username>.json, 避免污染本地缓存
            updateCacheMethod.invoke(profileCache, username, userProfile, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查 CSL 是否已安装。
     */
    public static boolean isCSLInstalled() {
        if (!initialized) init();
        return available;
    }
}
