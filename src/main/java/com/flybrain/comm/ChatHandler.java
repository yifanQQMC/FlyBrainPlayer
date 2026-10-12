package com.flybrain.comm;

import com.flybrain.FlyBrainPlayerMod;
import com.flybrain.brain.BrainPlayerEntity;
import com.flybrain.brain.Pretrain;
import com.flybrain.learn.KnowledgeBase;
import com.flybrain.learn.WikiClient;
import com.flybrain.profile.SkullKind;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * 交流入口：
 * - 玩家在聊天栏说「<AI名字> xxx」→ AI 回答
 * - 玩家对 AI 右键 → 打开对话（把常见问题列出来）
 * - /brain 命令：查知识库、让 AI 学指定词条、看统计
 */
@EventBusSubscriber(modid = FlyBrainPlayerMod.MOD_ID)
public class ChatHandler {

    static {
        // 确认这个类真的被 EventBusSubscriber 扫到了。
        // 之前 /brain 命令"莫名消失"，就是这个日志从来没有过的原因。
        FlyBrainPlayerMod.LOGGER.info("[Brain] ChatHandler 已注册 (game bus)");
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String raw = event.getRawText().trim();
        if (raw.isEmpty()) return;

        // 找出附近正在被叫的 AI
        for (var bot : player.level().getEntitiesOfClass(BrainPlayerEntity.class,
                player.getBoundingBox().inflate(16))) {
            String name = bot.getCustomName() == null
                    ? "" : bot.getCustomName().getString();
            // 名字匹配（去掉 #世代 后缀和颜色码）
            String clean = net.minecraft.ChatFormatting.stripFormatting(name);
            int hash = clean == null ? -1 : clean.indexOf('#');
            String shortName = hash > 0 ? clean.substring(0, hash).trim() : clean;
            if (shortName == null || shortName.isEmpty()) shortName = "AI";

            String lower = raw.toLowerCase();
            boolean addressed = lower.startsWith(shortName.toLowerCase())
                    || lower.startsWith("ai ")
                    || lower.contains(" " + shortName.toLowerCase())
                    || lower.contains("果蝇");

            if (addressed) {
                // 去掉称呼，只留问题
                String question = raw;
                for (String prefix : new String[]{shortName + " ", "ai ", "AI ", shortName, "果蝇 "}) {
                    if (question.startsWith(prefix)) {
                        question = question.substring(prefix.length()).trim();
                        break;
                    }
                }
                if (!question.isEmpty() && Dialogue.handleQuestion(bot, player, question)) {
                    // 玩家朝 AI 说话时，AI 转过来看着你
                    bot.getLookControl().setLookAt(player);
                    break;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("brain")
                .requires(s -> s.hasPermission(0))
                .then(Commands.literal("brains")
                        .executes(ctx -> {
                            var level = ctx.getSource().getLevel();
                            var center = ctx.getSource().getPosition();
                            var list = level.getEntitiesOfClass(
                                    BrainPlayerEntity.class,
                                    new net.minecraft.world.phys.AABB(center, center).inflate(32),
                                    e -> true);
                            var lines = new StringBuilder("\n§6════ AI 大脑一览 ════\n");
                            lines.append("§7预训练 loss §f")
                                    .append(String.format("%.4f", Pretrain.loss()))
                                    .append(" §8(耗时 ").append(Pretrain.millis()).append("ms)\n");
                            lines.append("§7神经解剖 §f物种共享 §8| §7突触强度 §f个体独立\n");
                            java.util.Set<String> fingerprints = new java.util.LinkedHashSet<>();
                            for (var bot : list) {
                                var b = bot.brain;
                                fingerprints.add(b.brainFingerprint());
                                lines.append(String.format(
                                        "§7#%-3d §f%s §8| 指纹 §e%s §8| 世代 §f%d"
                                                + " §8| 权重 §f%.2f%n",
                                        b.generation, bot.skull.displayName(),
                                        b.brainFingerprint(), b.generation,
                                        b.weightMagnitude()));
                            }
                            boolean allUnique = fingerprints.size() == list.size();
                            lines.append("\n§7共 ").append(list.size()).append(" 只大脑，指纹 ")
                                    .append(fingerprints.size()).append(" 个唯一");
                            lines.append(allUnique ? " §a✓ 全部互不相同"
                                    : " §c✗ 存在重复（不应发生）");
                            String out = lines.toString();
                            FlyBrainPlayerMod.LOGGER.info("[Brain] {}", out);
                            ctx.getSource().sendSuccess(() ->
                                    net.minecraft.network.chat.Component.literal(out), false);
                            return list.size();
                        }))
                .then(Commands.literal("skulls")
                        .executes(ctx -> {
                            FlyBrainPlayerMod.LOGGER.info("[Brain] 可用头颅：");
                            for (var k : SkullKind.values()) {
                                FlyBrainPlayerMod.LOGGER.info("[Brain]   {} → {} | {}",
                                        k.tag(), k.displayName(), k.description());
                            }
                            return SkullKind.values().length;
                        }))
                .then(Commands.literal("set")
                        .then(Commands.argument("skull",
                                        com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> {
                                    String tag = com.mojang.brigadier.arguments.StringArgumentType
                                            .getString(ctx, "skull");
                                    var kind = SkullKind.byTag(tag);
                                    var level = ctx.getSource().getLevel();
                                    var center = ctx.getSource().getPosition();
                                    // 目标：命令源附近的第一个 AI
                                    var list = level.getEntitiesOfClass(
                                            BrainPlayerEntity.class,
                                            new net.minecraft.world.phys.AABB(center, center).inflate(16),
                                            e -> true);
                                    if (list.isEmpty()) {
                                        ctx.getSource().sendFailure(
                                                net.minecraft.network.chat.Component.literal(
                                                        "§c附近 16 格内没有 AI 玩家"));
                                        return 0;
                                    }
                                    var bot = list.get(0);
                                    bot.applyProfile(kind);
                                    String msg = "§a已把 #" + bot.brain.generation + " 换成 "
                                            + kind.color() + kind.displayName()
                                            + " §8(学习率 " + String.format("%.3f",
                                                kind.personality().learningRate())
                                            + ")";
                                    FlyBrainPlayerMod.LOGGER.info("[Brain] {}", msg);
                                    ctx.getSource().sendSuccess(() ->
                                            net.minecraft.network.chat.Component.literal(msg), true);
                                    return 1;
                                })))
                .then(Commands.literal("ask")
                        .then(Commands.argument("topic",
                                        com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayer();
                                    String topic = com.mojang.brigadier.arguments.StringArgumentType
                                            .getString(ctx, "topic");
                                    var entry = WikiClient.get().queryBlocking(topic, 20);
                                    String msg = entry == null
                                            ? "§c没查到「" + topic + "」（可能没网或词条不存在）"
                                            : "§a学到 §e" + entry.title() + "§7 → " + entry.fact();
                                    // 打日志：无玩家时 RCON 也能看到结果
                                    FlyBrainPlayerMod.LOGGER.info("[Brain] {}", msg);
                                    if (p != null) {
                                        p.sendSystemMessage(Component.literal(msg));
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("askall")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayer();
                            if (p != null) {
                                p.sendSystemMessage(Component.literal(
                                        "§7目前知识库有 §e" + KnowledgeBase.shared().size() + " §7个词条。"));
                            }
                            return 1;
                        }))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            var kb = KnowledgeBase.shared();
                            // 无论有没有玩家都打日志，方便 RCON 自动化验证
                            FlyBrainPlayerMod.LOGGER.info("[Brain] 知识库共 {} 条:", kb.size());
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("§6=== 果蝇脑知识库（"
                                            + kb.size() + " 条）==="), false);
                            int n = 0;
                            for (var e : kbSnapshot()) {
                                FlyBrainPlayerMod.LOGGER.info("[Brain]   {} → {}",
                                        e.title(), e.fact());
                                ctx.getSource().sendSuccess(() ->
                                        Component.literal("§e- §f" + e.title()
                                                + " §7→ " + e.fact()), false);
                                if (++n >= 20) break;
                            }
                            return kb.size();
                        }))
                .then(Commands.literal("off").executes(ctx -> {
                    WikiClient.get().setEnabled(false);
                    ctx.getSource().sendSuccess(() ->
                            Component.literal("§c已关闭 AI 联网学习"), false);
                    return 1;
                }))
                .then(Commands.literal("on").executes(ctx -> {
                    WikiClient.get().setEnabled(true);
                    ctx.getSource().sendSuccess(() ->
                            Component.literal("§a已开启 AI 联网学习"), false);
                    return 1;
                }))
                .then(Commands.literal("say")
                        .then(Commands.argument("msg",
                                        com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(ctx -> {
                                    String msg = com.mojang.brigadier.arguments.StringArgumentType
                                            .getString(ctx, "msg");
                                    // 用数组包一层：lambda 捕获要求 effectively final
                                    final int[] spoken = {0};
                                    var level = ctx.getSource().getLevel();
                                    // 以命令源为中心，扫一大圈（命令源通常是玩家自己）
                                    var center = ctx.getSource().getPosition();
                                    for (var bot : level.getEntitiesOfClass(
                                            BrainPlayerEntity.class,
                                            new net.minecraft.world.phys.AABB(
                                                    center, center).inflate(64),
                                            e -> true)) {
                                        bot.speak(null, Component.literal("§7> §f" + msg));
                                        spoken[0]++;
                                    }
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("§a已对 " + spoken[0] + " 个 AI 说话"), false);
                                    return spoken[0];
                                }))));
    }

    private static java.util.List<KnowledgeBase.Entry> kbSnapshot() {
        // KnowledgeBase 没暴露遍历接口，这里用 answer 兜底不合适，
        // 直接通过反射不合适 —— 改为在 KnowledgeBase 加公开方法。
        return KnowledgeBase.shared().entriesSnapshot();
    }
}