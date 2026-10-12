package com.flybrain.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 基础预训练器。
 *
 * ## 为什么需要预训练
 * 早期版本里 {@code kcToMbon} 初始化为随机小值（±0.1），
 * 结果每只新生的 AI 都是"白纸"——它必须自己探索几十次才学会
 * "看见食物要往前走"。表现就是刚放出来的 AI 站在原地发呆。
 *
 * ## 预训练怎么做（真的有训练，不是手填表）
 * **把侧角（Lateral Horn）当教师信号**：
 * 侧角通路是先天的、正确的、不可塑的（威胁→攻击、食物→前进、障碍→转向…），
 * 我们用它作为"标准答案"，去训练可塑的 KC→MBON 权重，
 * 让蘑菇体学会**重建**侧角的行为映射。
 *
 * 这在神经科学上有对应：果蝇幼虫在蛹期会经历一段"预训练"，
 * 基本的感觉—运动映射在成虫前就已大致建立。
 *
 * ## 算法
 * 单层感知机 + 均方误差，用 SGD 迭代：
 * <pre>
 *   前向: out[mb] = Σ_kc kcActivity[kc] · w[kc][mb]
 *   教师: target[mb] = Σ_pn pn[p] · innate[p][mb]
 *   更新: w[kc][mb] -= lr · (out[mb] - target[mb]) · kcActivity[kc]
 * </pre>
 *
 * ## 结果
 * 全服只训练一次（静态缓存），耗时 < 200ms；
 * 之后每个个体复制模板 + ±6% 扰动。
 * 所以"每只 AI 都有基础能力"与"每只 AI 的大脑互相独立"同时成立。
 */
public final class Pretrain {

    private Pretrain() {}

    /** 训练结果缓存——全服只算一次 */
    private static volatile float[][] cachedTemplate;
    private static volatile float cachedLoss;
    private static volatile long cachedMillis;

    private static final int KC_COUNT = 50;
    private static final int TRAIN_SCENARIOS = 320;
    private static final int EPOCHS = 400;
    private static final float LEARNING_RATE = 0.05f;

    /** 取预训练权重模板（首次调用时训练） */
    public static float[][] template() {
        float[][] t = cachedTemplate;
        if (t == null) {
            synchronized (Pretrain.class) {
                t = cachedTemplate;
                if (t == null) {
                    long t0 = System.currentTimeMillis();
                    Trained trained = train();
                    cachedTemplate = trained.weights;
                    cachedLoss = trained.finalLoss;
                    cachedMillis = System.currentTimeMillis() - t0;
                    com.flybrain.FlyBrainPlayerMod.LOGGER.info(
                            "[Pretrain] 基础预训练完成：{} 轮 x {} 情景，耗时 {}ms，"
                                    + "最终损失 {}（越小越接近侧角先天行为）",
                            EPOCHS, TRAIN_SCENARIOS, cachedMillis,
                            String.format("%.4f", cachedLoss));
                }
                t = cachedTemplate;
            }
        }
        return t;
    }

    /** 预训练质量（平均平方误差），用于自检与展示 */
    public static float loss() {
        template();
        return cachedLoss;
    }

    public static long millis() {
        template();
        return cachedMillis;
    }

    private record Trained(float[][] weights, float finalLoss) {}

    private static Trained train() {
        // 借一只临时果蝇脑拿到"出厂先天通路"和"KC 接线"
        FlyBrain probe = new FlyBrain(0L, true);   // true = 跳过预训练递归
        float[][] innate = probe.copyInnateMatrix();
        boolean[][] pnToKc = probe.copyAnatomyMatrix();

        List<float[]> scenarios = buildScenarios(innate);

        // 权重初始化：小随机 + 零均值
        Random rng = new Random(0xBEEFL);
        float[][] w = new float[KC_COUNT][FlyBrain.MB_COUNT];
        for (int kc = 0; kc < KC_COUNT; kc++) {
            for (int mb = 0; mb < FlyBrain.MB_COUNT; mb++) {
                w[kc][mb] = (rng.nextFloat() - 0.5f) * 0.05f;
            }
        }

        float[] kcAct = new float[KC_COUNT];
        float[] out = new float[FlyBrain.MB_COUNT];

        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            // 学习率衰减：前期大步收敛，后期微调
            float lr = LEARNING_RATE * (1f - (float) epoch / EPOCHS * 0.9f);
            for (float[] sc : scenarios) {
                // ① KC 稀疏激活（与推理时完全一致的 WTA 逻辑）
                for (int kc = 0; kc < KC_COUNT; kc++) {
                    float sum = 0;
                    for (int pn = 0; pn < FlyBrain.PN_COUNT; pn++) {
                        if (pnToKc[pn][kc]) sum += sc[pn];
                    }
                    kcAct[kc] = sum;
                }
                topK(kcAct, FlyBrain.K_WTA);

                // ② 前向：KC → MBON
                java.util.Arrays.fill(out, 0f);
                for (int kc = 0; kc < KC_COUNT; kc++) {
                    if (kcAct[kc] <= 0) continue;
                    for (int mb = 0; mb < FlyBrain.MB_COUNT; mb++) {
                        out[mb] += w[kc][mb] * kcAct[kc];
                    }
                }

                // ③ 反向：误差 = 输出 - 教师信号
                for (int mb = 0; mb < FlyBrain.MB_COUNT; mb++) {
                    float err = out[mb] - sc[FlyBrain.PN_COUNT + mb];
                    if (err == 0) continue;
                    for (int kc = 0; kc < KC_COUNT; kc++) {
                        if (kcAct[kc] <= 0) continue;
                        w[kc][mb] -= lr * err * kcAct[kc];
                        // 突触饱和，防止某个动作被无限放大
                        if (w[kc][mb] > 2f) w[kc][mb] = 2f;
                        if (w[kc][mb] < -1f) w[kc][mb] = -1f;
                    }
                }
            }
        }

        return new Trained(w, evaluate(w, scenarios, pnToKc));
    }

    /**
     * 构造训练情景集。
     * 每个情景 = [PN 输入 ×24 | 教师 MBON 目标 ×14]。
     *
     * 覆盖三类：
     *  ① 单通道：每个 PN 单独激活（确保每条先天通路都能被学到）
     *  ② 随机组合：2~4 个通道同时激活（模拟真实世界的复合情境）
     *  ③ 典型情境：食物+障碍、威胁+疼痛这种高频组合单独强化
     */
    private static List<float[]> buildScenarios(float[][] innate) {
        List<float[]> list = new ArrayList<>(TRAIN_SCENARIOS);
        Random rng = new Random(0x5EED5EEDL);
        int W = FlyBrain.PN_COUNT + FlyBrain.MB_COUNT;

        // ① 单通道
        for (int pn = 0; pn < FlyBrain.PN_COUNT; pn++) {
            float[] sc = new float[W];
            sc[pn] = 1f;
            list.add(withTarget(sc, innate));
        }

        // ② 随机组合（部分通道取 0.5~1.0 的连续强度，更接近真实感知）
        while (list.size() < TRAIN_SCENARIOS - 6) {
            float[] sc = new float[W];
            int n = 2 + rng.nextInt(3);
            for (int i = 0; i < n; i++) {
                sc[rng.nextInt(FlyBrain.PN_COUNT)] = 0.5f + rng.nextFloat() * 0.5f;
            }
            list.add(withTarget(sc, innate));
        }

        // ③ 典型复合情境（学习率加倍，让高频组合更扎实）
        int[][] typical = {
                {FlyBrain.CH_FOOD, FlyBrain.CH_OBST_L},
                {FlyBrain.CH_FOOD, FlyBrain.CH_OBST_R},
                {FlyBrain.CH_THREAT, FlyBrain.CH_PAIN},
                {FlyBrain.CH_THREAT, FlyBrain.CH_MASTER},
                {FlyBrain.CH_GAP, FlyBrain.CH_FALLING},
                {FlyBrain.CH_NOVELTY, FlyBrain.CH_BOREDOM},
        };
        for (int[] combo : typical) {
            float[] sc = new float[W];
            for (int pn : combo) sc[pn] = 1f;
            float[] t = withTarget(sc, innate);
            list.add(t); list.add(t);   // 加倍
        }
        return list;
    }

    /** 用侧角先天通路算出教师目标 */
    private static float[] withTarget(float[] sc, float[][] innate) {
        for (int mb = 0; mb < FlyBrain.MB_COUNT; mb++) {
            float t = 0;
            for (int pn = 0; pn < FlyBrain.PN_COUNT; pn++) {
                t += sc[pn] * innate[pn][mb];
            }
            sc[FlyBrain.PN_COUNT + mb] = t;
        }
        return sc;
    }

    /** 在训练集上算 MSE，作为预训练质量指标 */
    private static float evaluate(float[][] w, List<float[]> scenarios, boolean[][] pnToKc) {
        float[] kcAct = new float[KC_COUNT];
        float sum = 0;
        int n = 0;
        for (float[] sc : scenarios) {
            for (int kc = 0; kc < KC_COUNT; kc++) {
                float s = 0;
                for (int pn = 0; pn < FlyBrain.PN_COUNT; pn++) {
                    if (pnToKc[pn][kc]) s += sc[pn];
                }
                kcAct[kc] = s;
            }
            topK(kcAct, FlyBrain.K_WTA);

            for (int mb = 0; mb < FlyBrain.MB_COUNT; mb++) {
                float o = 0;
                for (int kc = 0; kc < KC_COUNT; kc++) {
                    if (kcAct[kc] > 0) o += w[kc][mb] * kcAct[kc];
                }
                float e = o - sc[FlyBrain.PN_COUNT + mb];
                sum += e * e;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** k-WTA（与 FlyBrain.topK 行为一致：只认 >0 的候选，保留各自原值） */
    private static void topK(float[] a, int k) {
        int n = a.length;
        boolean[] keep = new boolean[n];
        float[] work = a.clone();
        for (int pass = 0; pass < k; pass++) {
            int maxI = -1;
            float maxV = 0f;
            for (int i = 0; i < n; i++) {
                if (work[i] > maxV) { maxV = work[i]; maxI = i; }
            }
            if (maxI < 0) break;
            keep[maxI] = true;
            work[maxI] = -1f;
        }
        for (int i = 0; i < n; i++) if (!keep[i]) a[i] = 0f;
    }
}