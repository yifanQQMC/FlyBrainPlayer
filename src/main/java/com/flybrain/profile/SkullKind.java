package com.flybrain.profile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 头颅类型 = AI 玩家的"物种/职业"定义。
 *
 * 每种头颅给 AI 三样东西：
 *   ① 技能（Skill）：一组可执行的本能通路，决定它擅长什么
 *   ② 性格（Personality）：影响学习率、好奇度、攻击性、社交意愿
 *   ③ 皮肤（Skin）：用哪张玩家皮肤
 *
 * 设计参考果蝇的**品系差异**（Drosophila melanogaster 不同品系有不同行为特征）：
 * 战斗品系攻击性高、觅食品系趋食性强、工蜂型品系社交活跃。
 * 头颅就是这个"品系选择器"。
 */
public enum SkullKind {

    /** 玩家头颅（默认）：好奇心强、爱学习、均衡 */
    PLAYER(
            "玩家头颅", "balanced",
            "#7fd6ff", "player",
            List.of(Skill.CURIOUS, Skill.SOCIAL),
            new Personality(0.030f, 0.6f, 0.3f, 0.7f),
            "什么都要学一点，最全面的AI"),

    /** 僵尸头颅：攻击型，高攻击性、爱战斗、不怕疼 */
    ZOMBIE(
            "僵尸头颅", "brute",
            "#5aa84a", "zombie",
            List.of(Skill.BRAWL, Skill.MINING),
            new Personality(0.015f, 0.15f, 0.9f, 0.2f),
            "见怪就打，破坏力强，但不太爱学习"),

    /** 骷髅头颅：远程/精准型，谨慎、爱躲、讨厌靠近 */
    SKELETON(
            "骷髅头颅", "sniper",
            "#d0d0d0", "skeleton",
            List.of(Skill.MARKSMAN, Skill.FLEE),
            new Personality(0.020f, 0.3f, 0.6f, 0.1f),
            "保持距离，见危险就跑，讨厌被打扰"),

    /** 苦力怕头颅：爆破型，暴躁、可能自爆 */
    CREEPER(
            "苦力怕头颅", "volatile",
            "#4ec26e", "creeper",
            List.of(Skill.ASSAULT, Skill.EXPLOSIVE),
            new Personality(0.010f, 0.1f, 1.0f, 0.1f),
            "脾气爆炸，离得太近就自爆"),

    /** 末影人头颅：研究/学者型，极高好奇、学习最快、社交低 */
    ENDERMAN(
            "末影人头颅", "scholar",
            "#a020f0", "enderman",
            List.of(Skill.CURIOUS, Skill.PORTAL, Skill.SCAVENGER),
            new Personality(0.055f, 1.0f, 0.2f, 0.3f),
            "学得最快，什么都想知道，但不太爱说话"),

    /** 猪灵头颅：工匠型，擅长挖矿建造、爱交流 */
    PIGLIN(
            "猪灵头颅", "artisan",
            "#e0a878", "piglin",
            List.of(Skill.MINING, Skill.BUILDER, Skill.SOCIAL),
            new Personality(0.025f, 0.5f, 0.4f, 0.9f),
            "最爱挖矿和盖房子，脾气好"),

    /** 凋灵头颅：毁灭型，极端暴力、迟钝 */
    WITHER(
            "凋灵头颅", "wither",
            "#303030", "wither",
            List.of(Skill.ASSAULT, Skill.DESTRUCTION),
            new Personality(0.008f, 0.05f, 1.0f, 0.0f),
            "纯粹的破坏机器，几乎不学习"),

    /** 史莱姆头颅：采集型，效率极高、爱收集、极友善 */
    SLIME(
            "史莱姆头颅", "forager",
            "#7fe07f", "slime",
            List.of(Skill.SCAVENGER, Skill.CURIOUS, Skill.SOCIAL),
            new Personality(0.035f, 0.7f, 0.1f, 1.0f),
            "拾荒小能手，见什么捡什么");

    // ---- 元数据 ----
    private final String displayName;
    private final String tag;             // 存档与物品 ID
    private final String color;           // 聊天栏颜色
    private final String texture;         // 皮肤文件（不含 .png）
    private final List<Skill> skills;
    private final Personality personality;
    private final String description;

    SkullKind(String displayName, String tag, String color, String texture,
              List<Skill> skills, Personality personality, String description) {
        this.displayName = displayName;
        this.tag = tag;
        this.color = color;
        this.texture = texture;
        this.skills = List.copyOf(skills);
        this.personality = personality;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String tag() { return tag; }
    public String color() { return color; }
    public String texture() { return texture; }
    public List<Skill> skills() { return skills; }
    public Personality personality() { return personality; }
    public String description() { return description; }

    public Component display() {
        return Component.literal(color + displayName);
    }

    /** 皮肤贴图资源路径 */
    public String texturePath() {
        return "flybrainplayer:textures/entity/" + texture + ".png";
    }

    public static SkullKind byTag(String tag) {
        for (SkullKind k : values()) {
            if (k.tag.equalsIgnoreCase(tag)) return k;
        }
        return PLAYER;
    }

    /** 技能枚举 */
    public enum Skill {
        BRAWL("近战", "§c⚔"),
        MARKSMAN("远程", "§7➶"),
        FLEE("逃跑", "§a🏃"),
        MINING("挖矿", "§6⛏"),
        BUILDER("建造", "§e🏠"),
        SCAVENGER("拾荒", "§d💎"),
        CURIOUS("好奇", "§b❓"),
        SOCIAL("社交", "§f💬"),
        ASSAULT("强攻", "§4⚔"),
        EXPLOSIVE("自爆", "§2☀"),
        DESTRUCTION("破坏", "§8💥"),
        PORTAL("传送", "§5✦");

        private final String label;
        private final String icon;

        Skill(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        public String label() { return label; }
        public String icon() { return icon; }
    }

    /**
     * 性格参数。
     *
     * @param learningRate  Hebbian 学习率——越高学得越快也越容易忘
     * @param curiosity      好奇度基线——影响去查 wiki 的频率
     * @param aggression     攻击性——影响攻击通路的先天权重
     * @param sociability    社交意愿——影响主动搭话的概率
     */
    public record Personality(
            float learningRate,
            float curiosity,
            float aggression,
            float sociability) {

        public Map<String, Float> asMap() {
            Map<String, Float> m = new LinkedHashMap<>();
            m.put("learningRate", learningRate);
            m.put("curiosity", curiosity);
            m.put("aggression", aggression);
            m.put("sociability", sociability);
            return m;
        }

        public static Personality fromNbt(CompoundTag tag) {
            if (tag == null || tag.isEmpty()) return PLAYER.personality;
            return new Personality(
                    tag.getFloat("lr"), tag.getFloat("curiosity"),
                    tag.getFloat("aggression"), tag.getFloat("sociability"));
        }

        public CompoundTag toNbt() {
            var t = new CompoundTag();
            t.putFloat("lr", learningRate);
            t.putFloat("curiosity", curiosity);
            t.putFloat("aggression", aggression);
            t.putFloat("sociability", sociability);
            return t;
        }
    }
}