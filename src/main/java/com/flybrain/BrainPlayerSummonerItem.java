package com.flybrain;

import com.flybrain.brain.BrainPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * AI 玩家召唤器：右键空中直接生成一只果蝇脑玩家。
 * （主要是方便测试/创造模式；生存玩法推荐红石块+南瓜头扫描仪组装）
 */
public class BrainPlayerSummonerItem extends Item {
    public BrainPlayerSummonerItem(Properties props) { super(props); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && level instanceof ServerLevel sl) {
            BrainPlayerEntity bot = FlyBrainPlayerMod.BRAIN_PLAYER.get().create(sl);
            if (bot != null) {
                bot.setPos(player.getX(), player.getY(), player.getZ());
                bot.setYRot(player.getYRot());
                bot.master = player;
                bot.masterId = player.getUUID();
                bot.setCustomName(Component.literal("果蝇脑 #" + (bot.brain.generation)));
                bot.setCustomNameVisible(true);
                sl.addFreshEntity(bot);
                player.sendSystemMessage(Component.literal(
                        "§a[果蝇脑] §fAI 玩家已生成！右键它进行认主。"));
                sl.playSound(null, player.blockPosition(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 0.8f, 0.8f);
                if (!player.getAbilities().instabuild) stack.shrink(1);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
