package com.flybrain.block;

import com.flybrain.FlyBrainPlayerMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 果蝇大脑方块（红石块）。
 * 右键显示连接组状态；被南瓜头扫描仪扫描时作为"大脑"组件。
 */
public class RedstoneBrainBlock extends Block {
    public RedstoneBrainBlock(Properties props) { super(props); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            player.sendSystemMessage(Component.literal(
                    "§c[果蝇脑] §f neurons: 14,000 | synapses: 5×10^7 | simulated: 82/50/12"));
            player.sendSystemMessage(Component.literal(
                    "§7在它 §e上方 1 格 §7放一个雕刻南瓜（扫描仪），右键南瓜生成 AI 玩家。"));
        }
        return InteractionResult.SUCCESS;
    }
}
