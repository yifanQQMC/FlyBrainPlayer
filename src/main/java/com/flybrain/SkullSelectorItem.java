package com.flybrain;

import com.flybrain.block.ProfileSkullBlock;
import com.flybrain.profile.SkullKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 头颅选择器：右键把一个"头颅方块"循环切换到下一种类型。
 *
 * 这样创造模式只需要一个物品就能凑齐全部 8 种头颅，
 * 不用为每种头颅单独做一个物品和模型。
 */
public class SkullSelectorItem extends Item {

    public SkullSelectorItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 潜行 + 空手对空气点击 → 列出全部头颅供选择
        BlockPos pos = player.blockPosition().relative(player.getDirection());

        // 先看手上是不是头颅方块物品
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi
                && bi.getBlock() instanceof ProfileSkullBlock) {
            if (!level.isClientSide) {
                BlockState state = bi.getBlock().defaultBlockState();
                int next = (state.getValue(ProfileSkullBlock.TYPE) + 1)
                        % SkullKind.values().length;
                SkullKind kind = SkullKind.values()[next];

                // 在面前放一个对应类型的头颅方块
                BlockPos target = findFreeSpot(level, pos);
                if (target != null) {
                    level.setBlock(target,
                            state.setValue(ProfileSkullBlock.TYPE, next), 3);
                    player.sendSystemMessage(Component.literal(
                            "§a放置 §f" + kind.color() + kind.displayName()
                            + " §8(" + kind.tag() + ")"));
                    level.playSound(null, target,
                            net.minecraft.sounds.SoundEvents.STONE_PLACE,
                            net.minecraft.sounds.SoundSource.NEUTRAL, 0.5f, 1.4f);
                }
                if (!player.getAbilities().instabuild) stack.shrink(1);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // 没拿头颅物品 → 直接展示清单
        if (!level.isClientSide) {
            player.sendSystemMessage(Component.literal("§6══ 可用头颅清单 ══"));
            for (SkullKind k : SkullKind.values()) {
                StringBuilder sb = new StringBuilder("§8  [").append(k.tag()).append("] ");
                sb.append(k.color()).append(k.displayName());
                sb.append(" §8— ").append(k.description());
                player.sendSystemMessage(Component.literal(sb.toString()));
            }
            player.sendSystemMessage(Component.literal(
                    "§7拿 §f头颅选择器 §7右键地面 → 放下一个头颅（每放一次换下一种）"));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private BlockPos findFreeSpot(Level level, BlockPos around) {
        for (int dy = 0; dy >= -3; dy--) {
            var p = around.above(dy);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolidRender(level, p.below())) {
                return p;
            }
        }
        return null;
    }
}