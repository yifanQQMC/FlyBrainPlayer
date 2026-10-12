package com.flybrain;

import com.flybrain.learn.KnowledgeBase;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 知识库持久化（game bus）。
 *
 * 单独成类而不是塞在 @Mod 主类里：主类订阅 game bus 事件容易因
 * 构造时序/总线注册时机而静默失效——表现是"事件从来不触发"。
 * 独立监听器 + 日志确认，是最省事的验证方式。
 */
@EventBusSubscriber(modid = FlyBrainPlayerMod.MOD_ID)
public final class KnowledgePersistence {

    private static final String FILE = "flybrain_knowledge.dat";

    private KnowledgePersistence() {}

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        var file = event.getServer().getWorldPath(LevelResource.ROOT).resolve(FILE);
        try {
            if (java.nio.file.Files.exists(file)) {
                var tag = net.minecraft.nbt.NbtIo.readCompressed(file,
                        net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                KnowledgeBase.shared().load(tag);
            }
            // 无条件打日志：确认这个监听器确实被调用了
            FlyBrainPlayerMod.LOGGER.info("[Knowledge] 知识库就绪，共 {} 条（文件: {}）",
                    KnowledgeBase.shared().size(), file.toAbsolutePath());
        } catch (Exception e) {
            FlyBrainPlayerMod.LOGGER.warn("[Knowledge] 加载失败，从空库开始: {}", e.toString());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        int n = KnowledgeBase.shared().size();
        var file = event.getServer().getWorldPath(LevelResource.ROOT).resolve(FILE);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            net.minecraft.nbt.NbtIo.writeCompressed(KnowledgeBase.shared().save(), file);
            FlyBrainPlayerMod.LOGGER.info("[Knowledge] 已保存 {} 条到 {}",
                    n, file.toAbsolutePath());
        } catch (Exception e) {
            FlyBrainPlayerMod.LOGGER.warn("[Knowledge] 保存失败: {}", e.toString());
        }
    }
}