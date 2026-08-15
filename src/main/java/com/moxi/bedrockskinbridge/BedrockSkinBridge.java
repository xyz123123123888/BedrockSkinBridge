package com.moxi.bedrockskinbridge;

import com.moxi.bedrockskinbridge.skin.BedrockSkinProvider;
import com.moxi.bedrockskinbridge.skin.CSLInjector;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viafabricplus.ViaFabricPlus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * BedrockSkinBridge — JE/BE 跨端皮肤桥接 Mod。
 *
 * 功能:
 * 1. JE 玩家看到 BE 普通皮肤 (5D/7D 回退 Steve)
 * 2. BE 玩家看到 JE 皮肤 (通过 LittleSkin 皮肤库)
 *
 * 依赖:
 * - CustomSkinLoader (万用皮肤补丁) — 皮肤渲染
 * - Fabric API — 基础事件
 * - ViaFabricPlus + ViaBedrock — 跨端协议 (推荐)
 *
 * 场景: Java 玩家通过 ViaFabricPlus 加入 Bedrock 服务器
 */
public class BedrockSkinBridge implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("BedrockSkinBridge");

    /**
     * 100s 延迟皮肤重发标志位。
     * 每次进服重置为 false, 延迟任务触发后置 true, 保证仅触发一次。
     */
    private volatile boolean delayedSkinSent = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[BedrockSkinBridge] 初始化中...");

        // 1. 初始化 CSL 反射
        boolean cslReady = CSLInjector.init();
        if (cslReady) {
            LOGGER.info("[BedrockSkinBridge] CustomSkinLoader 已检测到, BE 皮肤注入就绪");
        } else {
            LOGGER.warn("[BedrockSkinBridge] CustomSkinLoader 未检测到, BE 皮肤将无法显示");
        }

        // 2. 监听玩家加入世界
        //    SkinProvider 注册延迟到 JOIN 事件, 因为 ViaBedrock 协议加载晚于 onInitializeClient,
        //    在 onInitializeClient 注册会被 ViaBedrock 的默认 provider 覆盖。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            onPlayerJoin(handler, client);
        });

        LOGGER.info("[BedrockSkinBridge] 初始化完成 (SkinProvider 将在 JOIN 时注册)");
    }

    /**
     * 注册自定义 SkinProvider, 覆盖 ViaBedrock 的默认实现。
     * 如果 ViaBedrock 未安装, 静默跳过。
     */
    private void registerSkinProvider() {
        try {
            Via.getManager().getProviders().register(
                net.raphimc.viabedrock.protocol.provider.SkinProvider.class,
                new BedrockSkinProvider()
            );
            LOGGER.info("[BedrockSkinBridge] ViaBedrock SkinProvider 覆盖注册成功");
        } catch (NoClassDefFoundError e) {
            LOGGER.warn("[BedrockSkinBridge] ViaBedrock 未安装, SkinProvider 覆盖跳过");
        } catch (Exception e) {
            LOGGER.warn("[BedrockSkinBridge] SkinProvider 注册失败: {}", e.getMessage());
        }
    }

    /**
     * 玩家加入世界后:
     * 1. 发送聊天栏提醒 (仅本 Mod 安装者可见)
     * 2. 注册 SkinProvider 覆盖
     * 3. 发送 JE 皮肤给 BE 服务器
     */
    private void onPlayerJoin(ClientPacketListener handler, Minecraft client) {
        try {
            // 1. 发送聊天栏提醒
            sendReminder(client);

            // 2. 延迟注册 SkinProvider
            registerSkinProvider();

            // 3. 获取 ViaVersion UserConnection
            UserConnection user = ViaFabricPlus.getImpl().getPlayNetworkUserConnection();
            System.out.println("[BedrockSkinBridge] onPlayerJoin: user=" + (user == null ? "NULL" : user.getClass().getSimpleName()));
            if (user == null) {
                System.out.println("[BedrockSkinBridge]   getPlayNetworkUserConnection() returned null");
                return;
            }

            // 4. 获取 JE 玩家信息
            String username = client.getUser().getName();
            UUID uuid = client.getUser().getProfileId();
            System.out.println("[BedrockSkinBridge]   local player: name=" + username + " uuid=" + uuid);
            if (username == null || uuid == null) return;

            // 5. 发送 JE 皮肤给 BE 服务器
            BedrockSkinProvider.sendJavaSkin(user, uuid, username);
            LOGGER.info("[BedrockSkinBridge] 已触发 JE 皮肤发送到 BE 服务器");

            // 6. 立即发送 + 60s 补发一次, 触发服务器"更改皮肤"广播到其他玩家视角。
            //    立即发送时服务器可能尚未登记玩家而失败, 或 JOIN 时 protocolInfo.getUuid()
            //    尚未初始化。60s 后玩家已完全进入世界且会话 UUID 已稳定, 补发一次兜底。
            //    最多补发一次, 避免多次广播导致其他 BE 玩家聊天栏提示刷屏。
            //    连 Java 服务器时包会被直接丢弃, 无需担心兼容问题。
            delayedSkinSent = false;
            scheduleRetrySkinSend(user, uuid, username);
        } catch (NoClassDefFoundError e) {
            System.out.println("[BedrockSkinBridge] onPlayerJoin NoClassDefFoundError: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("[BedrockSkinBridge] onPlayerJoin FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * JOIN 后 60s 补发一次 JE 皮肤, 触发服务器"更改皮肤"广播。
     * 仅补发一次 (delayedSkinSent 标志位), 避免刷屏; 下次进服才重新触发。
     */
    private void scheduleRetrySkinSend(UserConnection user, UUID uuid, String username) {
        new Thread(() -> {
            try {
                Thread.sleep(60_000); // 60 秒
                if (delayedSkinSent) {
                    return; // 已触发, 跳过
                }
                delayedSkinSent = true;
                System.out.println("[BedrockSkinBridge] 60s 补发: 重发 JE 皮肤 (触发更改皮肤广播)");
                BedrockSkinProvider.sendJavaSkin(user, uuid, username);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "BSB-RetrySkinSend").start();
    }

    /**
     * 发送聊天栏提醒 (仅本 Mod 安装者可见, 纯客户端消息)。
     * 进入任意服务器都会弹出。
     */
    private void sendReminder(Minecraft client) {
        try {
            if (client.player == null) return;
            // [BSB]>> bedrockskinbridge模组已接管部分皮肤加载线
            // 颜色: [BSB]>> 用金色, 正文用亮绿色
            Component msg = Component.literal("[BSB]>> ")
                .withColor(0xFFAA00)  // 金色
                .append(Component.literal("bedrockskinbridge模组已接管部分皮肤加载线")
                    .withColor(0x55FF55));  // 亮绿色
            client.player.sendSystemMessage(msg);
        } catch (Exception e) {
            // 提醒失败不影响主流程
            System.out.println("[BedrockSkinBridge] sendReminder failed: " + e.getMessage());
        }
    }
}
