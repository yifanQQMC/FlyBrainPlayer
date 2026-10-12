package com.flybrain.learn;

import com.flybrain.FlyBrainPlayerMod;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minecraft Wiki 学习客户端。
 *
 * 用 MediaWiki Action API 拉取词条摘要，喂给 KnowledgeBase。
 * 设计要点（都是踩过坑的）：
 *
 * - **绝不能在服务端主线程发 HTTP 请求**，否则整个服卡死。全部走异步 + 独立线程池。
 * - **必须限流**：wiki 有礼貌性限速，多个 AI 同时查会触发 429。加令牌桶 + 单飞去重。
 * - **必须有超时**：网络不通时不能无限挂起，占着线程池。
 * - **缓存**：学到过的不再重复请求（KnowledgeBase.has 判定）。
 * - **可关闭**：服务器可能不希望模组出网，给开关。
 */
public final class WikiClient {

    private static final String API = "https://minecraft.wiki/api.php";
    /** 完整摘要（去掉 exintro）：高度/生成区间这类可执行事实只在正文里 */
    private static final String API_FULL = "https://minecraft.wiki/api.php";
    /** 用户代理：MediaWiki 要求标明身份，否则可能被 403 */
    private static final String UA =
            "FlyBrainPlayer/1.0 (Minecraft mod; learning agent; +https://minecraft.wiki/)";

    private static WikiClient instance;

    private final ScheduledExecutorService pool =
            Executors.newScheduledThreadPool(2, r -> {
                var t = new Thread(r, "FlyBrain-Wiki");
                t.setDaemon(true);           // 守护线程，服务端关闭时不阻塞退出
                return t;
            });

    /** in-flight 去重：同一个词条不并发请求 */
    private final Map<String, CompletableFuture<KnowledgeBase.Entry>> inflight =
            new ConcurrentHashMap<>();

    /** 失败退避：同一个词条失败后 N 秒内不再重试 */
    private final Map<String, Long> failUntil = new ConcurrentHashMap<>();
    private static final long BACKOFF_MS = 60_000;

    /** 令牌桶限流：最快每 1200ms 一次请求 */
    private final AtomicBoolean tokenBucket = new AtomicBoolean(true);
    private long lastRequestAt = 0;
    private static final long MIN_INTERVAL_MS = 1200;

    private volatile boolean enabled = true;

    public static WikiClient get() {
        if (instance == null) instance = new WikiClient();
        return instance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean e) {
        this.enabled = e;
    }

    /**
     * 异步学习一个词条。立即返回，完成后回调。
     * 返回 false 表示：已学过 / 正在学 / 处于失败退避 / 网络已关闭。
     */
    public boolean learn(String title, java.util.function.Consumer<KnowledgeBase.Entry> onDone) {
        if (!enabled || title == null || title.isBlank()) return false;

        String clean = title.trim();
        if (clean.length() > 80) return false;              // 防御：别拿超长串去打 API
        if (KnowledgeBase.shared().has(clean)) return false;

        long now = System.currentTimeMillis();
        Long blocked = failUntil.get(clean.toLowerCase());
        if (blocked != null && now < blocked) return false;

        // 单飞：已有同名词条在飞，直接复用它的 future
        var existing = inflight.get(clean.toLowerCase());
        if (existing != null) {
            if (onDone != null) existing.thenAccept(onDone);
            return true;
        }

        var future = new CompletableFuture<KnowledgeBase.Entry>();
        inflight.put(clean.toLowerCase(), future);
        if (onDone != null) future.thenAccept(onDone);

        pool.execute(() -> {
            try {
                acquireToken();
                // ① 先取导语（短、快、够回答"是什么"）
                String url = API
                        + "?action=query&prop=extracts&exintro=1&explaintext=1"
                        + "&redirects=1&format=json&titles="
                        + URLEncoder.encode(clean, StandardCharsets.UTF_8);
                request(url);
            } catch (Exception e) {
                failUntil.put(clean.toLowerCase(), System.currentTimeMillis() + BACKOFF_MS);
                FlyBrainPlayerMod.LOGGER.warn("[Wiki] 学习失败 {}: {}", clean, e.toString());
                inflight.remove(clean.toLowerCase());
                future.completeExceptionally(e);
            }
        });
        return true;
    }

    /**
     * 补充抓取完整正文。
     *
     * 为什么要两段式：`exintro=1` 只返回第一段导语，通常是"钻石是一种矿物，
     * 可以从钻石矿获得"——回答"是什么"够了，但**高度区间在正文里**，
     * 拿不到就意味着 AI 学到了却用不上（`diamondore:drops` 而不是 `y=-59..-16`）。
     * 所以导语抓完再补一次全文，合并后重算 fact。
     */
    private void requestFullBody(String title) {
        try {
            acquireToken();
            String url = API_FULL
                    + "?action=query&prop=extracts&explaintext=1"
                    + "&redirects=1&format=json&titles="
                    + URLEncoder.encode(title, StandardCharsets.UTF_8);
            String body = fetch(url);
            var full = parse(body);
            if (full == null) return;

            var existing = KnowledgeBase.shared().get(title);
            if (existing == null) return;

            // 用正文重算 fact；摘要保留较长的那份（导语更像"人话"）
            String mergedSummary = full.summary().length() > existing.summary().length()
                    ? full.summary() : existing.summary();
            KnowledgeBase.shared().learn(new KnowledgeBase.Entry(
                    existing.title(), mergedSummary,
                    KnowledgeBase.extractKeywords(mergedSummary),
                    full.fact(), existing.timestamp()));

            FlyBrainPlayerMod.LOGGER.info("[Wiki] 补充正文后 fact 升级: {} → {}",
                    existing.title(), full.fact());
        } catch (Exception e) {
            // 补充抓取失败不影响已有的导语知识
            FlyBrainPlayerMod.LOGGER.debug("[Wiki] 正文补充失败 {}: {}", title, e.toString());
        }
    }

    /** 令牌桶：保证两次请求之间至少间隔 MIN_INTERVAL_MS */
    private synchronized void acquireToken() throws InterruptedException {
        while (!tokenBucket.compareAndSet(true, false)) {
            Thread.sleep(50);
        }
        long wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
        tokenBucket.set(true);
    }

    private void request(String url) throws Exception {
        var entry = parse(fetch(url));
        if (entry == null) throw new IllegalStateException("无法解析响应");

        KnowledgeBase.shared().learn(entry);
        FlyBrainPlayerMod.LOGGER.info("[Wiki] 学到新知识: {} → {}",
                entry.title(), entry.fact());

        var f = inflight.remove(entry.title().toLowerCase());
        if (f != null) f.complete(entry);

        // 再补一次正文，拿到高度区间这类真正能改变行为的事实
        pool.execute(() -> requestFullBody(entry.title()));
    }

    /** 执行 HTTP GET，返回响应正文 */
    private String fetch(String url) throws Exception {
        var req = java.net.http.HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(15))
                .GET()
                .build();

        var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

        var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 解析 MediaWiki 的 JSON 响应 */
    private KnowledgeBase.Entry parse(String json) {
        // 用 MC 自带的 GsonHelper，避免额外依赖 gson 版本
        JsonObject root = GsonHelper.parse(json);

        if (root.has("error")) {
            throw new IllegalStateException("Wiki API 错误: " + root.get("error"));
        }

        if (!root.has("query")) return null;
        JsonObject query = root.getAsJsonObject("query");
        if (!query.has("pages")) return null;
        JsonObject pages = query.getAsJsonObject("pages");

        for (var entry : pages.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject page = entry.getValue().getAsJsonObject();
            // MediaWiki 用 "-1" 键或 missing 标记表示页面不存在
            if (page.has("missing") || "-1".equals(entry.getKey())) return null;

            String title = page.has("title") ? page.get("title").getAsString() : "";
            String extract = page.has("extract") ? page.get("extract").getAsString() : "";
            if (title.isBlank() || extract.isBlank()) return null;

            String fact = KnowledgeBase.extractFact(title, extract);
            var kws = KnowledgeBase.extractKeywords(extract);
            return new KnowledgeBase.Entry(title, extract, kws, fact, System.currentTimeMillis());
        }
        return null;
    }

    /** 供 /brain wiki <词条> 同步查一条（带短超时，供测试） */
    public KnowledgeBase.Entry queryBlocking(String title, int timeoutSeconds) {
        var f = new CompletableFuture<KnowledgeBase.Entry>();
        if (!learn(title, f::complete)) {
            var cached = KnowledgeBase.shared().get(title);
            if (cached != null) return cached;
        }
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            return KnowledgeBase.shared().get(title);
        }
    }
}