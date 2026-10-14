package com.flybrain.block;

import com.flybrain.FlyBrainPlayerMod;
import com.flybrain.brain.BrainPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 南瓜头扫描仪（雕刻南瓜方块）。
 * 放在果蝇脑方块（红石块）上方 1 格，右键 → 扫描 → 消耗两方块生成 AI 玩家。
 * 如果下方没有果蝇脑 → 提示玩家怎么组装。
 */
public class CarvedPumpkinHeadBlock extends Block {
    public CarvedPumpkinHeadBlock(Properties props) { super(props); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl)) return InteractionResult.SUCCESS;

        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);

        if (belowState.getBlock() instanceof RedstoneBrainBlock) {
            // ---- 组装！ ----
            BrainPlayerEntity bot = FlyBrainPlayerMod.BRAIN_PLAYER.get().create(sl);
            if (bot != null) {
                bot.setPos(pos.getX() + 0.5, pos.getY() - 1.0, pos.getZ() + 0.5);
                bot.master = player;
                bot.masterId = player.getUUID();
                // 走与自然生成一致的出生规则（随机头颅 + 个体属性 + 家园）
                bot.initSpawnTraits();
                sl.addFreshEntity(bot);

                // 消耗组件
                level.removeBlock(pos, false);
                level.removeBlock(below, false);

                // 特效
                sl.sendParticles(ParticleTypes.ENCHANT,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30, 0.5, 0.5, 0.5, 0.5);
                sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.05);
                sl.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.NEUTRAL, 0.3f, 2.0f);
                sl.playSound(null, pos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 1.0f, 0.6f);

                player.sendSystemMessage(Component.literal(
                        "§a[果蝇脑] §f扫描完成！AI 玩家已组装。"));
                player.sendSystemMessage(Component.literal(
                        "§7已自动认主。左键它可看大脑状态（暂未实现），伤害它有负奖励。"));
            }
        } else {
            player.sendSystemMessage(Component.literal(
                    "§c[果蝇脑] §f南瓜头下方 1 格需要先放一个 §4果蝇脑方块（红石块）§f！"));
            player.sendSystemMessage(Component.literal(
                    "§7配方：红石块(大脑) + 雕刻南瓜(扫描仪) → AI 玩家"));
        }
        return InteractionResult.SUCCESS;
    }
}
