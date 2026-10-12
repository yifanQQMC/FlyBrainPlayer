package com.flybrain.comm;

import com.flybrain.FlyBrainPlayerMod;
import com.flybrain.brain.BrainPlayerEntity;
import com.flybrain.learn.KnowledgeBase;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * 对话引擎：让 AI 玩家"说话"。
 *
 * 果蝇靠触角震动和翅膀扇动交流。这里给 AI 三条交流通路：
 *
 * 1. **知识问答** —— 玩家问「钻石在哪挖」，它查自己学过的知识库回答
 * 2. **自我介绍/闲聊** —— 问它是谁、它会什么，用状态数据生成回答
 * 3. **AI 之间交流** —— 两个 AI 靠近时互相"分享"知识（种群学习）
 */
public final class Dialogue {

    private Dialogue() {}

    /**
     * 处理玩家对 AI 的提问。命中知识库就给答案，否则触发联网学习。
     * @return 是否已经回复（避免重复）
     */
    public static boolean handleQuestion(BrainPlayerEntity bot, Player asker, String rawQuestion) {
        String q = rawQuestion == null ? "" : rawQuestion.trim();
        if (q.isEmpty()) return false;

        // ① 先查已学过的知识
        var hit = KnowledgeBase.shared().answer(q, 2.5);
        if (hit != null) {
            bot.speak(asker, Component.literal("§7> §f" + summarize(hit)));
            return true;
        }

        // ② 没查到 → 提取关键词去 wiki 学
        String topic = extractTopic(q);
        if (topic != null) {
            bot.speak(asker, Component.literal(
                    "§8[正在查阅 minecraft.wiki ...]"));
            bot.setCustomName(Component.literal("§7" + topic + "§r#"
                    + bot.brain.generation + " §8(研究中)"));

            com.flybrain.learn.WikiClient.get().learn(topic, entry -> {
                // 回到主线程执行（HTTP 回调在独立线程池）
                net.minecraft.server.MinecraftServer srv = bot.getServer();
                if (srv == null) return;
                srv.execute(() -> {
                    bot.setCustomName(Component.literal("果蝇脑 #" + bot.brain.generation));
                    bot.speak(asker, Component.literal(
                            "§a✓ 学到了！§f " + summarize(entry)));
                    bot.brain.reward(0.4f, FlyBrainBrainConst.CURIOSITY);
                    // 学到东西是有回报的，强化"去探索/去问"的倾向
                    if (bot.level() instanceof ServerLevel sl) {
                        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                                bot.getX(), bot.getY() + 2, bot.getZ(), 24, 0.4, 0.5, 0.4, 0.3);
                    }
                });
            });
            return true;
        }

        // ③ 纯闲聊
        bot.speak(asker, Component.literal("§7> §f" + chitchat(bot, q)));
        return true;
    }

    /** 把 wiki 摘要压成一句人话 */
    private static String summarize(KnowledgeBase.Entry e) {
        String s = e.summary().replaceAll("\\s+", " ").trim();
        // 取第一句，通常就是最关键的结论
        int dot = s.indexOf(". ");
        String first = dot > 0 && dot < 180 ? s.substring(0, dot + 1) : s;
        if (first.length() > 220) first = first.substring(0, 220) + "…";
        return first;
    }

    /** 从问题里提取要查的词条（去掉疑问词和礼貌用语） */
    public static String extractTopic(String question) {
        String q = question.toLowerCase(Locale.ROOT)
                .replaceAll("[?？。，,.!！:：;；\"'“”]", " ")
                .replaceAll("\\b(where|what|when|how|why|which|is|are|the|a|an|do|does|"
                        + "can|could|tell|about|of|for|in|on|at|to|and|or|please|"
                        + "在|哪|什么|怎么|如何|为|的|了|吗|呢|请|告诉|一下|介绍)\\b", " ")
                .trim();
        if (q.isBlank()) return null;
        // 词条名一般是首字母大写的专名，或单个英文词
        for (String w : question.replaceAll("[?？。，,.!！:：;；]", " ").split("\\s+")) {
            String t = w.trim();
            if (t.length() >= 3 && t.length() <= 24
                    && Character.isUpperCase(t.charAt(0))
                    && t.matches("[A-Za-z0-9_ ]+")) {
                return t;
            }
        }
        // 退而求其次：取清理后的第一个词
        String first = q.split("\\s+")[0];
        return first.length() >= 3 && first.length() <= 24 ? capitalize(first) : null;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String chitchat(BrainPlayerEntity bot, String q) {
        long gen = bot.brain.generation;
        int known = com.flybrain.learn.KnowledgeBase.shared().size();
        // 头颅不同 → 语气不同
        var sk = bot.skull;
        var p = sk.personality();

        if (q.contains("你是谁") || q.contains("名字") || q.contains("who")) {
            return switch (sk) {
                case ENDERMAN -> "……" + sk.displayName() + "。" + gen + " 代。"
                        + "我在读 minecraft.wiki。（已学 " + known + " 条）";
                case WITHER -> "我。" + sk.displayName() + "。" + gen + " 代。别挡路。";
                case CREEPER -> "嗨！我是" + sk.displayName() + "！" + gen
                        + " 代。别靠太近，我不太会控制。";
                case ZOMBIE -> "呃……我是" + sk.displayName() + "。" + gen + " 代。"
                        + "脑子不太好使，但拳头很硬。";
                case SKELETON -> "我是" + sk.displayName() + "。" + gen + " 代。保持距离。";
                case PIGLIN -> "我是" + sk.displayName() + "！" + gen
                        + " 代 Mining 和 building，我最擅长这个。";
                case SLIME -> "咕噜！" + sk.displayName() + "，" + gen
                        + " 代。什么都捡，" + known + " 条知识都挺有意思。";
                default -> "我是" + sk.displayName() + "，" + gen
                        + " 代。什么都学一点，知识库 " + known + " 条。";
            };
        }
        if (q.contains("你好") || q.contains("hi") || q.contains("hello")) {
            if (p.sociability() < 0.3f) {
                return "……嗯。（" + sk.displayName() + "不太爱说话）";
            }
            return "你好！我是" + sk.displayName() + " #" + gen
                    + "，已经学会 " + known + " 个词条。";
        }
        if (q.contains("性格") || q.contains("性��") || q.contains("personality")) {
            return String.format("学习率 %.3f，好奇度 %.2f，攻击性 %.2f，社交 %.2f。"
                            + "技能：%s", p.learningRate(), p.curiosity(),
                    p.aggression(), p.sociability(), sk.description());
        }
        if (q.contains("会什么") || q.contains("能力") || q.contains("can")) {
            StringBuilder sb = new StringBuilder("我擅长：");
            for (int i = 0; i < sk.skills().size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(sk.skills().get(i).label());
            }
            sb.append("。而且我能上 minecraft.wiki 查资料——学到的高度会让它真的改变我挖哪。");
            return sb.toString();
        }
        if (q.contains("学习") || q.contains("learn")) {
            return "我的突触权重会写进存档，第 " + gen + " 代。"
                    + "知识库全服共享，现在有 " + known + " 条。";
        }
        return switch (sk) {
            case WITHER -> "不听。";
            case SKELETON -> "（它指了指远处，似乎在示意保持距离）";
            case ENDERMAN -> "这个问题……我去 wiki 查一下再回答你。";
            default -> "没学过。问我「<词条名> 在哪挖」，我去 minecraft.wiki 查了就记住。";
        };
    }

    /** 两个 AI 相遇时互相交流：把各自知道的告诉对方（种群学习） */
    public static void aiGreet(BrainPlayerEntity a, BrainPlayerEntity b) {
        var factA = a.lastFact;
        var factB = b.lastFact;
        a.speak(null, Component.literal(
                "§8*触角震动* §7#" + b.brain.generation + " 号，你好。"));
        b.speak(null, Component.literal(
                "§8*触角震动* §7#" + a.brain.generation + " 号，你好。"));
        if (!factA.isBlank() && !factA.equals(factB)) {
            b.speak(null, Component.literal("§8↗ 分享: §7" + factA));
        }
    }
}