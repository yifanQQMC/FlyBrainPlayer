package com.flybrain.brain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * AI 玩家的随机英文名分配器。
 *
 * 为什么需要：玩家要能在聊天框直接喊名字下指令，比如
 * {@code ALEX，去探索一下前面}。中文头颅名（"末影人头颅"）不方便喊，
 * 而且没有区分度 —— 一群"末影人头颅"站在那，你叫哪个？
 *
 * 设计：
 *   - 名字池取 Minecraft 风格的英文短名（ALEX / STEVE / NICK ...）
 *   - **全服去重**：同一个世界里的 AI 不重名，否则喊一声两个都响应
 *   - 池用完才允许重名（加数字后缀）
 *
 * 纯 Java，无 Minecraft 依赖 —— 离线模拟器也能用。
 */
public final class NameGen {

    private NameGen() {}

    /** 名字池：MC 默认皮肤名 + 常见英文短名，共 60 个 */
    public static final String[] POOL = {
            // Minecraft 默认皮肤名
            "ALEX", "STEVE", "ARI", "KAI", "MAKENA", "Noor", "SUNNY", "ZURI",
            "EFE", "JONAH", "MIMI", "JUANA",
            // 常见英文短名
            "NICK", "JAKE", "LIAM", "NOAH", "ETHAN", "MASON", "LOGAN", "LUCAS",
            "OWEN", "RYAN", "LEVI", "JACK", "CODY", "DYLAN", "BLAKE", "CHASE",
            "ELLA", "GRACE", "MAYA", "ZOE", "LILY", "AVA", "IVY", "NORA",
            "RUBY", "SADIE", "TESS", "WILLA", "ANNA", "BETH", "CLARA", "DAISY",
            "FINN", "GUS", "HANK", "IZZY", "JAZZ", "KIT", "LEO", "MAX",
            "NATE", "OZZY", "PIP", "QUINN", "REX", "SAM", "THEO", "UMBER",
            "VIOLET", "WALT", "XANDER", "YURI", "ZANE", "ARCHIE", "BENNY", "CHARLIE"
    };

    /** 全服已用名字（静态，跨实例共享） */
    private static final Set<String> USED = new HashSet<>();
    private static final Random RNG = new Random();

    /**
     * 分配一个未被占用的英文名。
     * 池耗尽时加数字后缀（ALEX2、ALEX3...）。
     */
    public static synchronized String next() {
        // 先打乱池，避免相邻生成的 AI 总拿相近的名字
        List<String> shuffled = new ArrayList<>(List.of(POOL));
        java.util.Collections.shuffle(shuffled, RNG);
        for (String n : shuffled) {
            if (USED.add(n)) return n;
        }
        // 池用完：数字后缀
        for (int i = 2; i < 1000; i++) {
            for (String base : shuffled) {
                String candidate = base + i;
                if (USED.add(candidate)) return candidate;
            }
        }
        // 极端情况（60000+ AI）：直接给随机串
        String fallback = "BOT" + (1000 + RNG.nextInt(9000));
        USED.add(fallback);
        return fallback;
    }

    /** 释放名字（AI 死亡/移除时调用，避免名字泄漏） */
    public static synchronized void release(String name) {
        if (name != null) USED.remove(name);
    }

    /**
     * 占用一个指定名字（读档时用）。
     * 已存在返回 false（说明重名了，极少见 —— 老存档可能撞新车）。
     */
    public static synchronized boolean reserve(String name) {
        if (name == null || name.isEmpty()) return false;
        return USED.add(name);
    }

    /** 当前已分配数量 */
    public static synchronized int usedCount() {
        return USED.size();
    }

    /** 清空（世界重置/测试用） */
    public static synchronized void reset() {
        USED.clear();
    }
}
