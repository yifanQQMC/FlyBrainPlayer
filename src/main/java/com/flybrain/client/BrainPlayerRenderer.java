package com.flybrain.client;

import com.flybrain.FlyBrainPlayerMod;
import com.flybrain.brain.BrainPlayerEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * AI 玩家渲染器。
 *
 * 用原版玩家模型（PLAYER layer），贴图按头颅类型切换 ——
 * 换头颅 = 换皮��，这是"头颅决定外观"最直接的体现。
 */
public class BrainPlayerRenderer extends MobRenderer<BrainPlayerEntity, PlayerModel<BrainPlayerEntity>> {

    public BrainPlayerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(BrainPlayerEntity entity) {
        // 头颅类型通过 synced data 传到客户端，这里直接读
        var kind = entity.getSkull();
        return ResourceLocation.parse(kind.texturePath());
    }
}