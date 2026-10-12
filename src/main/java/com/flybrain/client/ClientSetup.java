package com.flybrain.client;

import com.flybrain.FlyBrainPlayerMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端注册：AI 玩家渲染器。
 * 注意：使用 net.neoforged.fml.common.EventBusSubscriber（21.1.219 存在于 fml 包）。
 */
@EventBusSubscriber(modid = FlyBrainPlayerMod.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FlyBrainPlayerMod.BRAIN_PLAYER.get(), BrainPlayerRenderer::new);
    }
}
