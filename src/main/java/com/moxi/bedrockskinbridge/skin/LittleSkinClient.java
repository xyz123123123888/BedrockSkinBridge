package com.moxi.bedrockskinbridge.skin;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import javax.imageio.ImageIO;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LittleSkin 皮肤站 API 客户端。
 * 通过 CustomSkinAPI 协议获取 JE 玩家皮肤。
 * API: https://littleskin.cn/csl/{username}.json
 */
public class LittleSkinClient {

    private static final String API_ROOT = "https://littleskin.cn/csl/";

    /**
     * 从 LittleSkin 获取玩家皮肤信息。
     *
     * @param username JE 玩家用户名
     * @return SkinInfo 包含皮肤图片 URL 和模型类型，null 表示未找到
     */
    public static SkinInfo fetchSkin(String username) {
        try {
            URL url = URI.create(API_ROOT + username + ".json").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) {
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();

            SkinInfo info = new SkinInfo();

            // 解析 skins map (优先) 或单个 skin 字段
            if (json.has("skins")) {
                JsonObject skins = json.getAsJsonObject("skins");
                if (skins.has("default")) {
                    info.skinFileName = skins.get("default").getAsString();
                    info.model = "default";
                } else if (skins.has("slim")) {
                    info.skinFileName = skins.get("slim").getAsString();
                    info.model = "slim";
                }
            } else if (json.has("skin")) {
                info.skinFileName = json.get("skin").getAsString();
                info.model = "default";
            }

            // 解析披风
            if (json.has("cape")) {
                info.capeFileName = json.get("cape").getAsString();
            }

            if (info.skinFileName == null) {
                return null;
            }

            // 构建完整 URL
            info.skinUrl = API_ROOT + "textures/" + info.skinFileName;
            if (info.capeFileName != null) {
                info.capeUrl = API_ROOT + "textures/" + info.capeFileName;
            }

            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 下载皮肤图片。
     *
     * @param imageUrl 皮肤图片 URL
     * @return BufferedImage, null 表示下载失败
     */
    public static BufferedImage downloadImage(String imageUrl) {
        try {
            URL url = URI.create(imageUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() != 200) {
                return null;
            }

            InputStream is = conn.getInputStream();
            BufferedImage image = ImageIO.read(is);
            is.close();
            return image;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 皮肤信息数据类。
     */
    public static class SkinInfo {
        public String skinFileName;
        public String capeFileName;
        public String skinUrl;
        public String capeUrl;
        public String model = "default";
    }
}
