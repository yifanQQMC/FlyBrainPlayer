package com.flybrain.brain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * 果蝇全脑连接组模拟器（Connectome）。
 *
 * 灵感来自 Janelia Research Campus 2024 年发布的成年果蝇全脑连接组
 * （FlyWire / Dorkenwald et al., Nature 2024，约 14 万神经元、5×10^7 突触）。
 * 这里取其结构骨架做轻量在线模拟：
 *
 *  - 20 个 PNP（投影神经元，嗅觉输入）
 *  - 50 个 KC（Kenyon 细胞，蘑菇体，稀疏编码记忆）
 *  - 12 个 MBON（蘑菇体输出神经元，动作偏好）
 *  - Lateral Horn（侧角）：先天气味通路（威胁/食物）
 *  - LPTC（视觉大切向细胞）：运动检测
 *  - 多巴胺奖励信号驱动 Hebbian + 资格迹学习
 *
 * 纯 Java，无任何依赖；每 tick 全网络前向传播 < 1 µs。
 */
public class FlyBrain {
    /** 世代计数（多巴胺奖励次数） */
    public int generation;
    /** 蘑菇体固定稀疏度 k（果蝇真实值 ≈ 5%） */
    public static final int K_WTA = 3;

    // ---- 感受器通道（PN 输入强度，0~1）----
    public static final int PN_COUNT = 24;
    public static final int CH_HUNGER   = 0;   // 饥饿素
    public static final int CH_FOOD     = 1;   // 附近可见食物（掉落物）
    public static final int CH_THREAT   = 2;   // 敌对生物
    public static final int CH_MASTER   = 3;   // 主人方位
    public static final int CH_LIGHT    = 4;   // 昼夜
    public static final int CH_OBST_L   = 5;   // 左前方障碍
    public static final int CH_OBST_R   = 6;   // 右前方障碍
    public static final int CH_GAP      = 7;   // 前方深坑
    public static final int CH_ITEM     = 8;   // 手上有东西
    public static final int CH_PAIN     = 9;   // 受伤
    public static final int CH_REWARD   = 10;  // 多巴胺（学习信号）
    public static final int CH_WATER    = 11;  // 水/岩浆危险
    public static final int CH_FALLING  = 12;  // 下落中
    public static final int CH_NOISE    = 13;  // 随机探索噪声
    public static final int CH_FOLLOW   = 14;  // 是否被拴住/跟随模式
    public static final int CH_SOCIAL   = 15;  // 附近同类数量
    public static final int CH_NIGHT    = 16;  // 夜晚
    public static final int CH_HEIGHT   = 17;  // 离地高度
    public static final int CH_SPEED    = 18;  // 自身速度
    public static final int CH_TOUCH    = 19;  // 身体接触方块
    // —— 新增：好奇心与知识状态 ——
    public static final int CH_CURIOSITY = 20; // 未知刺激强度（看到没见过的方块/生物）
    public static final int CH_NOVELTY   = 21; // 未学过的词条数（越多越想去查）
    public static final int CH_SOCIAL_TALK = 22; // 附近可交谈的对象
    public static final int CH_BOREDOM   = 23; // 无事可做（触发自主学习）

    // ---- MBON 输出通道 ----
    public static final int MB_COUNT = 14;
    public static final int OUT_FORWARD  = 0;
    public static final int OUT_TURN_L   = 1;
    public static final int OUT_TURN_R   = 2;
    public static final int OUT_BACK     = 3;
    public static final int OUT_JUMP     = 4;
    public static final int OUT_STOP     = 5;
    public static final int OUT_ATTACK   = 6;
    public static final int OUT_SPRINT   = 7;
    public static final int OUT_GREET    = 8;   // 对主人挥手（转向主人+停止）
    public static final int OUT_DIG      = 9;   // 挖掘
    public static final int OUT_WANDER   = 10;
    public static final int OUT_DANCE    = 11;  // 快乐旋转
    // —— 新增：与知识/社交相关的行为 ——
    public static final int OUT_STUDY    = 12;  // 研究：去查 wiki 学新东西
    public static final int OUT_TALK     = 13;  // 交谈：靠近同类或主人搭话

    // ---- 连接组矩阵 ----
    /** PN -> KC 随机稀疏投影（果蝇 antennal lobe → mushroom body） */
    private final boolean[][] pnToKc = new boolean[PN_COUNT][50];
    private final float[][] kcToMbon = new float[50][MB_COUNT];
    /** LH（侧角）先天气味通路，不可塑 */
    private final float[][] pnToMbonInnate = new float[PN_COUNT][MB_COUNT];
    /** 突触学习率 */
    public float learningRate = 0.02f;
    /** 资格迹衰减 */
    public float eligibilityDecay = 0.90f;

    private final float[] kcActivity = new float[50];
    private final float[] mbonActivity = new float[MB_COUNT];
    private final float[][] eligibility = new float[50][MB_COUNT];
    private final Random random = new Random();
    /** 记忆回放缓冲（睡眠式离线巩固） */
    private final Deque<float[]> replayBuffer = new ArrayDeque<>();
    private static final int REPLAY_MAX = 64;

    /**
     * 新建一只果蝇脑。
     *
     * 关键设计——**两个层次必须分开**：
     *
     * ① <b>神经解剖（pnToKc 接线）物种共享</b>：用固定种子生成，所有个体完全一样。
     *    这对应真实的基因决定的连接组，是"硬件"。
     *    共享它才能让预训练权重对每个个体都有意义。
     *
     * ② <b>突触强度（kcToMbon 权重）个体独立</b>：从预训练模板复制 + 微小随机扰动。
     *    这对应个体经历形成的经验，是"软件"。
     *
     * 预训练（pretrain）在这里自动完成一次：把侧角先天通路当作教师信号，
     * 训练 KC→MBON 权重，让每只新生的果蝇一出生就具备基础生存能力，
     * 而不是从随机噪声开始（后者表现为"AI 出生时什么都干不了"）。
     *
     * @param seed 个体种子——决定突触扰动，使每只 AI 的学习起点略有不同
     */
    public FlyBrain(long seed) {
        this(seed, false);
    }

    /**
     * @param skipPretrain 预训练器内部会造一只"探针"果蝇脑来读取出厂通路，
     *                      它必须跳过预训练本身，否则无限递归。
     */
    FlyBrain(long seed, boolean skipPretrain) {
        Random r = new Random(seed);

        // ① 神经解剖：固定种子 → 物种共享
        //
        // 关键设计：**每个 PN 至少有一条专属 KC**（one-hot 骨架），
        // 再叠加一些随机共享 KC（组合编码）。
        //
        // 为什么必须这样：早期版本用纯随机接线，24 个 PN 共享 50 个 KC、
        // 每个 KC 连 6 个 PN，配合 k=3 的 WTA 稀疏化后，
        // 不同 PN 激活时会撞到**同一组** KC（都等于 1.0 无法区分），
        // 导致多个 MBON 输出完全相同 —— 表现为"AI 会打招呼但不会逃跑"这类
        // 严重的通道混淆。专属 KC 保证了每个输入都有独立表示。
        Random anatomy = new Random(ANATOMY_SEED);
        for (int pn = 0; pn < PN_COUNT; pn++) {
            // 专属 KC：独占本通道，保证可分性
            pnToKc[pn][pn] = true;
            // 共享 KC：提供组合编码，让相似输入有部分重叠
            int shared = 2 + anatomy.nextInt(2);
            for (int s = 0; s < shared; s++) {
                pnToKc[pn][PN_COUNT + anatomy.nextInt(50 - PN_COUNT)] = true;
            }
        }

        // ② 铺设出厂先天通路（教师信号）
        installBaseInnate();

        // ③ 突触强度：从预训练模板复制 + 个体扰动
        if (skipPretrain) {
            // 探针用途：留零权重，只为读取 innate 与接线
            for (int kc = 0; kc < 50; kc++) {
                for (int mb = 0; mb < MB_COUNT; mb++) kcToMbon[kc][mb] = 0f;
            }
            return;
        }
        float[][] template = Pretrain.template();
        for (int kc = 0; kc < 50; kc++) {
            for (int mb = 0; mb < MB_COUNT; mb++) {
                // 扰动幅度 ±6%：保证都在"具备基础能力"的范围内，但个体略有差异
                float jitter = 1f + (r.nextFloat() - 0.5f) * 0.12f;
                kcToMbon[kc][mb] = template[kc][mb] * jitter;
            }
        }
    }

    /** 供 Pretrain 读取出厂先天通路矩阵 */
    float[][] copyInnateMatrix() {
        float[][] m = new float[PN_COUNT][MB_COUNT];
        for (int i = 0; i < PN_COUNT; i++) System.arraycopy(pnToMbonInnate[i], 0, m[i], 0, MB_COUNT);
        return m;
    }

    /** 供 Pretrain 读取 KC 接线矩阵 */
    boolean[][] copyAnatomyMatrix() {
        boolean[][] m = new boolean[PN_COUNT][50];
        for (int i = 0; i < PN_COUNT; i++) System.arraycopy(pnToKc[i], 0, m[i], 0, 50);
        return m;
    }

    /**
     * 大脑指纹：把突触权重压成一个短哈希。
     *
     * 用途是**证明个体独立**——两只 AI 的指纹必然不同，
     * 但同时它们都能通过预训练自检（基础能力都在）。
     */
    public String brainFingerprint() {
        long h = 1469598103934665603L;      // FNV-1a
        for (int kc = 0; kc < 50; kc++) {
            for (int mb = 0; mb < MB_COUNT; mb++) {
                h ^= Float.floatToIntBits(kcToMbon[kc][mb]);
                h *= 1099511628211L;
            }
        }
        String hex = Long.toHexString(h).toUpperCase();
        return hex.length() >= 8 ? hex.substring(0, 8) : hex;
    }

    /** 突触权重总量（用来粗略判断这只脑"学过东西没有"） */
    public float weightMagnitude() {
        float sum = 0;
        for (int kc = 0; kc < 50; kc++) {
            for (int mb = 0; mb < MB_COUNT; mb++) sum += Math.abs(kcToMbon[kc][mb]);
        }
        return sum;
    }

    /** 物种级连接组的固定种子（保证所有个体的神经解剖一致） */
    public static final long ANATOMY_SEED = 20260101L;

    /** 出厂先天通路——预训练的教师信号，同时也是各性情的基准 */
    private void installBaseInnate() {
        innate(CH_THREAT, OUT_ATTACK, 1.2f);
        innate(CH_THREAT, OUT_BACK,  0.6f);
        innate(CH_FOOD,  OUT_FORWARD, 1.5f);
        innate(CH_FOOD,  OUT_SPRINT,  0.4f);
        innate(CH_HUNGER,OUT_FORWARD, 0.5f);
        innate(CH_HUNGER,OUT_WANDER,  0.4f);
        innate(CH_OBST_L,OUT_TURN_R,  0.9f);
        innate(CH_OBST_R,OUT_TURN_L,  0.9f);
        innate(CH_GAP,   OUT_JUMP,    0.8f);
        innate(CH_GAP,   OUT_TURN_L,  0.3f);
        innate(CH_PAIN,  OUT_BACK,    0.7f);
        innate(CH_MASTER,OUT_GREET,   0.5f);
        innate(CH_MASTER,OUT_SPRINT,  0.3f);
        innate(CH_ITEM,  OUT_STOP,    0.3f);
        innate(CH_FALLING,OUT_STOP,   0.5f);
        innate(CH_NIGHT, OUT_SPRINT,  0.3f);
        innate(CH_NOISE, OUT_WANDER,  0.5f);
        innate(CH_NOISE, OUT_FORWARD, 0.7f);
        innate(CH_SPEED, OUT_SPRINT,  0.3f);
        innate(CH_TOUCH, OUT_BACK,    0.4f);
        innate(CH_TOUCH, OUT_JUMP,    1.2f);
        innate(CH_TOUCH, OUT_FORWARD, 0.8f);
        innate(CH_REWARD,OUT_DANCE,   0.4f);
        innate(CH_WATER, OUT_BACK,    0.8f);
        innate(CH_HEIGHT,OUT_STOP,    0.3f);
        innate(CH_NOVELTY,     OUT_STUDY, 1.3f);
        innate(CH_CURIOSITY,   OUT_STUDY, 0.9f);
        innate(CH_CURIOSITY,   OUT_GREET, 0.5f);
        innate(CH_BOREDOM,     OUT_STUDY, 0.7f);
        innate(CH_SOCIAL_TALK, OUT_TALK,  1.2f);
        innate(CH_SOCIAL_TALK, OUT_GREET, 0.8f);
    }

    private void innate(int pn, int mbon, float w) {
        pnToMbonInnate[pn][mbon] = w;
    }

    /**
     * 按头颅类型重塑本能通路 —— **技能的实际生效点**。
     *
     * 不同头颅 = 不同果蝇品系，先天倾向不一样：
     * 僵尸型攻击通路拉满、好奇通路压到近乎为零；
     * 末影人型反过来，学习最快、几乎不打架。
     *
     * 做法是在基础权重上做乘性调制，而不是直接赋值——
     * 这样保留了"通用生存本能"（不会连路都不会走），
     * 只改变各通路的相对强弱。
     */
    public void applyProfile(com.flybrain.profile.SkullKind kind) {
        if (kind == null) return;

        // 每次套用前重置为出厂权重。
        // 不重置的话，换一次头颅就把上一套的乘性调制叠加一次，
        // 反复换头颅会让所有通路权重趋近 0，表现为"AI 什么都干不了"。
        resetInnate();

        var p = kind.personality();

        // —— 攻击性调制：所有"威胁→攻击/后退"通路 ——
        float aggr = Math.max(0f, p.aggression());
        boost(CH_THREAT, OUT_ATTACK, 0.3f + aggr * 2.2f);
        boost(CH_THREAT, OUT_BACK, 0.3f + (1f - aggr) * 1.0f);

        // —— 好奇度调制：学习相关通路 ——
        float cur = Math.max(0f, p.curiosity());
        boost(CH_NOVELTY,   OUT_STUDY, 0.2f + cur * 1.8f);
        boost(CH_CURIOSITY, OUT_STUDY, 0.2f + cur * 1.2f);
        boost(CH_BOREDOM,   OUT_STUDY, 0.1f + cur * 1.0f);

        // —— 社交意愿调制 ——
        float soc = Math.max(0f, p.sociability());
        boost(CH_SOCIAL_TALK, OUT_TALK, 0.2f + soc * 1.6f);
        boost(CH_SOCIAL_TALK, OUT_GREET, 0.2f + soc * 1.0f);

        // —— 技能专属通路 ——
        for (var skill : kind.skills()) {
            switch (skill) {
                case BRAWL -> {
                    boost(CH_THREAT, OUT_ATTACK, 1.6f);
                    boost(CH_PAIN,  OUT_ATTACK, 0.8f);   // 被打会还手
                    boost(CH_REWARD, OUT_ATTACK, 0.5f);
                }
                case MARKSMAN -> {
                    boost(CH_THREAT, OUT_ATTACK, 0.9f);
                    boost(CH_PAIN,  OUT_BACK,   1.4f);   // 保持距离
                    boost(CH_TOUCH, OUT_BACK,   1.0f);
                }
                case FLEE -> {
                    boost(CH_THREAT, OUT_BACK,  2.0f);
                    boost(CH_TOUCH,  OUT_BACK,  1.2f);
                    boost(CH_GAP,    OUT_BACK,  0.6f);
                }
                case MINING -> {
                    boost(CH_ITEM,   OUT_DIG,   1.8f);
                    boost(CH_HEIGHT, OUT_DIG,   0.8f);   // 往下挖
                    boost(CH_TOUCH,  OUT_DIG,   1.0f);
                }
                case BUILDER -> {
                    boost(CH_TOUCH,  OUT_DIG,   1.4f);
                    boost(CH_ITEM,   OUT_DIG,   0.9f);
                    boost(CH_HEIGHT, OUT_STOP,  0.5f);
                }
                case SCAVENGER -> {
                    boost(CH_FOOD,   OUT_FORWARD, 2.0f);
                    boost(CH_SOCIAL, OUT_FORWARD, 0.5f);
                }
                case CURIOUS -> {
                    boost(CH_CURIOSITY, OUT_STUDY, 1.5f);
                    boost(CH_NOISE,     OUT_WANDER, 1.0f);
                }
                case SOCIAL -> {
                    boost(CH_MASTER, OUT_GREET, 1.6f);
                    boost(CH_SOCIAL, OUT_TALK,  1.2f);
                    boost(CH_FOLLOW, OUT_SPRINT, 1.0f);
                }
                case ASSAULT -> {
                    boost(CH_THREAT, OUT_ATTACK, 2.5f);
                    boost(CH_REWARD, OUT_DANCE,  0.6f);
                }
                case EXPLOSIVE -> {
                    boost(CH_THREAT, OUT_ATTACK, 3.0f);
                    boost(CH_REWARD, OUT_DANCE,  1.0f);
                    boost(CH_TOUCH,  OUT_DANCE,  0.8f);
                }
                case DESTRUCTION -> {
                    boost(CH_THREAT, OUT_ATTACK, 3.5f);
                    boost(CH_TOUCH,  OUT_DIG,    1.6f);
                    boost(CH_PAIN,   OUT_ATTACK, 2.0f);
                }
                case PORTAL -> {
                    boost(CH_GAP,    OUT_JUMP,   2.0f);
                    boost(CH_FALLING, OUT_JUMP,  1.5f);
                    boost(CH_NOISE,  OUT_SPRINT, 0.8f);
                }
            }
        }

        // 学习率直接由性格决定（学者型 0.055，凋灵型 0.008）
        learningRate = p.learningRate();

        applyProfileCommitted = true;
    }

    /** 对某条通路做乘性调制（保底不会归零） */
    private void boost(int pn, int mbon, float factor) {
        pnToMbonInnate[pn][mbon] *= Math.max(0.05f, factor);
    }

    /** 把本能通路恢复成出厂权重（通用生存本能） */
    private void resetInnate() {
        for (int i = 0; i < PN_COUNT; i++) {
            java.util.Arrays.fill(pnToMbonInnate[i], 0f);
        }
        installBaseInnate();      // 复用出厂通路，避免两处定义漂移
    }

    /** 标记是否已套用性格，防止每 tick 重复叠加权重 */
    public boolean applyProfileCommitted;

    /** 前向传播：输入 PN 活动 → KC WTA → MBON。返回动作向量。 */
    public float[] step(float[] pnInput) {
        // 1. Kenyon 细胞：求和 + k-WTA 稀疏化（果蝇蘑菇体编码策略）
        for (int kc = 0; kc < 50; kc++) {
            float sum = 0;
            for (int pn = 0; pn < PN_COUNT; pn++)
                if (pnToKc[pn][kc]) sum += pnInput[pn];
            kcActivity[kc] = sum;
        }
        topK(kcActivity, K_WTA);

        // 2. MBON = 可塑通路(KC→MBON) + 先天通路(PN→MBON)
        for (int mb = 0; mb < MB_COUNT; mb++) {
            float act = 0;
            for (int kc = 0; kc < 50; kc++)
                if (kcActivity[kc] > 0) act += kcToMbon[kc][mb] * kcActivity[kc];
            for (int pn = 0; pn < PN_COUNT; pn++)
                act += pnToMbonInnate[pn][mb] * pnInput[pn];
            mbonActivity[mb] = Math.max(0, act);
        }
        // 拷贝输出
        float[] out = new float[MB_COUNT];
        System.arraycopy(mbonActivity, 0, out, 0, MB_COUNT);
        return out;
    }

    /**
     * k-WTA（Winner-Take-All）：保留活动最强的 k 个神经元，其余清零。
     *
     * 关键：保留的是**各自的原始活动值**，不是统一赋成同一个数。
     * 早期版本把所有存活者都写成第 k 名的值，等于抹平了强度差异，
     * 导致 MBON 拿到的全是同一个标量，决策退化成随机噪声。
     */
    private void topK(float[] a, int k) {
        int n = a.length;
        boolean[] keep = new boolean[n];
        float[] work = a.clone();

        for (int pass = 0; pass < k; pass++) {
            int maxI = -1;
            float maxV = 0f;                       // 只认严格大于 0 的
            for (int i = 0; i < n; i++) {
                if (work[i] > maxV) { maxV = work[i]; maxI = i; }
            }
            if (maxI < 0) break;                   // 全是 0，没有赢家
            keep[maxI] = true;
            work[maxI] = -1f;
        }
        for (int i = 0; i < n; i++) {
            if (!keep[i]) a[i] = 0f;               // 非赢家归零，赢家保留原值
        }
    }

    /**
     * 多巴胺奖励：强化上一动作。用资格迹（eligibility trace），
     * 类比果蝇 PPL1/PAM 多巴胺神经元对蘑菇体的奖惩输入。
     */
    public void reward(float dopamine, int lastMbon) {
        if (dopamine <= 0) return;
        // 更新资格迹
        for (int kc = 0; kc < 50; kc++)
            for (int mb = 0; mb < MB_COUNT; mb++)
                eligibility[kc][mb] = eligibility[kc][mb] * eligibilityDecay
                        + (kcActivity[kc] > 0 ? kcActivity[kc] : 0);

        for (int kc = 0; kc < 50; kc++) {
            for (int mb = 0; mb < MB_COUNT; mb++) {
                float dw = learningRate * dopamine * eligibility[kc][mb]
                        * (mb == lastMbon ? 1f : -0.05f);
                kcToMbon[kc][mb] += dw;
                // 突触饱和
                if (kcToMbon[kc][mb] > 2f) kcToMbon[kc][mb] = 2f;
                if (kcToMbon[kc][mb] < -1f) kcToMbon[kc][mb] = -1f;
            }
        }
        generation++;
    }

    /** 记录一次经验到回放缓冲（用于睡觉时巩固） */
    public void memorize(float[] pnInput) {
        if (replayBuffer.size() >= REPLAY_MAX) replayBuffer.pollFirst();
        replayBuffer.addLast(pnInput.clone());
    }

    /** 睡眠巩固：随机重放经验并给予小幅奖励（果蝇夜间记忆 consolidation） */
    public void sleep() {
        if (replayBuffer.isEmpty()) return;
        List<float[]> list = new ArrayList<>(replayBuffer);
        float[] sample = list.get(random.nextInt(list.size()));
        float[] out = step(sample);
        int best = 0;
        for (int i = 1; i < MB_COUNT; i++) if (out[i] > out[best]) best = i;
        reward(0.15f, best);
    }

    /** 序列化到 NBT */
    public net.minecraft.nbt.CompoundTag save() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("generation", generation);
        tag.putFloat("lr", learningRate);
        tag.putFloat("ed", eligibilityDecay);
        tag.putBoolean("profileApplied", applyProfileCommitted);
        net.minecraft.nbt.ListTag w = new net.minecraft.nbt.ListTag();
        for (int kc = 0; kc < 50; kc++) {
            net.minecraft.nbt.ListTag row = new net.minecraft.nbt.ListTag();
            for (int mb = 0; mb < MB_COUNT; mb++) row.add(net.minecraft.nbt.FloatTag.valueOf(kcToMbon[kc][mb]));
            w.add(row);
        }
        tag.put("weights", w);
        return tag;
    }

    public void load(net.minecraft.nbt.CompoundTag tag) {
        generation = tag.getInt("generation");
        learningRate = tag.getFloat("lr");
        eligibilityDecay = tag.getFloat("ed");
        applyProfileCommitted = tag.getBoolean("profileApplied");
        if (tag.contains("weights")) {
            net.minecraft.nbt.ListTag w = tag.getList("weights", net.minecraft.nbt.Tag.TAG_LIST);
            for (int kc = 0; kc < Math.min(50, w.size()); kc++) {
                net.minecraft.nbt.ListTag row = w.getList(kc);
                for (int mb = 0; mb < Math.min(MB_COUNT, row.size()); mb++)
                    kcToMbon[kc][mb] = row.getFloat(mb);
            }
        }
    }
}
