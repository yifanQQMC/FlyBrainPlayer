package com.flybrain.learn;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 玩家的知识库（长期记忆）。
 *
 * 果蝇的中央复合体里有"突触可塑性"支撑的长期记忆。我们把它抽象成
 * 一个条目化知识库：每个词条包含标题、摘要、关键词、以及从摘要里
 * 抽取的"可执行事实"（如"钻石出现在 y=-59 层"）。
 *
 * 知识库是**共享的**（全服一份，写在存档目录），这样新生成的 AI 玩家
 * 一出生就继承了前辈们学到的东西——相当于种群层面的学习。
 */
public class KnowledgeBase {

    /** 单条知识 */
    public record Entry(
            String title,        // 条目标题，如 "Diamond"
            String summary,      // 摘要正文（已截断）
            List<String> keywords,   // 关键词，回答时做匹配
            String fact,         // 提炼出的可执行事实，喂给决策层
            long timestamp       // 学到的时间
    ) {}

    private static final int MAX_ENTRIES = 512;
    private static final int SUMMARY_LIMIT = 600;

    /** 插入顺序 = 访问热度（用 LinkedHashMap 做 LRU） */
    private final Map<String, Entry> entries = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 全服共享的那一份 */
    private static KnowledgeBase shared;

    public static KnowledgeBase shared() {
        if (shared == null) shared = new KnowledgeBase();
        return shared;
    }

    public int size() {
        return entries.size();
    }

    public boolean has(String title) {
        return entries.containsKey(normalize(title));
    }

    public Entry get(String title) {
        return entries.get(normalize(title));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** 插入/更新一条知识。学习到已知的词条会刷新摘要（wiki 可能更新了）。 */
    public void learn(Entry e) {
        String key = normalize(e.title());
        if (key.isEmpty()) return;
        Entry stored = new Entry(
                e.title(),
                truncate(e.summary()),
                e.keywords() == null ? List.of() : List.copyOf(e.keywords()),
                e.fact() == null ? "" : e.fact(),
                e.timestamp());
        entries.put(key, stored);
        // 超出上限就淘汰最早学的
        while (entries.size() > MAX_ENTRIES) {
            var it = entries.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * 从一段 wiki 摘要里抽取"可执行事实"。
     *
     * 这是知识真正影响行为的关键：AI 不是把百科存下来就算学会，
     * 而是要把文本压缩成能改变决策的一句话。
     * 例："Diamonds generate between y=-59 and y=-16"
     *   → fact = "diamond:y=-59..-16"
     * 这样 MiningGoal 就能在 y<-16 时优先找钻石。
     */
    public static String extractFact(String title, String text) {
        String t = text == null ? "" : text;
        String lower = t.toLowerCase(Locale.ROOT);
        String base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");

        // ① 高度区间 —— wiki 常见写法：
        //    "from y=-59 to y=-16"   "between y=1 and y=320"   "y=-59 to -16"
        //
        // 关键：必须要求区间前面有 y，否则 "veins of 1-8"（矿脉大小）
        // 会被误当成高度区间。这正是第一版会抽出错误高度的原因。
        //
        // 分两步而不是一条大正则：
        //   "y=-59" / "y=16"  必带 y=
        //   "y levels 1 to 340" 这种变体 wiki 较少见，用宽松分支兜底
        // 字符类只用 ASCII，避免源文件编码问题把连接符搞坏。
        java.util.regex.Matcher range = java.util.regex.Pattern
                .compile("y\\s*(?:levels?\\s*)?=\\s*(-?\\d+)"
                        + "\\s*(?:to|and|through|-)\\s*(?:y\\s*)?=?\\s*(-?\\d+)")
                .matcher(lower);
        while (range.find()) {
            try {
                int a = Integer.parseInt(range.group(1));
                int b = Integer.parseInt(range.group(2));
                if (a > b) { int t2 = a; a = b; b = t2; }
                if (a <= 0 && b <= 320 && b >= -64) {     // 只采信合理的 Y 值
                    String fact = base + ":y=" + a + ".." + b;
                    // 再确认这条区间附近真的在讲"生成"，避免抓错段落
                    // （wiki 正文里常有生物刷怪高度、机械方块位置等无关 Y 值）
                    if (mentionsGeneration(lower, range.start())) return fact;
                }
            } catch (NumberFormatException ignored) {
                // 继续往下走
            }
        }

        // ② 单个高度："below y=0" / "at y=-59"
        java.util.regex.Matcher single = java.util.regex.Pattern
                .compile("y\\s*(?:=|coordinate|level)?s?\\s*(-?\\d+)").matcher(lower);
        if (single.find()) {
            return base + ":y=" + single.group(1);
        }

        // ② 合成配方
        if (lower.contains("crafted") || lower.contains("recipe") || lower.contains("crafting")) {
            return base + ":craftable";
        }

        // ③ 熔炼/烧炼
        if (lower.contains("smelt") || lower.contains("furnace") || lower.contains("blasting")) {
            return base + ":smeltable";
        }

        // ④ 稀有度/危险度 → 影响是否值得冒险去取
        if (lower.contains("hostile mobs") || lower.contains("spawns") || lower.contains("y level 0")) {
            return base + ":dangerous";
        }

        // ⑤ 生物：食肉/可驯服
        if (lower.contains("tameable") || lower.contains("breed")) {
            return base + ":tameable";
        }
        if (lower.contains("drops ") || lower.contains("drops raw")) {
            return base + ":drops";
        }

        return base + ":known";
    }

    /**
     * 判断某个 Y 区间是否在描述"生成位置"。
     *
     * wiki 正文里 Y 坐标满天飞：矿物生成区间、生物刷怪高度、机械方块所在高度…
     * 只有和"generates / veins / found / appears"这类词挨在一起的才是我们要的。
     * 做法：看匹配位置**之后 120 字符**内有没有生成类词汇。
     */
    private static boolean mentionsGeneration(String lower, int matchAt) {
        int from = Math.max(0, matchAt);
        int to = Math.min(lower.length(), matchAt + 120);
        String window = lower.substring(from, to);
        return window.contains("generate") || window.contains("vein")
                || window.contains("appears") || window.contains("found")
                || window.contains("exist") || window.contains("natural")
                || window.contains("spawns in") || window.contains(" ore");
    }

    /** 把一段文本切成检索关键词（去停用词、去重、限长） */
    public static List<String> extractKeywords(String text) {
        if (text == null || text.isEmpty()) return List.of();
        var stop = Set.of("the", "a", "an", "and", "or", "in", "on", "of", "to", "is",
                "are", "it", "this", "that", "for", "with", "as", "by", "at", "from",
                "be", "can", "will", "which", "when", "into", "than", "then", "them",
                "they", "have", "has", "had", "but", "not", "you", "your", "more");
        var seen = new java.util.LinkedHashSet<String>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() < 4 || stop.contains(w)) continue;
            seen.add(w);
            if (seen.size() >= 16) break;
        }
        return new ArrayList<>(seen);
    }

    // 极简 Set（避免 import 冲突）
    private static final class Set {
        static java.util.Set<String> of(String... s) {
            return java.util.Set.of(s);
        }
    }

    /**
     * 回答一个问题：在知识库里做关键词打分，返回最相关的条目。
     * 命中数越多权重越高，标题全匹配直接置顶。
     */
    public Entry answer(String question, double minScore) {
        if (question == null || question.isBlank()) return null;
        String q = question.toLowerCase(Locale.ROOT);
        String[] qWords = q.split("[^a-z0-9]+");

        Entry best = null;
        double bestScore = minScore;

        synchronized (entries) {
            for (Entry e : entries.values()) {
                double score = 0;
                // 标题命中权重最高
                String title = e.title().toLowerCase(Locale.ROOT);
                if (q.contains(title)) score += 10.0;
                for (String w : title.split("[^a-z0-9]+")) {
                    if (w.length() >= 3 && q.contains(w)) score += 3.0;
                }
                // 关键词命中
                for (String k : e.keywords()) {
                    if (q.contains(k)) score += 1.0;
                }
                // 问题词直接命中正文
                for (String w : qWords) {
                    if (w.length() < 4) continue;
                    if (e.summary().toLowerCase(Locale.ROOT).contains(w)) score += 0.6;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
        }
        return best;
    }

    /** 知识库快照（供 /brain list 展示） */
    public List<Entry> entriesSnapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries.values());
        }
    }

    /** 取出知识库里所有"高度事实"，供挖掘决策使用 */
    public Map<String, int[]> miningDepths() {
        Map<String, int[]> out = new LinkedHashMap<>();
        synchronized (entries) {
            for (Entry e : entries.values()) {
                int i = e.fact().indexOf(":y=");
                if (i < 0) continue;
                String range = e.fact().substring(i + 3);
                String title = e.title().toLowerCase(Locale.ROOT);
                try {
                    if (range.contains("..")) {
                        String[] p = range.split("\\.\\.");
                        out.put(title, new int[]{
                                Integer.parseInt(p[0].trim()),
                                Integer.parseInt(p[1].trim())});
                    } else {
                        int v = Integer.parseInt(range.trim());
                        out.put(title, new int[]{v, v});
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字（wiki 用了别的写法），跳过
                }
            }
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("\\s+", " ").trim();
        return clean.length() <= SUMMARY_LIMIT ? clean : clean.substring(0, SUMMARY_LIMIT) + "…";
    }

    // ================= 持久化 =================

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        synchronized (entries) {
            for (Entry e : entries.values()) {
                CompoundTag t = new CompoundTag();
                t.putString("title", e.title());
                t.putString("summary", e.summary());
                t.putString("fact", e.fact());
                t.putLong("ts", e.timestamp());
                ListTag kw = new ListTag();
                for (String k : e.keywords()) kw.add(net.minecraft.nbt.StringTag.valueOf(k));
                t.put("kw", kw);
                list.add(t);
            }
        }
        root.put("entries", list);
        return root;
    }

    public void load(CompoundTag root) {
        entries.clear();
        if (root == null || !root.contains("entries")) return;
        ListTag list = root.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            List<String> kws = new ArrayList<>();
            ListTag kw = t.getList("kw", net.minecraft.nbt.Tag.TAG_STRING);
            for (int j = 0; j < kw.size(); j++) kws.add(kw.getString(j));
            learn(new Entry(t.getString("title"), t.getString("summary"),
                    kws, t.getString("fact"), t.getLong("ts")));
        }
    }
}