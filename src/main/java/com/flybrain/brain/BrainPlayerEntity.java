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
    /** 同类扫描节流：getEntitiesOfClass 是 O(N²) 的，必须缓存 */
    public int socialScanCooldown = 0;
    public float cachedKinCount = 0f;
    public int cachedTalkables = 0;
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

    /**
     * 随机英文名（ALEX / STEVE / NICK ...），全服不重名。
     * 玩家在聊天框喊这个名字就能下指令。
     */
    public String name = "";

    /** 玩家指令的剩余执行 tick —— >0 表示正在执行指令 */
    public int commandTicksLeft = 0;
    /** 当前指令类型（对应 BotCommand.Action） */
    public String currentCommand = "";
    /** 指令目标坐标（探索类指令用） */
    public net.minecraft.core.BlockPos commandTarget = null;

    // ===== 个体属性（自然生成的多样性来源）=====
    /**
     * 个体特性种子：决定性格/属性的随机扰动，使同一头颅的 AI 也互不雷同。
     * 类比真实种群里的个体差异——没有两只完全一样的"末影人玩家"。
     */
    public long traitSeed;
    /** 个体的解析性格（头颅基准 + 个体扰动），覆盖默认头颅性格 */
    public com.flybrain.profile.SkullKind.Personality persona;
    /** 体力/能量：行动缓慢消耗、休息恢复，驱动自然作息（不累不歇、累了就歇） */
    public float energy = 1f;
    /** 家园坐标：出生时记下，累了/下雨会想回去，形成"领地感" */
    public net.minecraft.core.BlockPos home;
    /** 是否有家园（读档/召唤都可能没有） */
    public boolean hasHome = false;
    /** 当前 tick 是否处于休息态（tick 里据此恢复能量） */
    private boolean restingNow = false;
    /**
     * 补眠目标：能量跌破触发线后，进入"强制补眠"并一直歇到 energy>=restTarget。
     * 用滞后控制（hysteresis）避免 AI 在阈值附近反复横跳、永远卡在 0.05~0.2
     * 被"休息中"统计误伤——要么真补满再活动，要么不补。0 表示不在补眠期。
     */
    private float restTarget = 0f;

    // ===== 个体知识账本（文明发展的最小单位）=====
    /** 自己亲手学过的词条 */
    public final java.util.LinkedHashSet<String> knownTopics = new java.util.LinkedHashSet<>();
    /** 向全局知识库贡献过几条（首次学到） */
    public int contributions = 0;
    /** 教过别的 AI 几次 */
    public int teachings = 0;
    /** 从别的 AI 那学到几次 */
    public int learned = 0;
    /** 传授冷却：避免每 tick 都触发 */
    public int teachCooldown = 0;

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

            // —— 知识传授：文明传播的核心机制 ——
            // 两只 AI 靠近时，知识多的一方把词条教给少的一方。
            // 这模拟了原始社会的口耳相传：知识不再是"每人自己查 wiki"，
            // 而是可以在种群里扩散，学者型 AI 成为知识节点。
            if (mob.teachCooldown-- <= 0) {
                mob.tryTeachNearby();
                mob.teachCooldown = 60;      // 3 秒一次，别刷屏
            }

            // 神经链路诊断。默认只在日志级别调到 DEBUG 时才输出。
            //
            // 曾经默认对"非玩家头颅"打 INFO，结果大规模部署时
            // 1000 只 AI 每 40 tick 打一条 = 每 2 秒 25 条日志，
            // 纯 I/O 浪费，实测拖慢 tick。现在统一由日志级别控制，
            // 需要观测时把 com.flybrain 调到 DEBUG 即可。
            if (debugTicks++ % 40 == 0 && FlyBrainPlayerMod.LOGGER.isDebugEnabled()) {
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
        // —— 玩家指令优先于大脑决策 ——
        // 被玩家点名下指令时，AI 暂时"听话"，指令做完再回到自主行为。
        // 这是有意的设计：玩家交互应该有即时反馈，
        // 而不是"我说了我的，它想它的"。
        if (commandTicksLeft > 0) {
            commandTicksLeft--;
            if (runCommand()) return;      // 指令执行成功，这一 tick 就不走大脑
            commandTicksLeft = 0;          // 执行失败（如目标没了）立即放弃
        }

        int best = 0;
        for (int i = 1; i < FlyBrain.MB_COUNT; i++) if (out[i] > out[best]) best = i;
        lastAction = best;

        // 能量见底 → 进入"强制补眠"并一直歇到恢复（滞后控制，防止假死）：
        // 若只做"energy<0.05 就歇一下"，AI 会补到 0.07 又去行动、再掉回阈值，
        // 永远在 0.05~0.2 之间反复横跳、被"休息中"统计误伤成死气沉沉。
        // 改为：跌破触发线就把 restTarget 设到高位，强制持续休息直到真正补满，
        // 再回到正常活动——这样精力曲线是"活动很久→补一觉→再活动"，而非长期瘫痪。
        if (energy < 0.12f) restTarget = 0.85f;
        if (restTarget > 0f && energy >= restTarget) restTarget = 0f;
        if (restTarget > 0f) best = FlyBrain.OUT_REST;

        // 能量管理：休息/无操作/补眠不耗，其余动作缓慢消耗。
        // 消耗刻意调得很慢（满→空约 1~2 分钟），让 AI 大部分时间在活动，
        // 只有偶尔歇一会儿——否则会"全体同时休息"显得死气沉沉。
        if (best == FlyBrain.OUT_REST || best == FlyBrain.OUT_STOP
                || best == FlyBrain.OUT_GREET) {
            restingNow = (best == FlyBrain.OUT_REST);
        } else {
            restingNow = false;
            energy = Math.max(0f, energy - 0.0006f);
        }

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
            case FlyBrain.OUT_REST -> doRest();
            case FlyBrain.OUT_SHELTER -> doShelter(master);
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
        refreshNameTag();      // 别用旧格式覆盖英文名（曾把 ALEX 冲掉）
        // 头顶临时标注"研究中"，让玩家看得出它在干活
        setCustomName(net.minecraft.network.chat.Component.literal(
                "§b" + name + " §r" + getSkull().color()
                        + getSkull().displayName() + "§r#" + brain.generation
                        + " §8(研究" + topic + ")"));

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
                // —— 个体知识账本：记录自己学到了什么 ——
                // 这是"文明发展"的最小单位：每只 AI 有自己的知识积累，
                // 而不是只往全局库里倒货。
                knownTopics.add(entry.title());
                contributions++;
                refreshNameTag();
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
        // 玩家通常很少，直接遍历（便宜）
        double bestD = 64;   // 8 格内
        for (var p : level().players()) {
            double d = p.distanceToSqr(this);
            if (d < bestD) { bestD = d; target = p; }
        }
        // 同类扫描是 O(N²) 的第三个来源（1500 只时直接把 tick 堵死）。
        // 复用 Sensors 的 20 tick 节流缓存：只在刷新 tick 扫，
        // 并且只找最近的一个，够用就停。
        if (target == null && socialScanCooldown <= 0) {
            for (BrainPlayerEntity other : level().getEntitiesOfClass(
                    BrainPlayerEntity.class, getBoundingBox().inflate(8),
                    e -> e != this)) {
                double d = other.distanceToSqr(this);
                if (d < bestD) { bestD = d; target = other; break; }
            }
            // 没找到也标记已扫，避免每 tick 重扫
            socialScanCooldown = 20;
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

    // ==================== 玩家指令系统 ====================

    /**
     * 接收玩家指令。
     *
     * @param action  指令类型
     * @param source  下发指令的玩家（作为主人 + 朝向参考）
     * @return 是否接受（拒绝时要给玩家反馈）
     */
    public boolean acceptCommand(com.flybrain.comm.BotCommand.Action action,
                                 net.minecraft.world.entity.player.Player source) {
        if (action == com.flybrain.comm.BotCommand.Action.NONE) return false;

        // 记录主人：指令者就是当前要服从的人
        if (source != null) {
            master = source;
            masterId = source.getUUID();
        }
        currentCommand = action.name();

        switch (action) {
            case EXPLORE -> {
                // 朝面朝方向走 12-20 格（找可走到的点）
                double yaw = Math.toRadians(source != null
                        ? source.getYRot() : getYRot());
                int dist = 12 + brainRandom().nextInt(9);
                int tx = (int) (getX() - Math.sin(yaw) * dist);
                int tz = (int) (getZ() + Math.cos(yaw) * dist);
                commandTarget = pickWalkableTargetNear(tx, tz, 8);
                if (commandTarget == null) {
                    // 前方走不通，就随便探个方向
                    Vec3 fallback = pickWalkableTarget(14);
                    commandTarget = fallback == null ? null
                            : net.minecraft.core.BlockPos.containing(fallback);
                }
                commandTicksLeft = 100;      // 5 秒
                getLookControl().setLookAt(source != null ? source : this);
                return commandTarget != null;
            }
            case COME -> {
                commandTicksLeft = 160;      // 8 秒，够走到玩家旁边
                return true;
            }
            case STOP -> {
                getNavigation().stop();
                commandTicksLeft = 40;       // 2 秒
                return true;
            }
            case ATTACK -> {
                commandTicksLeft = 120;
                return true;
            }
            case JUMP -> {
                if (onGround()) jumpFromGround();
                commandTicksLeft = 10;
                return true;
            }
            case WANDER -> {
                commandTicksLeft = 200;      // 10 秒自由游走
                return true;
            }
            case GREET -> {
                commandTicksLeft = 30;
                if (source != null) {
                    getLookControl().setLookAt(source);
                    speak(source, net.minecraft.network.chat.Component.literal(greetLine()));
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 每 tick 执行当前指令，返回 true = 指令仍有效 */
    private boolean runCommand() {
        var action = com.flybrain.comm.BotCommand.Action.valueOf(currentCommand);
        LivingEntity m = getMaster();

        switch (action) {
            case EXPLORE -> {
                if (commandTarget == null) return false;
                double d2 = distanceToSqr(commandTarget.getX() + 0.5,
                        commandTarget.getY(), commandTarget.getZ() + 0.5);
                if (d2 < 4.0) return false;              // 到了
                if (!getNavigation().moveTo(commandTarget.getX() + 0.5,
                        commandTarget.getY(), commandTarget.getZ() + 0.5, 1.15)) {
                    return false;                        // 走不通
                }
                return true;
            }
            case COME -> {
                if (m == null) return false;
                double dm = distanceToSqr(m);
                if (dm < 4.0) return false;              // 已经贴身
                getNavigation().moveTo(m, 1.2);
                return true;
            }
            case STOP -> {
                getNavigation().stop();
                return true;
            }
            case ATTACK -> {
                // 找最近的怪物；没有就回复"没目标"
                var level = level();
                var foes = level.getEntitiesOfClass(
                        net.minecraft.world.entity.monster.Monster.class,
                        getBoundingBox().inflate(12),
                        e -> e.isAlive() && !e.isRemoved());
                if (foes.isEmpty()) return false;
                var target = foes.get(0);
                getLookControl().setLookAt(target);
                getNavigation().moveTo(target, 1.2);
                return true;
            }
            case JUMP -> {
                return commandTicksLeft > 0;
            }
            case WANDER -> {
                wanderForward(1.0);
                return true;
            }
            case GREET -> {
                return commandTicksLeft > 0;
            }
            default -> {
                return false;
            }
        }
    }

    /** 找 commandTarget 附近的可行走点（探索指令用，避免目标在悬崖里） */
    private net.minecraft.core.BlockPos pickWalkableTargetNear(
            int tx, int tz, int range) {
        var level = level();
        for (int r = 0; r <= range; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    var p = new net.minecraft.core.BlockPos(tx + dx, 70, tz + dz);
                    // 往下找 20 格内的地面
                    for (int y = 90; y > 50; y--) {
                        var at = new net.minecraft.core.BlockPos(p.getX(), y, p.getZ());
                        var below = at.below();
                        if (level.getBlockState(below).isSolid()
                                && level.getBlockState(at).isAir()
                                && level.getBlockState(at.above()).isAir()) {
                            return at;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 不同头颅的打招呼语气 */
    private String greetLine() {
        return switch (getSkull()) {
            case PLAYER -> "嗨！";
            case ZOMBIE -> "呜……你好……";
            case SKELETON -> "咔嗒（点头）";
            case CREEPER -> "嘶……";
            case ENDERMAN -> "……你好。（瞬移到你身旁）";
            case PIGLIN -> "嘿！朋友！";
            case WITHER -> "哼。";
            case SLIME -> "啵啵！";
        };
    }

    /**
     * 尝试把知识教给附近的 AI。
     *
     * 规则：
     *   - 只教 6 格内的同类
     *   - 挑一个"对方不会、我会"的词条（优先挑自己最早学的）
     *   - 对方收到后给正反馈（多巴胺），形成"社交=学习"的正循环
     *
     * 为什么限制 6 格：太远了不现实，而且全服广播开销大。
     */
    public void tryTeachNearby() {
        if (knownTopics.isEmpty()) return;
        var level = level();
        var peers = level.getEntitiesOfClass(BrainPlayerEntity.class,
                getBoundingBox().inflate(6), e -> e != this && e.isAlive());
        if (peers.isEmpty()) return;

        // 找知识最少的邻居当学生（帮扶后进）
        BrainPlayerEntity student = null;
        int minKnown = Integer.MAX_VALUE;
        for (var p : peers) {
            if (p.knownTopics.size() < minKnown) {
                minKnown = p.knownTopics.size();
                student = p;
            }
        }
        if (student == null || student.knownTopics.size() >= knownTopics.size()) {
            return;      // 没有可教的对象（对方懂得不比我少）
        }

        // 挑一个对方不会的词条
        String topic = null;
        for (String t : knownTopics) {
            if (!student.knownTopics.contains(t)) {
                topic = t;
                break;
            }
        }
        if (topic == null) return;

        // 传授
        student.knownTopics.add(topic);
        student.learned++;
        teachings++;
        giveDopamine(0.3f);            // 教会别人也有奖励
        student.giveDopamine(0.4f);    // 学到新东西奖励更多

        // 视觉反馈： enchant 粒子 + 双方头顶冒消息
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.ENCHANT,
                    student.getX(), student.getY() + 1.5, student.getZ(),
                    12, 0.3, 0.3, 0.3, 0.1);
        }
        // 只在 DEBUG 或偶尔汇报，避免 1000 只 AI 互相教刷爆日志
        if (FlyBrainPlayerMod.LOGGER.isDebugEnabled() || brainRandom().nextFloat() < 0.02f) {
            FlyBrainPlayerMod.LOGGER.info(
                    "[Teach] {} 教会 {} 「{}」（学生已学 {} 条）",
                    name, student.name, topic, student.knownTopics.size());
        }
    }

    private void wanderForward(double speed) {
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

    /** 刚出生时打一行日志，确认预训练生效 + 大脑独立。
     *   默认关闭：1500 只同时出生时这条 INFO 会把日志 I/O 打满，
     *   直接把 tick 堵死（实测实体全部超时消失）。
     *   需要时把 com.flybrain 调到 DEBUG 即可看到。 */
    private int birthLogged;

/**
     * 自然生成规则：AI 自己在世界里找地方出生。
     *
     * 条件（比原版怪物宽松，毕竟它们是"玩家"不是怪物）：
     *   - 脚下有实心方块可站
     *   - 头不被卡住
     *   - 亮度 ≥ 7（不至于在黑洞里刷出来）
     *   - 不在水里
     *
     * 群系/高度不限 —— 让它们满世界跑，这才叫"自由生成"。
     */
    /**
     * 自然生成规则：AI 自己在世界里找地方出生。
     *
     * 注意这是**静态方法**（1.21.1 签名带 EntityType + RandomSource）——
     * 写成实例方法编译直接报"方法不会覆盖超类型方法"。
     *
     * 条件（比原版怪物宽松，毕竟是"玩家"不是怪物）：
     *   - 脚下有实心方块、身体是空气
     *   - 亮度 ≥ 7（别在全黑洞穴里无限刷）
     *   - 不在水里
     * 群系/高度不限 —— 满世界跑，这才叫自由生成。
     */
    public static boolean checkMobSpawnRules(
            net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> type,
            net.minecraft.world.level.LevelAccessor level,
            net.minecraft.world.entity.MobSpawnType reason,
            net.minecraft.core.BlockPos pos,
            net.minecraft.util.RandomSource random) {
        if (reason == net.minecraft.world.entity.MobSpawnType.SPAWNER) return true;
        if (!level.getBlockState(pos.below()).isValidSpawn(level, pos, type)) return false;
        if (!level.getBlockState(pos).isAir()) return false;
        if (!level.getBlockState(pos.above()).isAir()) return false;
        if (level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) return false;
        return level.getRawBrightness(pos, 0) >= 7;
    }

    /**
     * 出生时的初始化：随机头颅 + 随机性格 + 家园。
     * 这样满世界自由生成的 AI 天然就是多样化种群，
     * 而不是清一色同一个体。
     *
     * 抽成独立方法 {@link #initSpawnTraits}，让自然生成、召唤器、南瓜头扫描仪、
     * 测试命令共用同一套"出生规则"——否则召唤出来的 AI 会全变成默认 PLAYER
     * 且没有个体属性，和自然生成的种群不一致。
     */
    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
            net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty,
            net.minecraft.world.entity.MobSpawnType reason,
            net.minecraft.world.entity.SpawnGroupData data) {
        initSpawnTraits();
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    /**
     * 出生规则（所有生成路径共用）：加权选头颅 + 个体属性扰动 + 记家园。
     * 抽出来是因为召唤器 / 南瓜头扫描仪 / 测试命令也是"生成 AI"，
     * 应当和自然生成走同一套规则，否则种群不一致。
     */
    public void initSpawnTraits() {
        // 随机挑一种头颅（按权重，稀有害羞型更少 —— 形成自然种群构成）
        this.skull = randomSkull(this.getRandom());
        this.applyProfile(this.skull);     // 同步到客户端 + 调大脑性格（基准）
        // 个体特性种子 → 同头颅也各有差异（性格+属性扰动）
        this.traitSeed = this.getRandom().nextLong();
        this.rollIndividualTraits();       // 在 applyProfile 之后叠加个体扰动
        this.assignName();                 // 分配随机英文名（ALEX/STEVE/...）
        // 记家园：出生地就是它的"窝"
        this.home = this.blockPosition();
        this.hasHome = true;
    }

    /**
     * 分配随机英文名，并把它写进头顶显示名。
     *
     * 头顶格式：{@code ALEX §7末影人#3} —— 英文名给玩家喊，
     * 中文头颅名说明它是什么类型，#N 是世代。
     *
     * 全服去重（见 {@link NameGen}），否则喊一声两个 AI 都答应。
     */
    public void assignName() {
        if (name == null || name.isEmpty()) {
            name = NameGen.next();
        }
        refreshNameTag();
    }

    /** 刷新头顶名字（换了头颅/世代变化时也要调用） */
    public void refreshNameTag() {
        setCustomName(net.minecraft.network.chat.Component.literal(
                "§b" + name + " §r" + getSkull().color()
                        + getSkull().displayName() + "§r#" + brain.generation));
        setCustomNameVisible(true);
    }

    /**
     * 个体属性生成：在头颅基准之上叠加**个体扰动**，让同头颅的 AI 也各有不同。
     *
     * 这是"自然生成的 AI 玩家具备多样性"的核心——否则 1000 只僵尸玩家
     * 会是同一台机器的克隆，看不出种群差异。
     *
     * 扰动分两层：
     *  ① 性格扰动：学习率/好奇/攻击/社交各 ±一小段，仍留在合理区间
     *  ② 属性扰动：生命/移速/攻击/视野各乘一个 vigor 系数（0.85~1.15）
     *
     * 扰动用 traitSeed 驱动，所以读档可精确复现同一只 AI 的"天性"。
     */
    private void rollIndividualTraits() {
        var base = skull.personality();
        var r = new java.util.Random(traitSeed == 0 ? System.nanoTime() : traitSeed);
        float lr   = clamp(base.learningRate() * (0.8f + r.nextFloat() * 0.4f), 0.005f, 0.08f);
        float cur  = clamp(base.curiosity()  + (r.nextFloat() - 0.5f) * 0.30f, 0f, 1f);
        float aggr = clamp(base.aggression() + (r.nextFloat() - 0.5f) * 0.30f, 0f, 1f);
        float soc  = clamp(base.sociability()+ (r.nextFloat() - 0.5f) * 0.30f, 0f, 1f);
        this.persona = new com.flybrain.profile.SkullKind.Personality(lr, cur, aggr, soc);

        // 重放大脑性格（applyProfile 已放过基准，这里换成扰动后的版本）
        brain.applyPersonality(lr, cur, aggr, soc);
        for (var skill : skull.skills()) brain.applySkill(skill.name());

        // 属性扰动：让"体格"也五花八门
        float vigor = 0.85f + r.nextFloat() * 0.30f;          // 0.85~1.15
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(clamp(20.0f * vigor, 10f, 40f));
        setHealth(getMaxHealth());
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(
                clamp(0.25f * (0.85f + r.nextFloat() * 0.30f), 0.18f, 0.35f));
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(
                clamp(2.0f * (0.7f + r.nextFloat() * 0.6f), 1.0f, 5.0f));
        getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(
                clamp(32.0f * (0.85f + r.nextFloat() * 0.30f), 16f, 48f));
        // 初始能量给个随机相位（0.55~1.0），让每只 AI 的"作息"错峰，
        // 不会出现"全体同时累、同时歇"的假死现象
        this.energy = 0.55f + r.nextFloat() * 0.45f;
    }

    /** 读档后重放个体性格（同时修掉"读档丢失头颅性格"的旧 bug） */
    private void reapplyBrainProfile() {
        if (persona == null) persona = skull.personality();
        brain.applyPersonality(persona.learningRate(), persona.curiosity(),
                persona.aggression(), persona.sociability());
        for (var skill : skull.skills()) brain.applySkill(skill.name());
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    /**
     * 休息：原地停步、回血般恢复能量。
     * 能量恢复后 CH_TIRED 下降，大脑自然不再选 REST —— 形成自然作息，
     * 而不是"脑在思考但身体不动"的假死。
     */
    private void doRest() {
        getNavigation().stop();
        energy = Math.min(1f, energy + 0.02f);
        // 休息本身是有回报的（恢复状态）→ 给一点多巴胺，
        // 让大脑学会"累了就该歇"，而不是被"爱动"的塑性权重带偏、
        // 永远不选 REST 导致能量归零假死。
        giveDopamine(0.12f);
        if (level() instanceof ServerLevel sl && brainRandom().nextFloat() < 0.05f) {
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    getX(), getY() + 1.2, getZ(), 2, 0.2, 0.2, 0.2, 0.01);
        }
    }

    /**
     * 躲避：淋雨/雷暴/离家远时的归巢行为。
     * 有家园 → 走回家；在家或无家园 → 原地蹲下"找遮蔽"（避雷避雨）。
     * 这也是"与游戏环境自然交互"的体现：天气变了，AI 真的会动。
     */
    private void doShelter(LivingEntity master) {
        if (hasHome && home != null && home.distSqr(blockPosition()) > 9) {
            getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.9);
        } else {
            getNavigation().stop();          // 在家/无家 → 原地躲避
            energy = Math.min(1f, energy + 0.01f);   // 躲雨也缓口气
        }
    }

    /** 加权随机头颅：决定自然生成时的种群构成（稀有害羞型更少） */
    private static final int[] SPAWN_WEIGHTS = {22, 14, 12, 10, 8, 15, 3, 16};
    //                                 PLAYER ZOMB SKELE CREE ender PIGLIN WITHER SLIME
    public static com.flybrain.profile.SkullKind randomSkull(net.minecraft.util.RandomSource r) {
        var all = com.flybrain.profile.SkullKind.values();
        int total = 0;
        for (int w : SPAWN_WEIGHTS) total += w;
        int pick = r.nextInt(total);
        for (int i = 0; i < all.length; i++) {
            pick -= SPAWN_WEIGHTS[i];
            if (pick < 0) return all[i];
        }
        return all[0];
    }

    /** 移除时释放名字，避免名字池被死 AI 占着 */
    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (!level().isClientSide && name != null && !name.isEmpty()) {
            NameGen.release(name);
        }
        super.remove(reason);
    }

    /** 一次最多生几只 —— 控制单点密度，避免一坨全挤在一起 */
    @Override
    public int getMaxSpawnClusterSize() {
        return 4;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (birthLogged++ == 3 && FlyBrainPlayerMod.LOGGER.isDebugEnabled()) {
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
        // 个体属性（自然生成的多样性）
        var p = persona != null ? persona : skull.personality();
        player.sendSystemMessage(Component.literal(String.format(
                "§7个体性格 §8| §7学率 §f%.3f §7好奇 §f%.2f §7攻击 §f%.2f §7社交 §f%.2f",
                p.learningRate(), p.curiosity(), p.aggression(), p.sociability())));
        player.sendSystemMessage(Component.literal(String.format(
                "§7体格 §8| §7生命 §f%.0f §7移速 §f%.2f §7攻击 §f%.1f §7能量 §f%.0f%%",
                getMaxHealth(), getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue(),
                getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue(), energy * 100f)));
        if (hasHome && home != null) {
            double d = Math.sqrt(home.distSqr(blockPosition()));
            player.sendSystemMessage(Component.literal(String.format(
                    "§7家园 §f(%d,%d,%d) §7距 §f%.0f §7格", home.getX(), home.getY(),
                    home.getZ(), d)));
        }
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
        tag.putString("Name", name == null ? "" : name);   // 英文名持久化
        // 个体知识账本（文明发展进度）
        net.minecraft.nbt.ListTag known = new net.minecraft.nbt.ListTag();
        for (String t : knownTopics) known.add(net.minecraft.nbt.StringTag.valueOf(t));
        tag.put("KnownTopics", known);
        tag.putInt("Contributions", contributions);
        tag.putInt("Teachings", teachings);
        tag.putInt("Learned", learned);
        net.minecraft.nbt.ListTag topics = new net.minecraft.nbt.ListTag();
        for (String t : pendingTopics) topics.add(net.minecraft.nbt.StringTag.valueOf(t));
        tag.put("PendingTopics", topics);
        // 个体属性（自然生成多样性）：种子 + 解析性格 + 能量 + 家园
        tag.putLong("TraitSeed", traitSeed);
        if (persona != null) tag.put("Persona", persona.toNbt());
        tag.putFloat("Energy", energy);
        tag.putBoolean("HasHome", hasHome);
        if (hasHome && home != null) tag.putLong("Home", home.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("FlyBrain")) brain.load(tag.getCompound("FlyBrain"));
        if (tag.hasUUID("MasterId")) masterId = tag.getUUID("MasterId");
        // 恢复英文名：先占住名字（防止读档期间被新 AI 抢走），再刷新头顶
        String savedName = tag.getString("Name");
        if (!savedName.isEmpty()) {
            name = savedName;
            NameGen.reserve(name);
        }
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
        // 恢复个体知识账本
        net.minecraft.nbt.ListTag known2 = tag.getList("KnownTopics",
                net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < known2.size(); i++) knownTopics.add(known2.getString(i));
        contributions = tag.getInt("Contributions");
        teachings = tag.getInt("Teachings");
        learned = tag.getInt("Learned");
        // 恢复个体属性
        traitSeed = tag.getLong("TraitSeed");
        persona = tag.contains("Persona")
                ? com.flybrain.profile.SkullKind.Personality.fromNbt(tag.getCompound("Persona"))
                : null;
        // 旧存档没有 Energy 字段 → 默认满能量，否则会永远"极累想歇"
        energy = tag.contains("Energy") ? tag.getFloat("Energy") : 1f;
        hasHome = tag.getBoolean("HasHome");
        home = (hasHome && tag.contains("Home"))
                ? net.minecraft.core.BlockPos.of(tag.getLong("Home")) : null;
        // 重放个体性格（修掉旧版"读档后头颅性格丢失"的 bug：
        // 之前只恢复同步数据、不重放 applyProfile，导致性格加成凭空消失）
        reapplyBrainProfile();
        // 头顶名不存 NBT，读档后必须重刷，否则名字会变回默认值
        if (name == null || name.isEmpty()) {
            // 旧存档没有 Name 字段 —— 补分配一个，别让老 AI 变成"哑巴"
            assignName();
        } else {
            refreshNameTag();
        }
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
