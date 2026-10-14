package com.flybrain.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * 感受器阵列：把 Minecraft 世界状态编码为果蝇脑的 20 通道 PN 输入。
 * 类比果蝇的嗅觉ORN→PN、视觉LPTC、机械感觉bristle等外周神经系统。
 */
public final class Sensors {

    public static float[] sense(BrainPlayerEntity self) {
        float[] pn = new float[FlyBrain.PN_COUNT];
        Level level = self.level();
        Vec3 pos = self.position();

        // CH_HUNGER：饥饿度（0=饱，1=饿）
        pn[FlyBrain.CH_HUNGER] = self.needsFood() ? 1f : 0f;

        // CH_FOOD：8格内最近掉落物方位与存在感
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                new AABB(pos, pos).inflate(8), e -> !e.hasPickUpDelay() || true);
        items.sort(Comparator.comparingDouble(e -> e.distanceToSqr(self)));
        if (!items.isEmpty()) {
            pn[FlyBrain.CH_FOOD] = 1f;
            self.nearestFoodPos = items.get(0).blockPosition();
        } else {
            pn[FlyBrain.CH_FOOD] = 0f;
            self.nearestFoodPos = null;
        }

        // CH_THREAT：6格内怪物
        List<Monster> threats = level.getEntitiesOfClass(Monster.class, new AABB(pos, pos).inflate(6));
        pn[FlyBrain.CH_THREAT] = threats.isEmpty() ? 0f : Mth.clamp(1.5f - threats.get(0).distanceTo(self) / 6f, 0f, 1f);
        self.nearestThreat = threats.isEmpty() ? null : threats.get(0);

        // CH_MASTER：主人
        LivingEntity master = self.getMaster();
        if (master != null && !master.isRemoved()) {
            double d = master.distanceTo(self);
            pn[FlyBrain.CH_MASTER] = (float) Mth.clamp(2.0 - d / 16.0, 0, 1);
        }

        // CH_LIGHT / CH_NIGHT：昼夜
        float dayFactor = level.getDayTime() % 24000f / 24000f;
        boolean night = dayFactor > 0.5f;
        pn[FlyBrain.CH_LIGHT] = night ? 0f : 1f;
        pn[FlyBrain.CH_NIGHT] = night ? 1f : 0f;

        // CH_OBST_L / CH_OBST_R / CH_GAP：前方地形感知（触觉+视觉）
        float yawRad = self.getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 left = new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad));
        Vec3 right = left.scale(-1);

        pn[FlyBrain.CH_OBST_L] = probeBlock(level, pos, forward.add(left.scale(0.8)).normalize());
        pn[FlyBrain.CH_OBST_R] = probeBlock(level, pos, forward.add(right.scale(0.8)).normalize());
        pn[FlyBrain.CH_GAP] = probeGap(level, pos, forward);

        // CH_ITEM：手上是否有东西
        ItemStack main = self.getMainHandItem();
        pn[FlyBrain.CH_ITEM] = main.isEmpty() ? 0f : 1f;

        // CH_PAIN：最近受伤记忆衰减
        pn[FlyBrain.CH_PAIN] = self.lastHurtMemory;

        // CH_REWARD：多巴胺瞬时信号
        pn[FlyBrain.CH_REWARD] = self.dopamine;

        // CH_WATER：脚下/前方是否有水或岩浆
        BlockPos feet = self.blockPosition();
        pn[FlyBrain.CH_WATER] = isLiquid(level, feet.relative(self.getDirection(), 2)) ? 1f : 0f;

        // CH_FALLING
        pn[FlyBrain.CH_FALLING] = self.fallDistance > 2 ? 1f : 0f;

        // CH_NOISE：随机探索噪声（果蝇随机游走策略 Lévy flight 的简化）
        // 给一个基础本底值（0.05）而不是纯 0/1：保证无输入时 MBON 仍有微弱驱动力，
        // 不会因为全零而让决策退化成随机挑通道。
        pn[FlyBrain.CH_NOISE] = 0.05f + (self.brainRandom().nextFloat() < 0.15f ? 0.5f : 0f);

        // CH_FOLLOW：主人距离 > 10 时想跟随
        if (master != null && !master.isRemoved()) {
            pn[FlyBrain.CH_FOLLOW] = master.distanceTo(self) > 10 ? 1f : 0f;
        }

        // CH_SOCIAL：附近同类数量
        //
        // 性能关键：getEntitiesOfClass 是全区域实体扫描。
        // 每只 AI 每次感知都扫一次 → N 只 AI 就是 O(N²)，
        // 1000 只时实测这是最大瓶颈（比大脑前向传播贵得多）。
        //
        // 解法：**每 20 tick 才刷新一次**，中间用缓存值。
        // 20 tick = 1 秒，对"附近有多少同类"这个慢变量完全够用。
        if (self.socialScanCooldown-- <= 0) {
            List<BrainPlayerEntity> kin = level.getEntitiesOfClass(
                    BrainPlayerEntity.class, new AABB(pos, pos).inflate(10));
            self.cachedKinCount = Mth.clamp((kin.size() - 1) / 3f, 0, 1);
            self.socialScanCooldown = 20;
        }
        pn[FlyBrain.CH_SOCIAL] = self.cachedKinCount;

        // CH_HEIGHT：实际离地高度（数到第一个固体为止，不是数空气格数）
        int airBelow = 0;
        for (int i = 1; i <= 4; i++) {
            if (level.getBlockState(feet.below(i)).isAir()) airBelow++;
            else break;// 碰到地面就停
        }
        pn[FlyBrain.CH_HEIGHT] = airBelow / 4f;

        // CH_SPEED
        pn[FlyBrain.CH_SPEED] = Mth.clamp((float) (self.getDeltaMovement().horizontalDistance() / 0.2), 0, 1);

        // CH_TOUCH：真正被埋住才算 1（头和脚所在格都是实体方块）
        // 之前把 horizontalCollision 也算进来，导致轻微贴墙就触发"被困反射"，
        // MBON 一直选跳跃/停止，表现就是"脑在思考但身体不动"。这里收紧为严格判定。
        boolean headBlocked = !level.getBlockState(feet.above()).isAir();
        boolean feetBlocked = !level.getBlockState(feet).isAir();
        pn[FlyBrain.CH_TOUCH] = (headBlocked && feetBlocked) ? 1f : 0f;

        // ===== 好奇心通道：自主学习的驱动力 =====
        // CH_CURIOSITY：看到"没见过的方块"——扫描周围，记录没在知识库里见过的类型
        int unfamiliar = countUnfamiliarBlocks(level, pos);
        pn[FlyBrain.CH_CURIOSITY] = Mth.clamp(unfamiliar / 4f, 0, 1);

        // CH_NOVELTY：自己还有多少"想查但没查"的词条
        pn[FlyBrain.CH_NOVELTY] = self.getCuriosityLevel();

        // CH_SOCIAL_TALK：附近有可交谈的对象（同类 AI 或玩家）
        //
        // 同上：这里也有一次 getEntitiesOfClass，是第二个 O(N²) 来源。
        // 玩家数量通常很少，先查玩家（便宜）；只有在没玩家时才扫同类，
        // 且同样做 20 tick 节流。
        int talkables = 0;
        for (var p : level.players()) {
            if (p.distanceToSqr(self) < 64) { talkables++; break; }
        }
        if (talkables == 0) {
            if (self.socialScanCooldown <= 0) {
                int near = 0;
                for (BrainPlayerEntity other : level.getEntitiesOfClass(
                        BrainPlayerEntity.class, new AABB(pos, pos).inflate(8),
                        e -> e != self)) {
                    near++;
                    if (near >= 2) break;
                }
                self.cachedTalkables = near;
            }
            talkables = self.cachedTalkables;
        }
        pn[FlyBrain.CH_SOCIAL_TALK] = Mth.clamp(talkables / 2f, 0, 1);

        // CH_BOREDOM：无聊 = 没事干（没食物/没威胁/主人不在）时缓慢累积
        float busy = pn[FlyBrain.CH_FOOD] + pn[FlyBrain.CH_THREAT]
                + pn[FlyBrain.CH_MASTER] + pn[FlyBrain.CH_GAP];
        self.boredom = Mth.clamp(self.boredom + (busy < 0.2f ? 0.01f : -0.05f), 0, 1);
        pn[FlyBrain.CH_BOREDOM] = self.boredom;

        // ===== 自然环境交互通道 =====

        // CH_RAIN：露天淋雨。只有"头顶见得到天 + 正在下雨"才算，
        // 站在树下/屋檐下不算淋雨 —— 让 AI 真的会去找遮蔽而不是无意义发抖。
        float rainLevel = level.getRainLevel(1.0f);
        boolean raining = rainLevel > 0.05f;
        boolean exposedToSky = level.canSeeSky(feet);
        pn[FlyBrain.CH_RAIN] = (raining && exposedToSky) ? 1f : 0f;

        // CH_THUNDER：雷暴（闪电威胁）
        pn[FlyBrain.CH_THUNDER] = level.isThundering() ? 1f : 0f;

        // CH_TIRED：疲劳度 = 1 - 能量。能量由实体维护，休息时回升。
        pn[FlyBrain.CH_TIRED] = 1f - Mth.clamp(self.energy, 0f, 1f);

        // CH_HOME：离家距离（归一化）。没家 → 0（不归巢）。
        if (self.hasHome && self.home != null) {
            double d = Math.sqrt(self.home.distSqr(self.blockPosition()));
            pn[FlyBrain.CH_HOME] = Mth.clamp((float) (d / 48.0), 0f, 1f);
        } else {
            pn[FlyBrain.CH_HOME] = 0f;
        }

        return pn;
    }

    /**
     * 统计周围"知识库里没记录过"的方块种类数。
     * 果蝇遇到没见过的刺激会产生定向反应；这里让 AI 见到新方块就想查。
     */
    private static int countUnfamiliarBlocks(Level level, Vec3 pos) {
        var kb = com.flybrain.learn.KnowledgeBase.shared();
        int novel = 0;
        BlockPos origin = BlockPos.containing(pos);
        for (int dx = -3; dx <= 3 && novel < 8; dx += 3) {
            for (int dz = -3; dz <= 3 && novel < 8; dz += 3) {
                for (int dy = -2; dy <= 2 && novel < 8; dy += 2) {
                    var st = level.getBlockState(origin.offset(dx, dy, dz));
                    if (st.isAir()) continue;
                    // 原版方块不算"新奇"（AI 有生存常识），只统计非原版的
                    var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    if (id.getNamespace().equals("minecraft")) continue;
                    if (!kb.has(id.getPath().replace('_', ' '))) novel++;
                }
            }
        }
        return novel;
    }

    /** 前方障碍探测：1 格内权重高，2 格递减。流体（空气/水）不算障碍。 */
    private static float probeBlock(Level level, Vec3 pos, Vec3 dir) {
        for (int d = 1; d <= 2; d++) {
            BlockPos p = BlockPos.containing(pos.add(dir.scale(d)));
            var state = level.getBlockState(p);
            // 站在实体上（脚下的地面）不算障碍，只看身体高度那一层
            if (!state.isAir() && level.getFluidState(p).isEmpty()) return 1f / d;
        }
        return 0f;
    }

    /**
     * 前方深坑探测。
     *
     * 两个必须避开的坑：
     * 1. 不能用 `前方格.below()`——那是"前方的下方"，平地上几乎恒为空气，CH_GAP 会永远=1。
     * 2. 不能假定自己正好站在地面那一层——实体可能悬空 1~2 格（刚落地/被传送）。
     *    所以要先向下找到**自己真实的落地面 y**，再拿它和前方做对比。
     */
    private static float probeGap(Level level, Vec3 pos, Vec3 dir) {
        // ① 先确定自己的真实地面高度
        int myFeetY = BlockPos.containing(pos).getY();
        int myGroundY = myFeetY;
        for (int down = 0; down <= 3; down++) {
            if (!level.getBlockState(new BlockPos(BlockPos.containing(pos).getX(),
                    myFeetY - down, BlockPos.containing(pos).getZ())).isAir()) {
                myGroundY = myFeetY - down;      // 第一个实体方块的顶面
                break;
            }
        }

        // ② 找前方 2 格内的落地面
        int aheadCount = 0;
        for (int d = 1; d <= 2; d++) {
            BlockPos ahead = BlockPos.containing(pos.add(dir.scale(d)));
            int aheadGroundY = Integer.MIN_VALUE;
            for (int drop = 0; drop <= 5; drop++) {
                var probe = new BlockPos(ahead.getX(), myGroundY - drop, ahead.getZ());
                if (!level.getBlockState(probe).isAir()) {
                    aheadGroundY = myGroundY - drop;
                    break;
                }
            }
            if (aheadGroundY == Integer.MIN_VALUE) {
                // 前方整列都是空气 → 悬空，危险
                return 1f;
            }
            int fall = myGroundY - aheadGroundY;      // 正数=要下坡，负数=要爬坡
            if (fall >= 2) return Math.min(1f, (fall - 1) / 3f);   // ≥2 格落差算坑
            if (fall <= -2) return 0.3f;                           // 2 格以上台阶也该注意
            aheadCount++;
        }
        return 0f;
    }

    private static boolean isLiquid(Level level, BlockPos pos) {
        return !level.getFluidState(pos).isEmpty();
    }
}
