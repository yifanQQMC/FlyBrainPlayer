import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** 从 SkinsMC 抓真实皮肤 PNG，并把 wiki 摘要一并取下来（用于验证） */
public class FetchSkin {
    static final HttpClient C = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static byte[] get(String url, String referer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 FlyBrainPlayer/1.0")
                .GET();
        if (referer != null) b.header("Referer", referer);
        HttpResponse<byte[]> r = C.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("  " + r.statusCode() + "  " + r.body().length + "B  " + url);
        return r.body();
    }

    public static void main(String[] args) throws Exception {
        // ① 皮肤：从页面里抓到的 SkinsMC S3 直链
        String page = new String(get("https://skinsmc.org/skin/676300", null));
        String s3 = null;
        var m = java.util.regex.Pattern
                .compile("https://skinsmc\\.s3[^\"'<> ]+").matcher(page);
        if (m.find()) s3 = m.group();
        if (s3 == null) { System.out.println("找不到 S3 链接"); return; }

        byte[] png = get(s3, "https://skinsmc.org/");
        Path out = Paths.get("cyborg_steve.png");
        Files.write(out, png);
        System.out.println("皮肤已保存: " + out.toAbsolutePath() + " (" + png.length + " 字节)");

        // ② wiki 摘要：验证联网学习用
        for (String topic : new String[]{"Diamond", "Diamond ore", "Zombie"}) {
            String api = "https://minecraft.wiki/api.php?action=query&prop=extracts"
                    + "&exintro=1&explaintext=1&redirects=1&format=json&titles="
                    + java.net.URLEncoder.encode(topic, "UTF-8");
            String body = new String(get(api, null));
            Files.write(Paths.get("wiki_" + topic.replace(' ', '_') + ".json"),
                    body.getBytes("UTF-8"));
        }
    }
}