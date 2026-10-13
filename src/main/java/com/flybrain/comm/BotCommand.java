package com.flybrain.comm;

import java.util.Locale;

/**
 * 玩家指令解析器：把聊天框里的自然语言变成 AI 能执行的动作。
 *
 * 支持格式：
 *   {@code ALEX，去探索一下前面}
 *   {@code ALEX 去探索}
 *   {@code alex, come here}
 *   {@code ALEX，停下}
 *
 * 为什么不做成"完整 NLP"：这是游戏指令，不是对话。
 * 关键词匹配 + 容错（忽略标点/语气词）足够，而且可预测 ——
 * 玩家说"去探索"就该去探索，不会被模型自由发挥带偏。
 *
 * 纯 Java，无 Minecraft 依赖，可单测。
 */
public final class BotCommand {

    private BotCommand() {}

    /** 动作类型 */
    public enum Action {
        /** 向面朝方向走一段（探索） */
        EXPLORE,
        /** 向玩家走来 */
        COME,
        /** 停下不动 */
        STOP,
        /** 攻击附近怪物 */
        ATTACK,
        /** 跳一下 */
        JUMP,
        /** 四处游走 */
        WANDER,
        /** 跟玩家打招呼 */
        GREET,
        /** 不是指令（普通对话） */
        NONE
    }

    /** 解析结果 */
    public record Parsed(String botName, Action action, String arg) {}

    /**
     * 解析玩家消息。
     *
     * @param raw 玩家原文
     * @return 识别出指令返回 Parsed；否则返回 action=NONE
     */
    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed(null, Action.NONE, null);
        }
        String text = raw.trim();

        // 1. 拆出"名字 + 指令"：支持 ", " "，" " " " 分隔
        String botName = null;
        String body = text;
        for (String sep : new String[]{", ", "，", ",", "， "}) {
            int i = text.indexOf(sep);
            if (i > 0 && i < 20) {          // 名字不会超过 20 字符
                String head = text.substring(0, i).trim();
                // 头部必须是单个词（英文名），否则不是"点名"格式
                if (head.matches("[A-Za-z][A-Za-z0-9]{0,15}")) {
                    botName = head;
                    body = text.substring(i + sep.length()).trim();
                    break;
                }
            }
        }
        // 没分隔符时，尝试 "NAME 指令"（空格分隔，首词是英文名）
        if (botName == null) {
            int sp = text.indexOf(' ');
            if (sp > 0 && sp < 20) {
                String head = text.substring(0, sp);
                if (head.matches("[A-Za-z][A-Za-z0-9]{0,15}")) {
                    botName = head;
                    body = text.substring(sp + 1).trim();
                }
            }
        }
        if (botName == null || body.isEmpty()) {
            return new Parsed(null, Action.NONE, null);
        }

        return new Parsed(botName, matchAction(body), body);
    }

    /** 把指令正文映射到动作 */
    private static Action matchAction(String body) {
        String s = body.toLowerCase(Locale.ROOT)
                .replaceAll("[，。！？、~\\s]+", "");   // 去标点和空格，容错

        // —— 探索类（"去探索一下前面" / "去看看" / "advance"）——
        if (containsAny(s, "探索", "去看看", "前面", "前方", "advance", "explore",
                "goto", " scout")) {
            return Action.EXPLORE;
        }
        // —— 过来（"过来" / "来" / "come" / "follow"）——
        if (containsAny(s, "过来", "来找我", "come", "follow", "here")) {
            return Action.COME;
        }
        // —— 停下（"停下" / "站住" / "stop" / "wait"）——
        if (containsAny(s, "停下", "站住", "别动", "stop", "wait", "halt")) {
            return Action.STOP;
        }
        // —— 攻击（"攻击" / "打" / "attack" / "fight"）——
        if (containsAny(s, "攻击", "去打", "揍", "attack", "fight", "kill")) {
            return Action.ATTACK;
        }
        // —— 跳（"跳" / "jump"）——
        if (containsAny(s, "跳", "jump", "hop")) {
            return Action.JUMP;
        }
        // —— 游走（"走走" / "逛逛" / "wander" / "walk"）——
        if (containsAny(s, "走走", "逛逛", "游走", "wander", "walk", "roam")) {
            return Action.WANDER;
        }
        // —— 打招呼（"打招呼" / "问好" / "greet" / "hi" / "hello"）——
        if (containsAny(s, "打招呼", "问好", "欢迎", "greet", "hello", "hi")) {
            return Action.GREET;
        }
        return Action.NONE;
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }
}
