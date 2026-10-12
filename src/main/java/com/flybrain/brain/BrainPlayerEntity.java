package com.flybrain.brain;

import com.flybrain.FlyBrainPlayerMod;
import com.flybrain.profile.SkullKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * AI 玩家：外观是人形（玩家皮肤风格贴图），大脑是一只果蝇。
 * 全部行为由 FlyBrain 网络实时驱动，没有硬编码的 if-else 行为树。
 * 多巴胺奖励让它越玩越"聪明"（权重被 NBT 持久化）。
 */
public class BrainPlayerEntity extends PathfinderMob {
    public final FlyBrain brain = new FlyBrain(System.currentTimeMillis());

    /** 感知缓存 */
    public BlockPos nearestFoodPos;
    public Monster nearestThreat;
    public float lastHurtMemory;
    public float dopamine;
    public LivingEntity master;          // 主人（设置者或最近玩家）
    public java.util.UUID masterId;

    // ===== 自主学习状态 =====
    /** 无聊度（无事可做时累积，驱动自发学习） */
    public float boredom;
    /** 好奇心水平：想查还没查的词条数，0~1 */
    public float curiosity;
    /** 最近学到的一条可执行事实，形如 "diamond:y=-59..-16" */
    public String lastFact = "";
    /** 还没学、但想去查的词条队列 */
    public final java.util.LinkedHashSet<String> pendingTopics = new java.util.LinkedHashSet<>();
    /** 当前正在研究的词条 */
    public String researchTopic;
    /** 研究计时（毫秒，游戏内时间），防止卡住 */
    public int researchTicks;

    /** 决策节流：果蝇反应延迟约 20ms → 游戏内每 4 tick 决策一次 */
    private int decisionCooldown;
    private int lastAction = FlyBrain.OUT_WANDER;
    private float[] lastActionVec;
    private int pendingRewardCooldown;
    private int sleepTimer;

    public BrainPlayerEntity(EntityType<? extends BrainPlayerEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(true);
        // 默认套用"玩家头颅"的性格调制，让出厂状态就是调过的
        brain.applyProfile(SkullKind.PLAYER);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.STEP_HEIGHT, 0.6);
    }

    public LivingEntity getMaster() {
        if (master != null && !master.isRemoved() && master.isAlive()) return master;
        if (masterId != null && level() instanceof ServerLevel sl) {
            var p = sl.getServer().getPlayerList().getPlayer(masterId);
            if (p != null) { master = p; return master; }
        }
        // 没有明确主人 → 找最近玩家
        if (level() instanceof ServerLevel sl2) {
            var players = sl2.players();
            LivingEntity best = null; double bd = Double.MAX_VALUE;
            for (var p : players) {
                double d = p.distanceToSqr(this);
                if (d < bd) { bd = d; best = p; }
            }
            master = best;
        }
        return master;
    }

    public boolean needsFood() {
        return false; // 占位：以后可加饥饿系统
    }

    // ===== 头颅 = 技能 / 性格 / 皮肤 =====

    /**
     * 当前头颅类型。
     *
     * 必须走 synced entity data：渲染器在客户端，而头颅字段是服务端的普通字段，
     * 不同步的话客户端永远读到默认值，玩家看到的皮肤就不会随头颅变化。
     */
    public SkullKind skull = SkullKind.PLAYER;
    public static final EntityDataAccessor<String> DATA_SKULL =
            SynchedEntityData.defineId(BrainPlayerEntity.class, EntityDataSerializers.STRING);

    public SkullKind getSkull() {
        return SkullKind.byTag(entityData.get(DATA_SKULL));
    }

    /** 同步给客户端 */
    private void setSkullSynced(SkullKind kind) {
        skull = kind;
        entityData.set(DATA_SKULL, kind.tag());
    }

    /**
     * 套用头颅：改技能树、性格、皮肤。
     *
     * 关键：**重新套用前先把本能权重恢复出厂值**，
     * 否则反复换头颅会把权重一路乘下去，最后所有通路都趋近 0，
     * 表现为"AI 什么都干不了"。
     */
    public void applyProfile(SkullKind kind) {
        if (kind == null) return;
        setSkullSynced(kind);
        brain.applyProfile(kind);
        var p = kind.personality();
        boredom = Math.min(1f, p.curiosity());     // 好奇型一出生就闲不住
        setCustomName(Component.literal(kind.color() + kind.displayName()
                + "§r #" + brain.generation));
        setCustomNameVisible(true);
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.ENCHANT,
                    getX(), getY() + 1.5, getZ(), 24, 0.4, 0.5, 0.4, 0.3);
        }
    }

    /** Entity.random 是 protected 的 RandomSource，感受器需要它做 Lévy 飞行探索 */
    public net.minecraft.util.RandomSource brainRandom() {
        return this.random;
    }

    /** 好奇心强度：待查词条越多越高，归一化到 0~1 */
    public float getCuriosityLevel() {
        if (pendingTopics.isEmpty() && researchTopic == null) return 0f;
        int n = pendingTopics.size() + (researchTopic != null ? 1 : 0);
        return Math.min(1f, n / 5f);
    }

    /**
     * 说话。target 为 null 时广播给附近所有玩家。
     * 果蝇没有嗓子，这里用头顶气泡 + 聊天栏两种方式呈现。
     */
    public void speak(net.minecraft.world.entity.Entity target, Component msg) {
        if (!(level() instanceof ServerLevel sl)) return;
        /** 头顶气泡：1.21.1 的 ParticleTypes 里没有 TEXT/CHAT 粒子，
     *  用 END_ROD 代替，真正的文字靠 speak() 走聊天栏 */
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                getX(), getY() + 2.1, getZ(), 3, 0.15, 0.1, 0.15, 0.01);
        if (target instanceof net.minecraft.world.entity.player.Player p) {
            p.displayClientMessage(msg, false);
        } else {
            // 广播给附近 16 格内的玩家
            for (var p : sl.players()) {
                if (p.distanceToSqr(this) < 256) p.displayClientMessage(msg, false);
            }
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoalCustom(this));
        this.goalSelector.addGoal(1, new FlyBrainGoal(this));
    }

    /** 果蝇脑主目标：每 4 tick 感知→决策→执行 */
    static class FlyBrainGoal extends Goal {
        private final BrainPlayerEntity mob;
        private int wanderTicks;

        FlyBrainGoal(BrainPlayerEntity mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
        }

        @Override public boolean canUse() { return true; }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        private int debugTicks;

        @Override
        public void tick() {
            if (mob.decisionCooldown-- > 0) return;
            mob.decisionCooldown = 4;

            float[] pn = Sensors.sense(mob);
            mob.brain.memorize(pn);
            float[] out = mob.brain.step(pn);
            mob.lastActionVec = out;
            mob.executeAction(out);

            // 神经链路诊断。排障时把 com.flybrain 调到 DEBUG 即可看到 PN/OUT 全量数值。
            // 默认只对"非玩家头颅"的个体打 INFO——因为不同头颅的行为差异
            // 本身就是这个模组最需要能观测的东西。
            if (debugTicks++ % 40 == 0
                    && (FlyBrainPlayerMod.LOGGER.isDebugEnabled()
                        || mob.skull != SkullKind.PLAYER)) {
                int best = 0;
                for (int i = 1; i < FlyBrain.MB_COUNT; i++) if (out[i] > out[best]) best = i;
                StringBuilder pnDump = new StringBuilder();
                for (int i = 0; i < FlyBrain.PN_COUNT; i++) {
                    if (pn[i] > 0.01f) pnDump.append(i).append('=')
                            .append(String.format("%.2f", pn[i])).append(' ');
                }
                StringBuilder outDump = new StringBuilder();
                for (int i = 0; i < FlyBrain.MB_COUNT; i++) {
                    outDump.append(String.format("%.2f", out[i])).append(i == FlyBrain.MB_COUNT - 1 ? "" : " ");
                }
                FlyBrainPlayerMod.LOGGER.info(
                        "[BrainDebug][{}] act={} navDone={} onGnd={} vXZ={} | PN[{}] | OUT[{}]",
                        mob.skull.tag(), best, mob.getNavigation().isDone(), mob.onGround(),
                        mob.getDeltaMovement().horizontalDistance(),
                        pnDump, outDump);
            }
        }
    }

    /** 执行 MBON 输出：选最强动作通道 */
    void executeAction(float[] out) {
        int best = 0;
        for (int i = 1; i < FlyBrain.MB_COUNT; i++) if (out[i] > out[best]) best = i;
        lastAction = best;

        LivingEntity master = getMaster();

        switch (best) {
            case FlyBrain.OUT_FORWARD -> {
                boolean moved = false;
                if (nearestFoodPos != null) {
                    // 寻路失败会返回 false —— 不能盲信"发了 moveTo 就会走"
                    moved = getNavigation().moveTo(
                            nearestFoodPos.getX() + 0.5, nearestFoodPos.getY(),
                            nearestFoodPos.getZ() + 0.5, 1.1);
                    if (!moved) foodUnreachableTicks++;else foodUnreachableTicks = 0;
                    // 连续够不到就放弃这个食物，退回游走
                    if (foodUnreachableTicks > 20) {
                        nearestFoodPos = null;
                        foodUnreachableTicks = 0;
                    }
                }
                if (!moved) wanderForward(1.0);
            }
            case FlyBrain.OUT_TURN_L -> {
                setYRot(getYRot() - 30f);
                wanderForward(0.7);
            }
            case FlyBrain.OUT_TURN_R -> {
                setYRot(getYRot() + 30f);
                wanderForward(0.7);
            }
            case FlyBrain.OUT_BACK -> getNavigation().stop();
            case FlyBrain.OUT_JUMP -> {
                if (onGround()) jumpFromGround();
            }
            case FlyBrain.OUT_STOP -> getNavigation().stop();
            case FlyBrain.OUT_ATTACK -> {
                if (nearestThreat != null && nearestThreat.isAlive() && distanceToSqr(nearestThreat) < 9) {
                    getNavigation().moveTo(nearestThreat, 1.2);
                    if (distanceToSqr(nearestThreat) < 4) doHurtTarget(nearestThreat);
                } else if (nearestThreat != null) {
                    getNavigation().moveTo(nearestThreat, 1.2);
                }
            }
            case FlyBrain.OUT_SPRINT -> {
                if (master != null) getNavigation().moveTo(master, 1.4);
                else wanderForward(1.4);
            }
            case FlyBrain.OUT_GREET -> {
                if (master != null) {
                    getLookControl().setLookAt(master);
                    if (distanceToSqr(master) > 16) getNavigation().moveTo(master, 1.2);
                    else getNavigation().stop();
                }
            }
            case FlyBrain.OUT_DIG -> {
                tryDigNearest(master);
            }
            case FlyBrain.OUT_WANDER -> wanderForward(0.9);
            case FlyBrain.OUT_DANCE -> {
                setYRot(getYRot() + 60f);
                if (onGround()) jumpFromGround();
            }
            case FlyBrain.OUT_STUDY -> doStudy();
            case FlyBrain.OUT_TALK -> doTalk();
        }
    }

    /**
     * 自主学习：挑一个还没学过的词条，去 minecraft.wiki 查资料。
     *
     * 这是本模组"自主学习"的入口。流程：
     *   选词条 → 异步 HTTP 查 wiki → 解析成可执行事实 → 存进共享知识库
     *   → 给予多巴胺奖励（让突触权重真的朝"去查资料"倾斜）→ 行为被知识改变
     */
    private void doStudy() {
        // 已经在研究 → 等结果，别重复发起
        if (researchTopic != null) {
            researchTicks++;
            // 超时保护：60 秒还没结果就放弃，避免永久卡在研究状态
            if (researchTicks > 1200) {
                researchTopic = null;
                researchTicks = 0;
                curiosity = Math.max(0, curiosity - 0.2f);
            } else {
                getNavigation().stop();
            }
            return;
        }

        // 没有待查词条 → 从周围环境里找一个"没学过的方块"当研究目标
        // 注意：这里用局部 final 变量，lambda 里才能安全捕获
        final String topic;
        if (!pendingTopics.isEmpty()) {
            topic = pendingTopics.iterator().next();
        } else {
            topic = pickResearchTopicFromEnvironment();
        }
        if (topic == null) {
            curiosity = 0f;
            boredom = 0f;
            return;                 // 没什么好学的，回去游走
        }

        researchTopic = topic;
        researchTicks = 0;
        getNavigation().stop();
        setCustomName(Component.literal("§7" + topic + "§r#"
                + brain.generation + " §8(研究中)"));

        // 异步去查（绝不能阻塞主线程）
        com.flybrain.learn.WikiClient.get().learn(topic, entry -> {
            var srv = getServer();
            if (srv == null) return;
            srv.execute(() -> {
                if (isRemoved() || !(level() instanceof ServerLevel sl)) return;
                lastFact = entry.fact();
                pendingTopics.remove(topic);
                researchTopic = null;
                researchTicks = 0;
                setCustomName(Component.literal("果蝇脑 #" + brain.generation));
                sl.sendParticles(ParticleTypes.ENCHANT,
                        getX(), getY() + 1.5, getZ(), 20, 0.4, 0.4, 0.4, 0.2);
                speak(null, Component.literal(
                        "§a✓ 我学会了 §e" + entry.title() + "§a —— §7" + entry.fact()));
                // 学会东西 → 多巴胺奖励 → 突触权重强化 → 更爱学习
                giveDopamine(0.5f);
            });
        });
    }

    /** 从周围环境里挑一个值得查的词条（看到的非原版方块名） */
    private String pickResearchTopicFromEnvironment() {
        var kb = com.flybrain.learn.KnowledgeBase.shared();
        for (int r = 2; r <= 6; r += 2) {
            for (int a = 0; a < 8; a++) {
                double ang = Math.PI * 2 * a / 8;
                var p = blockPosition().offset(
                        (int) Math.round(Math.cos(ang) * r), 0,
                        (int) Math.round(Math.sin(ang) * r));
                var st = level().getBlockState(p);
                if (st.isAir()) continue;
                var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
                if (id.getNamespace().equals("minecraft")) continue;
                String topic = id.getPath().replace('_', ' ');
                if (!kb.has(topic) && !pendingTopics.contains(topic)) return topic;
            }
        }
        return null;
    }

    /** 交谈：靠近最近的玩家或同类 AI，打个招呼 */
    private void doTalk() {
        LivingEntity target = null;
        double bestD = 64;   // 8 格内
        for (var p : level().players()) {
            double d = p.distanceToSqr(this);
            if (d < bestD) { bestD = d; target = p; }
        }
        if (target == null) {
            for (BrainPlayerEntity other : level().getEntitiesOfClass(
                    BrainPlayerEntity.class, getBoundingBox().inflate(8), e -> e != this)) {
                double d = other.distanceToSqr(this);
                if (d < bestD) { bestD = d; target = other; }
            }
        }
        if (target == null) { wanderForward(0.8); return; }

        getLookControl().setLookAt(target);
        if (bestD > 9) {
            getNavigation().moveTo(target, 1.1);
        } else {
            getNavigation().stop();
            // 打招呼（带冷却，避免刷屏）
            if (tickCount % 200 == 0) {
                if (target instanceof BrainPlayerEntity other) {
                    com.flybrain.comm.Dialogue.aiGreet(this, other);
                } else {
                    speak(target, Component.literal("§7> §f你好，我是果蝇脑 #"
                            + brain.generation + "。有什么想让我学的吗？"));
                }
                giveDopamine(0.2f);
            }
        }
    }

    private void wanderForward(double speed) {
        // 每 20 tick 换一个随机朝向的新目标，制造果蝇式的 Lévy 飞行式游走
        if (wanderCooldown <= 0 || wanderTarget == null) {
            wanderCooldown = 20 + brainRandom().nextInt(20);
            wanderTarget = pickWalkableTarget(6 + brainRandom().nextInt(8));
        }
        wanderCooldown--;

        // 卡住超过 1.5 秒就放弃当前目标，别死磕
        if (getNavigation().isDone() || wanderStuckTicks > 30) {
            wanderStuckTicks = 0;
            if (wanderTarget != null) {
                boolean ok = getNavigation().moveTo(
                        wanderTarget.x, wanderTarget.y, wanderTarget.z, speed);
                // 寻不到路 → 立刻换一个目标，不要卡在这个死目标上
                if (!ok) wanderCooldown = 0;
            }
        } else {
            wanderStuckTicks++;
        }
    }

    /**
     * 找一个真正能站人的目标点。
     * 关键：不能只随机取偏移点——平地上随便偏移很容易落到空隙/悬空处，
     * 寻路直接失败，表现为"脑在思考但身体不动"。这里必须验证脚下有支撑方块。
     */
    private Vec3 pickWalkableTarget(int radius) {
        var origin = blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            double yaw = brainRandom().nextDouble() * Math.PI * 2;
            int r = 2 + brainRandom().nextInt(Math.max(1, radius));
            var p = origin.offset(
                    (int) Math.round(Math.sin(yaw) * r), 0,
                    (int) Math.round(Math.cos(yaw) * r));

            // 脚下必须是实心，且自己这格 + 头顶要空
            if (!level().getBlockState(p.below()).isAir()
                    && level().getBlockState(p).isAir()
                    && level().getBlockState(p.above()).isAir()) {
                return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            }
        }
        // 附近都不可走 → 原地待命（不要给一个必定失败的路径）
        return null;
    }

    private int wanderCooldown;
    private int wanderStuckTicks;
    private int foodUnreachableTicks;
    private Vec3 wanderTarget = Vec3.ZERO;

    /**
     * 挖掘行为 —— **由知识库驱动**。
     *
     * 这是"学到新东西会改变行为"最直观的体现：
     * AI 从 wiki 学到「钻石生成在 y=-59..-16」之后，挖掘目标就会优先
     * 选钻石矿；如果当前 Y 在该区间，它就会主动往下挖。
     * 什么知识都没学到时，它退回到最朴素的行为：挖挡路的方块。
     */
    private void tryDigNearest(LivingEntity master) {
        // ① 优先按知识库选目标：当前深度是否在某个矿物的生成区间
        var depths = com.flybrain.learn.KnowledgeBase.shared().miningDepths();
        int selfY = blockPosition().getY();

        for (var e : depths.entrySet()) {
            int[] range = e.getValue();
            if (selfY < range[0] || selfY > range[1]) continue;   // 不在区间内

            var ore = findBlockNear(e.getKey(), 12);
            if (ore != null) {
                digToward(ore);
                return;
            }
        }

        // ② 没有匹配的深度知识 → 向下挖（学过高度信息的人知道"地下有东西"）
        if (!depths.isEmpty() && selfY > -60) {
            var below = blockPosition().below();
            var st = level().getBlockState(below);
            if (!st.isAir() && st.getDestroySpeed(level(), below) >= 0
                    && st.getDestroySpeed(level(), below) < 5.0f) {
                digDown();
                return;
            }
        }

        // ③ 兜底：挖挡路方块（原来只有主人在才行）
        Vec3 dir = master != null
                ? master.position().subtract(position()).normalize()
                : new Vec3(0, 0, 1);
        BlockPos target = BlockPos.containing(position().add(dir.scale(1.5)));
        mine(target);
    }

    /** 朝目标方块走并挖掉它 */
    private void digToward(BlockPos ore) {
        if (ore.distSqr(blockPosition()) <= 2.25) {
            mine(ore);
        } else {
            getNavigation().moveTo(ore.getX() + 0.5, ore.getY(), ore.getZ() + 0.5, 1.3);
        }
    }

    /** 挖脚下方块（往深层探索） */
    private void digDown() {
        mine(blockPosition().below());
        if (onGround()) jumpFromGround();
    }

    /** 在半径内找某个关键词相关的方块（如 "diamond" → diamond_ore） */
    private BlockPos findBlockNear(String keyword, int radius) {
        var reg = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        String want = keyword.replace(" ", "_");
        var origin = blockPosition();
        for (var entry : reg.entrySet()) {
            // registry entry 的 key 是 ResourceKey<Block>，路径要取 location()
            String path = entry.getKey().location().getPath();
            if (!path.contains(want)) continue;
            for (int r = 1; r <= radius; r++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            var p = origin.offset(dx, dy, dz);
                            if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;
                            if (level().getBlockState(p).getBlock() == entry.getValue()) return p;
                        }
                    }
                }
            }
            return null;
        }
        return null;
    }

    /** 实际挖掉一个方块 */
    private void mine(BlockPos target) {
        if (!(level() instanceof ServerLevel sl)) return;
        var state = level().getBlockState(target);
        if (state.isAir()) return;
        float speed = state.getDestroySpeed(level(), target);
        if (speed < 0 || speed >= 5.0f) return;      // 挖不动的（黑曜石/基岩）跳过

        level().destroyBlock(target, true, this);
        giveDopamine(0.3f);
        sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                8, 0.3, 0.3, 0.3, 0.02);
        level().playSound(null, target, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4f, 1.4f);
    }

    /** 多巴胺发放：奖励上一个动作 */
    public void giveDopamine(float amount) {
        dopamine = Math.min(1f, dopamine + amount);
        pendingRewardCooldown = 2; // 下一 tick 结算
    }

    /**
     * 脱困反射：被埋在方块里时向上寻找最近空位弹出去。
     * 没有这一步，被 summon 进方块的 AI 玩家会永久卡死（已实测复现）。
     */
    private void escapeIfStuck() {
        var feet = blockPosition();
        var here = level().getBlockState(feet);
        if (here.isAir() && level().getBlockState(feet.above()).isAir()) return; // 没卡

        for (int up = 1; up <= 4; up++) {
            var p = feet.above(up);
            if (level().getBlockState(p).isAir() && level().getBlockState(p.above()).isAir()) {
                setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                getNavigation().stop();
                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.PORTAL,
                            getX(), getY() + 1, getZ(), 12, 0.3, 0.5, 0.3, 0.1);
                }
                return;
            }
        }
        // 上下都堵死 → 拆掉头顶方块
        if (level() instanceof ServerLevel sl) {
            var head = feet.above();
            var hs = level().getBlockState(head);
            if (!hs.isAir() && hs.getDestroySpeed(level(), head) >= 0
                    && hs.getDestroySpeed(level(), head) < 2.0f) {
                level().destroyBlock(head, true, this);
            }
        }
    }

    /** 刚出生时打一行日志，确认预训练生效 + 大脑独立 */
    private int birthLogged;

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (birthLogged++ == 3) {     // 出生后稍等几 tick 再打，避免在构造期刷屏
                FlyBrainPlayerMod.LOGGER.info(
                        "[FlyBrain] #{} 出生：大脑指纹 {} | 突触总量 {} | "
                                + "预训练 loss {}（耗时 {}ms）",
                        brain.generation, brain.brainFingerprint(),
                        String.format("%.2f", brain.weightMagnitude()),
                        String.format("%.4f", Pretrain.loss()),
                        Pretrain.millis());
            }
            // 多巴胺结算
            if (pendingRewardCooldown > 0 && --pendingRewardCooldown == 0) {
                brain.reward(dopamine, lastAction);
                dopamine = 0;
            }
            lastHurtMemory *= 0.98f;

            // 被卡住 → 自救（每 10 tick 检查一次就够）
            if (tickCount % 10 == 0) escapeIfStuck();

            // 每 20 秒"睡一次"（巩固记忆）
            if (++sleepTimer >= 400) {
                sleepTimer = 0;
                brain.sleep();
            }

            // 捡物品
            if (nearestFoodPos != null) {
                var items = level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.2));
                for (var item : items) {
                    ItemStack stack = item.getItem();
                    if (!stack.isEmpty()) {
                        this.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
                        item.discard();
                        giveDopamine(0.5f);
                        if (level() instanceof ServerLevel sl) {
                            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 1, getZ(), 6, 0.3, 0.3, 0.3, 0.01);
                        }
                        break;
                    }
                }
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean r = super.hurt(source, amount);
        if (r && !level().isClientSide) {
            lastHurtMemory = 1f;
            // 被玩家打 → 负奖励
            if (source.getEntity() instanceof net.minecraft.world.entity.player.Player) {
                giveDopamine(-0.5f);
            }
        }
        return r;
    }

    @Override
    public InteractionResult mobInteract(net.minecraft.world.entity.player.Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            // 潜行右键 = 对话（不改变主人），普通右键 = 认主
            if (player.isShiftKeyDown()) {
                openDialogue(player);
            } else {
                master = player;
                masterId = player.getUUID();
                player.sendSystemMessage(Component.literal(
                        "§a[果蝇脑] §f已认主！世代 §e#" + brain.generation
                        + "§f，学习率 §b" + String.format("%.3f", brain.learningRate)));
                level().playSound(null, blockPosition(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 0.6f, 1.2f);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** 打开对话：展示状态，并给出提问方式 */
    private void openDialogue(net.minecraft.world.entity.player.Player player) {
        var kb = com.flybrain.learn.KnowledgeBase.shared();
        getLookControl().setLookAt(player);

        player.sendSystemMessage(Component.literal("§6════ 果蝇脑 #"
                + brain.generation + " ════"));
        player.sendSystemMessage(Component.literal(
                "§7神经元 §f82 §7| §f突触权重已学 §e" + brain.generation + " §7代"
                + " §7| §f知识库 §e" + kb.size() + " §7条"));
        player.sendSystemMessage(Component.literal(
                "§7最近学到： §f" + (lastFact.isBlank() ? "（还没有）" : lastFact)));
        if (researchTopic != null) {
            player.sendSystemMessage(Component.literal(
                    "§8正在研究： §e" + researchTopic + " §8…"));
        }
        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal(
                "§7可以直接问我：§f「AI <词条名> 在哪挖」§7——我会上 wiki 查并记住"));
        player.sendSystemMessage(Component.literal(
                "§7或用 §f/brain ask <词条> §7查 · §f/brain list §7看知识库"));
    }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKULL, SkullKind.PLAYER.tag());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("FlyBrain", brain.save());
        if (masterId != null) tag.putUUID("MasterId", masterId);
        tag.putFloat("Boredom", boredom);
        tag.putFloat("Curiosity", curiosity);
        tag.putString("LastFact", lastFact);
        tag.putString("Skull", skull.tag());
        net.minecraft.nbt.ListTag topics = new net.minecraft.nbt.ListTag();
        for (String t : pendingTopics) topics.add(net.minecraft.nbt.StringTag.valueOf(t));
        tag.put("PendingTopics", topics);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("FlyBrain")) brain.load(tag.getCompound("FlyBrain"));
        if (tag.hasUUID("MasterId")) masterId = tag.getUUID("MasterId");
        boredom = tag.getFloat("Boredom");
        curiosity = tag.getFloat("Curiosity");
        lastFact = tag.getString("LastFact");
        // 恢复头颅：注意不要用 applyProfile，否则会把已学到的突触权重重置掉。
        // 这里只恢复同步数据标记，性格调制在 readAdditionalSaveData 之后按需重放。
        String skullTag = tag.getString("Skull");
        if (!skullTag.isEmpty()) {
            setSkullSynced(SkullKind.byTag(skullTag));
        }
        pendingTopics.clear();
        net.minecraft.nbt.ListTag topics = tag.getList("PendingTopics",
                net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < topics.size(); i++) pendingTopics.add(topics.getString(i));
    }

    /** 自定义浮水目标，避免 goal 冲突 */
    static class FloatGoalCustom extends Goal {
        private final BrainPlayerEntity mob;
        FloatGoalCustom(BrainPlayerEntity mob) { this.mob = mob; setFlags(EnumSet.of(Goal.Flag.JUMP)); }
        @Override public boolean canUse() { return mob.isInWater(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void tick() { mob.jumpFromGround(); }
    }
}
