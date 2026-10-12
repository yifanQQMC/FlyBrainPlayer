package com.flybrain.block;

import com.flybrain.brain.BrainPlayerEntity;
import com.flybrain.profile.SkullKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 头颅方块 —— AI 玩家的"物种/职业"载体。
 *
 * 三种用法：
 *  ① 放 <b>在果蝇脑上方</b>、南瓜头扫描仪之上 → 组装出该头颅类型的 AI
 *     组装顺序（自下而上）：果蝇脑 → 头颅 → 扫描仪
 *  ② 放在任意 <b>AI 玩家头顶</b> → 给它换头颅（改技能/性格/皮肤）
 *  ③ 右键 → 查看该头颅的技能与性格说明
 *
 * 头颅类型对应原版头颅（僵尸/骷髅/苦力怕/末影人/猪灵/凋灵/史莱姆/玩家），
 * 共用一个方块 id + 一个 TYPE 属性区分，注册时批量生成物品与模型。
 */
public class ProfileSkullBlock extends Block {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    /** 当前头颅类型索引（对应 SkullKind 顺序） */
    public static final IntegerProperty TYPE = IntegerProperty.create("type", 0, SkullKind.values().length - 1);

    private static final VoxelShape SHAPE = net.minecraft.world.level.block.Block.box(4, 0, 4, 12, 8, 12);

    public ProfileSkullBlock(Properties props) {
        super(props);
        registerDefaultState(getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(TYPE, 0)
                .setValue(ROTATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, ROTATION, TYPE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    /** 头颅不能被推动（轻质装饰）。1.21.1 里 canBeReplaced 已改签名，
     *  用 noOcclusion + 非 solid 材质表达"轻"，避免签��踩坑。 */

    public SkullKind kindOf(BlockState state) {
        return SkullKind.values()[state.getValue(TYPE)];
    }

    // ================= 交互 =================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        SkullKind kind = kindOf(state);
        showInfo(player, kind);

        // ① 头顶是 AI 玩家 → 换头颅
        BrainPlayerEntity bot = findBotAbove(level, pos);
        if (bot != null) {
            bot.applyProfile(kind);
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.7f, 1.3f);
            return InteractionResult.SUCCESS;
        }

        // ② 组装模式：下面是果蝇脑，再上面是南瓜头扫描仪
        if (isAssemblyStack(level, pos)) {
            assemble(level, pos, kind);
        } else {
            player.sendSystemMessage(Component.literal(
                    "§7组装顺序（自下而上）：§f果蝇脑 → §e" + kind.displayName()
                    + " §f→ 南瓜头扫描仪"));
        }
        return InteractionResult.SUCCESS;
    }

    private void showInfo(Player player, SkullKind kind) {
        var p = kind.personality();
        player.sendSystemMessage(Component.literal("§6══ " + kind.color()
                + kind.displayName() + " ══"));
        player.sendSystemMessage(Component.literal("§7" + kind.description()));
        StringBuilder sb = new StringBuilder("§b技能: ");
        for (int i = 0; i < kind.skills().size(); i++) {
            if (i > 0) sb.append("§8, ");
            var s = kind.skills().get(i);
            sb.append(s.icon()).append(' ').append("§f").append(s.label());
        }
        player.sendSystemMessage(Component.literal(sb.toString()));
        player.sendSystemMessage(Component.literal(String.format(
                "§7性格: §f学习率 %.3f §8| §f好奇 %.2f §8| §f攻击 %.2f §8| §f社交 %.2f",
                p.learningRate(), p.curiosity(), p.aggression(), p.sociability())));
    }

    /** 找站在这个头颅正上方的 AI */
    private BrainPlayerEntity findBotAbove(Level level, BlockPos pos) {
        var above = pos.above();
        var list = level.getEntitiesOfClass(BrainPlayerEntity.class,
                new net.minecraft.world.phys.AABB(above).inflate(0.6),
                e -> e.isAlive());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 下面是果蝇脑 + 上面是南瓜头扫描仪 = 合法组装栈 */
    private boolean isAssemblyStack(Level level, BlockPos pos) {
        var below = level.getBlockState(pos.below()).getBlock();
        var above = level.getBlockState(pos.above()).getBlock();
        return below instanceof RedstoneBrainBlock
                && above instanceof CarvedPumpkinHeadBlock;
    }

    private void assemble(Level level, BlockPos pos, SkullKind kind) {
        if (!(level instanceof ServerLevel sl)) return;

        var bot = com.flybrain.FlyBrainPlayerMod.BRAIN_PLAYER.get().create(sl);
        if (bot == null) return;

        bot.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        bot.applyProfile(kind);
        bot.setCustomName(Component.literal(kind.color() + kind.displayName()
                + "§r #" + bot.brain.generation));
        bot.setCustomNameVisible(true);
        sl.addFreshEntity(bot);

        // 消耗组件
        level.removeBlock(pos, false);
        level.removeBlock(pos.above(), false);
        level.removeBlock(pos.below(), false);

        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 40, 0.4, 0.6, 0.4, 0.5);
        sl.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.7f, 1.1f);
        // ServerLevel 没有 sendSystemMessage，广播给附近玩家
        var msg = Component.literal("§a[果蝇脑] §f组装完成：" + kind.color()
                + kind.displayName() + " §7（" + kind.description() + "）");
        for (var p : sl.players()) {
            if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) < 256) {
                p.displayClientMessage(msg, false);
            }
        }
    }
}