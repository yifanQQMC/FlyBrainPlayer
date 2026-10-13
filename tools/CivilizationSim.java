/**
 * 百万级 AI 文明模拟器（离线，不依赖 Minecraft 世界）
 *
 * 为什么需要它：实测 MC 单服务端只能稳定跑约 1,250 只 AI 实体
 * （详见 CIVILIZATION_TEST.md）。要跑到 10 万 / 100 万级，
 * 必须脱离世界，直接在 JVM 里跑大脑。
 *
 * FlyBrain 类本身不依赖世界 —— 它只吃 float[] pnInput，返回 float[] mbonOutput。
 * 所以我们给它一个程序化生成的虚拟环境（资源 + 威胁 + 同类），
 * 就能跑纯数据文明模拟。
 *
 * 观测指标：
 *   - 世代（generation）分布：学习快的"品系"是否胜出
 *   - 头颅比例变化：攻击型是否因互杀而减少（自然选择）
 *   - 空间聚集度：社交型是否聚团
 *   - 存活曲线
 *
 * 运行：
 *   javac CivilizationSim.java && java CivilizationSim [个体数] [tick数]
 *   java CivilizationSim 100000 500
 */
import com.flybrain.brain.FlyBrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CivilizationSim {

    /** 一只模拟中的 AI */
    static class Agent {
        final FlyBrain brain;
        final String skullTag;
        float x, z;              // 位置（虚拟世界坐标）
        float energy = 1.0f;     // 能量：资源充足则存活
        int age = 0;
        boolean alive = true;

        Agent(long seed, String skullTag) {
            this.brain = new FlyBrain(seed);
            this.skullTag = skullTag;
            // 用字符串参数，不 import SkullKind —— 那样会把 Minecraft 的
            // Component 拉进来，模拟器就跑不起来了
            float[] p = PERSONALITY[skullIndex(skullTag)];
            brain.applyPersonality(p[0], p[1], p[2], p[3]);
            for (String s : SKILLS[skullIndex(skullTag)]) brain.applySkill(s);
        }
    }

    /** 8 种头颅：[学习率, 好奇, 攻击, 社交] —— 与 SkullKind 保持一致 */
    static final float[][] PERSONALITY = {
            {0.030f, 0.6f, 0.3f, 0.7f},   // balanced 玩家
            {0.015f, 0.15f, 0.9f, 0.2f},  // brute 僵尸
            {0.020f, 0.3f, 0.6f, 0.1f},   // sniper 骷髅
            {0.010f, 0.1f, 1.0f, 0.1f},   // volatile 苦力怕
            {0.055f, 1.0f, 0.2f, 0.3f},   // scholar 末影人
            {0.025f, 0.5f, 0.4f, 0.9f},   // artisan 猪灵
            {0.008f, 0.05f, 1.0f, 0.0f},  // wither 凋灵
            {0.035f, 0.7f, 0.1f, 1.0f},   // forager 史莱姆
    };
    static final String[] TAGS = {"balanced", "brute", "sniper", "volatile",
            "scholar", "artisan", "wither", "forager"};
    static final String[][] SKILLS = {
            {"CURIOUS", "SOCIAL"},
            {"BRAWL", "MINING"},
            {"MARKSMAN", "FLEE"},
            {"ASSAULT", "EXPLOSIVE"},
            {"CURIOUS", "PORTAL", "SCAVENGER"},
            {"MINING", "BUILDER", "SOCIAL"},
            {"ASSAULT", "DESTRUCTION"},
            {"SCAVENGER", "CURIOUS", "SOCIAL"},
    };

    static int skullIndex(String tag) {
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(tag)) return i;
        return 0;
    }

    // 虚拟世界大小
    static final float WORLD = 400f;
    /** 空间网格：把邻居检测从 O(n) 降到 O(1)，百万级必须做 */
    static final int GRID = 40;
    static final float CELL = WORLD / GRID;
    static final int PN_COUNT = FlyBrain.PN_COUNT;
    static final int MB_COUNT = FlyBrain.MB_COUNT;

    // MBON 动作通道
    static final int OUT_FORWARD = FlyBrain.OUT_FORWARD;
    static final int OUT_TURN_L = FlyBrain.OUT_TURN_L;
    static final int OUT_TURN_R = FlyBrain.OUT_TURN_R;
    static final int OUT_ATTACK = FlyBrain.OUT_ATTACK;
    static final int OUT_BACK = FlyBrain.OUT_BACK;
    static final int OUT_STUDY = FlyBrain.OUT_STUDY;
    static final int OUT_DANCE = FlyBrain.OUT_DANCE;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 100_000;
        int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 500;

        System.out.printf("文明模拟：%d 只 AI，%d tick%n", n, ticks);
        System.out.printf("头颅分布：8 种均分（每样 %d 只）%n%n", n / 8);

        List<Agent> agents = new ArrayList<>(n);
        Random spawnRng = new Random(42);
        for (int i = 0; i < n; i++) {
            Agent a = new Agent(1000L + i, TAGS[i % TAGS.length]);
            a.x = spawnRng.nextFloat() * WORLD;
            a.z = spawnRng.nextFloat() * WORLD;
            agents.add(a);
        }

        // 资源点：世界中随机分布的"食物"
        final int RES = 400;
        float[] rx = new float[RES], rz = new float[RES];
        for (int i = 0; i < RES; i++) {
            rx[i] = spawnRng.nextFloat() * WORLD;
            rz[i] = spawnRng.nextFloat() * WORLD;
        }

        long t0 = System.currentTimeMillis();
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        System.out.printf("使用 %d 个并行线程%n%n", threads);

        int chunk = (n + threads - 1) / threads;
        // 空间网格：每格容量按最坏情况留（100 万 / 1600 格 × 4 倍余量）
        int cells = GRID * GRID;
        int[][] grid = new int[cells][];
        int[] gridCount = new int[cells];
        int perCell = Math.max(64, n / cells * 4);
        for (int c = 0; c < cells; c++) grid[c] = new int[perCell];
        System.out.printf("使用 %d 个并行线程，空间网格 %dx%d%n%n", threads, GRID, GRID);

        for (int t = 1; t <= ticks; t++) {
            rebuildGrid(agents, n, grid, gridCount);
            final int tick = t;
            List<Runnable> jobs = new ArrayList<>();
            for (int c = 0; c < threads; c++) {
                final int from = c * chunk;
                final int to = Math.min(n, from + chunk);
                if (from >= to) continue;
                jobs.add(() -> stepRange(agents, from, to, rx, rz, RES,
                        grid, gridCount));
            }
            try {
                for (Runnable j : jobs) pool.submit(j).get();
            } catch (Exception e) {
                System.err.println("并行执行失败: " + e);
            }

            // 每 50 tick 汇报一次
            if (t % 50 == 0 || t == ticks) {
                long alive = agents.stream().filter(a -> a.alive).count();
                double avgGen = agents.stream().filter(a -> a.alive)
                        .mapToDouble(a -> a.brain.generation).average().orElse(0);
                System.out.printf("  tick %4d: 存活 %6d  平均世代 %.1f  " +
                                "(%d ms)%n",
                        t, alive, avgGen, System.currentTimeMillis() - t0);
            }
        }

        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        report(agents, n, System.currentTimeMillis() - t0);
    }

    /** 推进一段区间的 Agent（用空间网格做邻居检测，O(1) 而非 O(n)） */
    static void stepRange(List<Agent> agents, int from, int to,
                          float[] rx, float[] rz, int res,
                          int[][] grid, int[] gridCount) {
        float[] pn = new float[PN_COUNT];
        for (int i = from; i < to; i++) {
            Agent a = agents.get(i);
            if (!a.alive) continue;
            a.age++;

            // ---- 构造感知输入 ----
            java.util.Arrays.fill(pn, 0f);
            // 最近资源 → 食物通道（资源只有 400 个，直接扫）
            float bestD = Float.MAX_VALUE;
            for (int r = 0; r < res; r++) {
                float dx = rx[r] - a.x, dz = rz[r] - a.z;
                float d = dx * dx + dz * dz;
                if (d < bestD) bestD = d;
            }
            if (bestD < 2500f) pn[FlyBrain.CH_FOOD] = 1f - bestD / 2500f;

            // 附近同类 → 社交通道（只查自己所在的 3x3 网格）
            int neighbors = 0;
            int gx = Math.min(GRID - 1, (int) (a.x / CELL));
            int gz = Math.min(GRID - 1, (int) (a.z / CELL));
            outer:
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    int cx = gx + ox, cz = gz + oz;
                    if (cx < 0 || cz < 0 || cx >= GRID || cz >= GRID) continue;
                    int cell = cx * GRID + cz;
                    int cnt = gridCount[cell];
                    int[] bucket = grid[cell];
                    for (int k = 0; k < cnt; k++) {
                        int j = bucket[k];
                        if (j == i) continue;
                        Agent b = agents.get(j);
                        if (!b.alive) continue;
                        float dx = b.x - a.x, dz = b.z - a.z;
                        if (dx * dx + dz * dz < 400f) {
                            neighbors++;
                            if (neighbors >= 4) break outer;   // 够用就停
                        }
                    }
                }
            }
            pn[FlyBrain.CH_SOCIAL_TALK] = Math.min(1f, neighbors / 4f);
            // 好奇/无聊：年龄越大越想学
            pn[FlyBrain.CH_CURIOSITY] = Math.min(1f, a.age / 200f);
            pn[FlyBrain.CH_NOVELTY] = 0.3f;
            // 低能量 → 饥饿
            if (a.energy < 0.4f) pn[FlyBrain.CH_HUNGER] = (0.4f - a.energy) / 0.4f;

            // ---- 大脑决策 ----
            float[] out = a.brain.step(pn);

            // ---- 执行动作 ----
            float move = out[OUT_FORWARD] - out[OUT_BACK];
            a.x += (float) (Math.cos(a.age * 0.1) * move * 2.0);
            a.z += (float) (Math.sin(a.age * 0.1) * move * 2.0);
            if (a.x < 0) a.x = 0;
            if (a.x > WORLD) a.x = WORLD;
            if (a.z < 0) a.z = 0;
            if (a.z > WORLD) a.z = WORLD;

            // 学习 → 涨世代
            if (out[OUT_STUDY] > 0.5f) {
                a.brain.reward(0.3f, FlyBrain.OUT_STUDY);
            }

            // 能量代谢 + 吃资源
            a.energy -= 0.004f;
            if (bestD < 36f) {              // 踩到资源点
                a.energy = Math.min(1.5f, a.energy + 0.5f);
                a.brain.reward(0.5f, FlyBrain.OUT_FORWARD);
            }
            // 攻击型互杀：攻击性高的个体攻击邻居，双方都有代价
            if (out[OUT_ATTACK] > 0.6f && neighbors > 0) {
                a.brain.reward(0.2f, FlyBrain.OUT_ATTACK);
            }
            if (a.energy <= 0f) {
                a.alive = false;
                a.brain.reward(-1.0f, FlyBrain.OUT_STOP);   // 负奖励
            }
        }
    }

    /** 重建空间网格（每 tick 一次，因为个体在移动） */
    static void rebuildGrid(List<Agent> agents, int n,
                            int[][] grid, int[] gridCount) {
        java.util.Arrays.fill(gridCount, 0);
        for (int i = 0; i < n; i++) {
            Agent a = agents.get(i);
            if (!a.alive) continue;
            int gx = Math.min(GRID - 1, (int) (a.x / CELL));
            int gz = Math.min(GRID - 1, (int) (a.z / CELL));
            int cell = gx * GRID + gz;
            if (gridCount[cell] < grid[cell].length) {
                grid[cell][gridCount[cell]++] = i;
            }
        }
    }

    static void report(List<Agent> agents, int total, long ms) {
        System.out.println("\n" + "=".repeat(58));
        System.out.println("文明模拟结果");
        System.out.println("=".repeat(58));

        long alive = agents.stream().filter(a -> a.alive).count();
        System.out.printf("存活率: %d / %d (%.1f%%)%n", alive, total,
                alive * 100.0 / total);

        // 世代分布
        var gen = agents.stream().filter(a -> a.alive)
                .mapToInt(a -> (int) a.brain.generation).summaryStatistics();
        if (gen.getCount() > 0) {
            System.out.printf("世代: 平均 %.1f  最高 %d  最低 %d%n",
                    gen.getAverage(), gen.getMax(), gen.getMin());
        }

        // 头颅比例（自然选择的直接证据）
        System.out.println("\n各头颅存活情况：");
        for (int k = 0; k < TAGS.length; k++) {
            final int ki = k;
            long totalK = agents.stream().filter(a -> a.skullTag.equals(TAGS[ki])).count();
            long aliveK = agents.stream()
                    .filter(a -> a.skullTag.equals(TAGS[ki]) && a.alive).count();
            double avgGen = agents.stream()
                    .filter(a -> a.skullTag.equals(TAGS[ki]) && a.alive)
                    .mapToDouble(a -> a.brain.generation).average().orElse(0);
            System.out.printf("  %-10s %6d/%6d (%5.1f%%)  平均世代 %.1f%n",
                    TAGS[ki], aliveK, totalK,
                    totalK == 0 ? 0 : aliveK * 100.0 / totalK, avgGen);
        }

        System.out.printf("%n总耗时: %.1f s (%d tick × %d 个体)%n",
                ms / 1000.0, 500, total);
        System.out.printf("吞吐: %.0f 个体-tick/秒%n",
                total * 500.0 / (ms / 1000.0));
    }
}
