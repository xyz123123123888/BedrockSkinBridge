package com.moxi.bedrockskinbridge.skin;

import java.awt.image.BufferedImage;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BE 皮肤处理核心逻辑。
 *
 * 功能 1: 拦截 BE 玩家皮肤 → 注入 CSL 缓存 → JE 客户端渲染
 *   - 普通皮肤 (标准 humanoid 几何): 提取图片注入 CSL
 *   - 5D/7D 皮肤 (自定义几何): 跳过, 显示默认 Steve
 *
 * 功能 2: 从 LittleSkin 获取 JE 玩家皮肤 → 发送给 BE 服务器
 */
public class BedrockSkinHandler {

    // Bedrock 标准几何模型标识
    private static final String GEO_HUMANOID = "geometry.humanoid.custom";
    private static final String GEO_HUMANOID_SLIM = "geometry.humanoid.customSlim";

    /**
     * 判断 BE 皮肤是否使用自定义几何模型 (5D/7D 皮肤)。
     *
     * 5D/7D 皮肤使用非标准几何模型 (如动物、机甲等)，
     * Java 版无法原生渲染，应回退为默认 Steve。
     *
     * @param skinResourcePatch 皮肤资源补丁 JSON
     * @param geometryData      完整几何模型 JSON (可为空字符串)
     * @return true 表示是 5D/7D 皮肤
     */
    public static boolean isCustomGeometry(String skinResourcePatch, String geometryData) {
        // 优先解析 skinResourcePatch 指定的几何名 (最可靠依据)。
        // 很多普通 BE 皮肤即使带 geometryData, 其 skinResourcePatch 也明确指向
        // 标准 humanoid 几何, 此时应正常渲染, 不能仅因 geometryData 非空就判定为 5D/7D。
        String defaultGeo = extractDefaultGeometry(skinResourcePatch);
        if (defaultGeo != null) {
            return !GEO_HUMANOID.equals(defaultGeo)
                && !GEO_HUMANOID_SLIM.equals(defaultGeo);
        }
        // skinResourcePatch 无有效几何名时, 才退而用 geometryData 判断:
        // geometryData 是非空且非 "null" 的自定义几何 → 5D/7D
        if (geometryData != null && !geometryData.isEmpty()
            && !"null".equalsIgnoreCase(geometryData)) {
            return true;
        }
        return false;
    }

    /**
     * 从 skinResourcePatch JSON 中提取 geometry.default 的几何名。
     * 无法解析/不存在时返回 null。
     */
    private static String extractDefaultGeometry(String skinResourcePatch) {
        if (skinResourcePatch == null || skinResourcePatch.isEmpty()) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(skinResourcePatch).getAsJsonObject();
            if (json.has("geometry") && json.get("geometry").isJsonObject()) {
                JsonObject geo = json.getAsJsonObject("geometry");
                if (geo.has("default") && geo.get("default").isJsonPrimitive()) {
                    return geo.get("default").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 处理收到的 BE 玩家皮肤。
     * 由 BedrockSkinProvider.setSkin() 调用。
     *
     * @param uuid     BE 玩家 UUID
     * @param cslKey   CSL 缓存 key (GameProfile.name = StringUtil.encodeUUID(uuid))
     * @param skinImage  皮肤图片 (BufferedImage)
     * @param skinResourcePatch 皮肤资源补丁 JSON
     * @param capeImage 披风图片, 可为 null
     */
    public static void handleBedrockSkin(UUID uuid, String cslKey,
                                          BufferedImage skinImage,
                                          String skinResourcePatch,
                                          BufferedImage capeImage) {
        if (skinImage == null) return;

        // 5D/7D 皮肤: 不注入 CSL, 让 JE 显示默认 Steve
        if (isCustomGeometry(skinResourcePatch, null)) {
            return;
        }

        // CSL 未安装: 无法注入
        if (!CSLInjector.isCSLInstalled()) {
            return;
        }

        // 将皮肤图片保存到 CSL 数据目录, 生成 (LOCAL) 假 URL 注入 CSL
        // CSL 仅识别 (LOCAL)/(LEGACY)/(LOCAL_LEGACY)/(BASE64)/http(s):// 前缀
        // file:// 会被静默丢弃, 必须用 (LOCAL)relPath 形式
        String skinUrl = saveTempSkin(uuid, skinImage);
        if (skinUrl == null) return;

        // 判断模型类型
        String model = isSlimModel(skinResourcePatch) ? "slim" : "default";

        // 注入 CSL 缓存
        CSLInjector.injectSkin(cslKey, skinUrl, model, null);
    }

    /**
     * 将皮肤图片保存到 CSL 数据目录, 返回 (LOCAL) 假 URL。
     *
     * CSL 的 HttpTextureUtil.toHttpTextureInfo() 仅识别 5 类前缀:
     *   http(s):// / (LOCAL) / (LEGACY) / (LOCAL_LEGACY) / (BASE64)
     * file:// 会被静默丢弃。必须用 (LOCAL)relPath 形式, relPath 相对于
     * .minecraft/CustomSkinLoader/ 目录。
     */
    private static String saveTempSkin(UUID uuid, BufferedImage image) {
        try {
            // CSL 数据目录: .minecraft/CustomSkinLoader/
            java.io.File cslDataDir = new java.io.File(
                net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().toFile(),
                "CustomSkinLoader"
            );

            // 子目录: bedrockskinbridge/skins/
            java.io.File skinDir = new java.io.File(
                cslDataDir, "bedrockskinbridge" + java.io.File.separator + "skins"
            );
            if (!skinDir.exists()) skinDir.mkdirs();

            // 保存图片: <uuid>.png
            java.io.File skinFile = new java.io.File(skinDir, uuid.toString() + ".png");
            javax.imageio.ImageIO.write(image, "png", skinFile);

            // 构造 (LOCAL) 假 URL: CSL 会用 new File(DATA_DIR, relPath) 读取
            String relPath = "bedrockskinbridge/skins/" + uuid.toString() + ".png";
            return "(LOCAL)" + relPath;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断是否为 slim (Alex) 模型。
     */
    private static boolean isSlimModel(String skinResourcePatch) {
        if (skinResourcePatch == null || skinResourcePatch.isEmpty()) {
            return false;
        }
        try {
            JsonObject json = JsonParser.parseString(skinResourcePatch).getAsJsonObject();
            if (json.has("geometry")) {
                JsonObject geo = json.getAsJsonObject("geometry");
                String defaultGeo = geo.has("default")
                    ? geo.get("default").getAsString() : "";
                return GEO_HUMANOID_SLIM.equals(defaultGeo);
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * 构造标准 Bedrock 皮肤资源补丁 JSON。
     * 用于将 JE 皮肤发送给 BE 服务器。
     *
     * @param slim 是否为 slim (Alex) 模型
     * @return skinResourcePatch JSON 字符串
     */
    public static String buildStandardResourcePatch(boolean slim) {
        JsonObject geo = new JsonObject();
        geo.addProperty("default", slim ? GEO_HUMANOID_SLIM : GEO_HUMANOID);
        JsonObject patch = new JsonObject();
        patch.add("geometry", geo);
        return patch.toString();
    }
}
