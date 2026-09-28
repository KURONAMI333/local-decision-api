package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JevK5 native 経路の実機 smoke — pin 済み artifact のみを使い、製品の
 * 起動鎖を端から通す:
 *
 *   embedded manifest 検証 → stage(実 bytes、sha256 照合) → inspect →
 *   Unpacker 展開(member hash) → 実 llama-server spawn(127.0.0.1 +
 *   per-launch api key) → SomRouter + LlamaSystemoneChannel 経由で
 *   Choice/Score/Noul の実 readout。
 *
 * あわせて auth(401)/loopback 限定・invalidate 後の再起動・offline cache
 * (commit 済みなら fetch なしで立つ)・idle unload を検査する。
 *
 * assets は machine-local の local_map.json(artifact path → file)経由 —
 * {@code SOM_TEST_LOCAL_MAP} env または {@code som.test.localMap} property。
 * 未供給・未対応 platform・空き容量不足では skip され、試験の合否は外部
 * CDN・network に依存しない。
 */
class LlamaNativeSmokeTest {

    private static final Path LOCAL_MAP = Path.of(
            System.getProperty("som.test.localMap",
                    System.getenv().getOrDefault("SOM_TEST_LOCAL_MAP",
                            "som-jevk5-pin/local_map.json")));

    private static Map<String, Path> readLocalMap() throws IOException {
        if (!Files.isRegularFile(LOCAL_MAP)) return Map.of();
        JsonObject obj = JsonParser.parseString(
                Files.readString(LOCAL_MAP, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Path> out = new LinkedHashMap<>();
        for (String k : obj.keySet()) {
            Path p = Path.of(obj.get(k).getAsString());
            if (!Files.isRegularFile(p)) return Map.of();
            out.put(k, p);
        }
        return out;
    }

    /** upstream_url → local file の file-backed fetcher(network 不使用)。 */
    private static Fetcher fileFetcher(Map<String, Path> urlToFile) {
        return (uri, off) -> {
            Path local = urlToFile.get(uri.toString());
            if (local == null) {
                throw new Fetcher.FetchRejected("no local-map entry for " + uri);
            }
            SeekableByteChannel ch = Files.newByteChannel(local);
            ch.position(off);
            return new Fetcher.Response(200, Channels.newInputStream(ch));
        };
    }

    private static final Fetcher OFFLINE = (uri, off) -> {
        throw new Fetcher.FetchRejected("test: transport down");
    };

    private static JsonObject choiceBody(String state) {
        JsonObject criteria = new JsonObject();
        criteria.addProperty("billing", "billing and payment questions");
        criteria.addProperty("shipping", "shipping and delivery questions");
        criteria.addProperty("account", "account settings and login");
        JsonObject q = new JsonObject();
        q.addProperty("type", "choice");
        q.addProperty("instructions",
                "Which department should handle this request?");
        q.add("criteria", criteria);
        JsonObject questions = new JsonObject();
        questions.add("q", q);
        JsonObject body = new JsonObject();
        body.addProperty("state", state);
        body.add("questions", questions);
        return body;
    }

    private static JsonObject scoreBody() {
        com.google.gson.JsonArray criteria = new com.google.gson.JsonArray();
        criteria.add("not urgent at all");
        criteria.add("somewhat urgent");
        criteria.add("extremely urgent");
        JsonObject q = new JsonObject();
        q.addProperty("type", "score");
        q.addProperty("instructions",
                "How urgent is this request? Pick the matching level.");
        q.add("criteria", criteria);
        JsonObject questions = new JsonObject();
        questions.add("q", q);
        JsonObject body = new JsonObject();
        body.addProperty("state",
                "The customer's payment was charged twice and they cannot pay rent.");
        body.add("questions", questions);
        return body;
    }

    private static JsonObject noulBody() {
        JsonObject q = new JsonObject();
        q.addProperty("type", "noul");
        q.addProperty("instructions",
                "The customer explicitly asked for a refund.");
        JsonObject questions = new JsonObject();
        questions.add("q", q);
        JsonObject body = new JsonObject();
        body.addProperty("state",
                "Customer writes: I want my money back — you charged me twice "
                        + "for the same order last week.");
        body.add("questions", questions);
        return body;
    }

    /** ensureServing を SERVING まで回す — provision は非同期なので poll。 */
    private static void awaitServing(LlamaGate gate, long deadlineNanos)
            throws InterruptedException {
        SomNativeGate.Availability last = null;
        while (System.nanoTime() < deadlineNanos) {
            SomNativeGate.Availability a = gate.ensureServing();
            if (a != last) {
                System.out.println("[awaitServing] availability → " + a);
                last = a;
            }
            if (a == SomNativeGate.Availability.SERVING) return;
            assertNotEquals(SomNativeGate.Availability.TAMPERED, a,
                    "stage/検証の trust 失敗は smoke を即座に落とす: "
                            + String.join("\n", LlamaGate.recentChildOutput()));
            Thread.sleep(500);
        }
        StringBuilder stacks = new StringBuilder();
        Thread.getAllStackTraces().forEach((t, st) -> {
            if (t.getName().contains("localinferenceapi")) {
                stacks.append("\n=== ").append(t.getName())
                        .append(" ").append(t.getState());
                for (StackTraceElement e : st) {
                    stacks.append("\n    at ").append(e);
                }
            }
        });
        fail("llama-server が startupTimeout 内に SERVING にならなかった"
                + " (last=" + last + "): "
                + String.join("\n", LlamaGate.recentChildOutput())
                + stacks);
    }

    /** 現在の platform が stage に必要とする artifact の local map を作る。 */
    private static Map<String, Path> platformFetchMap(VerifiedManifest vm,
                                                      String platform,
                                                      Map<String, Path> localMap) {
        Map<String, Path> byUrl = new LinkedHashMap<>();
        int missing = 0;
        for (ManifestVerifier.Artifact art
                : vm.platformView(platform).artifacts()) {
            if (art.upstreamUrl() == null || "license".equals(art.role())) {
                continue; // license は embedded から stage される
            }
            Path local = localMap.get(art.path());
            if (local == null) { missing++; continue; }
            byUrl.put(art.upstreamUrl(), local);
        }
        return missing == 0 ? byUrl : Map.of();
    }

    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS)
    void fullNativeLifecycle(@TempDir Path gameDir) throws Exception {
        String platform = SomPlatform.detect();
        assumeTrue(platform != null,
                "no pinned runtime for this platform — smoke skipped");
        Map<String, Path> localMap = readLocalMap();
        assumeTrue(!localMap.isEmpty(),
                "SOM_TEST_LOCAL_MAP assets absent — smoke skipped");

        var verified = SomPackage.verifyEmbeddedManifest();
        assertTrue(verified.passed(), () -> String.join("\n", verified.failures()));
        Map<String, Path> urlToFile =
                platformFetchMap(verified.manifest(), platform, localMap);
        assumeTrue(!urlToFile.isEmpty(),
                "local-map lacks this platform's runtime/weights — skipped");

        // stage 済み bytes + 展開先 + GGUF の publish copy を賄えるか
        long need = urlToFile.values().stream().mapToLong(p -> {
            try { return Files.size(p); } catch (IOException e) { return 0; }
        }).sum();
        long usable = Files.getFileStore(gameDir).getUsableSpace();
        assumeTrue(usable > need * 2 + (512L << 20),
                "insufficient free space for smoke: usable=" + usable);

        Fetcher fetcher = fileFetcher(urlToFile);
        // --- gate 1: stage + spawn + Choice/Score/Noul + auth/loopback ---
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        try (LlamaGate gate = new LlamaGate(gameDir, platform, fetcher,
                SomPin::openEmbedded, LlamaGate.ProcessBuilderLauncher.INSTANCE,
                Duration.ofSeconds(60), Duration.ofMinutes(10),
                Duration.ofSeconds(1))) {
            awaitServing(gate,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(300));
            assertTrue(gate.port() > 0);

            SomChannel cpu = new SomChannel() {
                @Override public CompletableFuture<JsonObject> request(JsonObject b) {
                    return CompletableFuture.failedFuture(
                            new IOException("cpu must not be used while NATIVE"));
                }
            };
            try (SomRouter router = new SomRouter(gate,
                    port -> new LlamaSystemoneChannel(
                            new LlamaClient(port, gate::apiKey,
                                    Duration.ofSeconds(60)),
                            Duration.ofSeconds(90), gate::touch),
                    cpu, Duration.ofSeconds(90), 3)) {
                // router は gate の所有権を持つ — router.close() は gate を
                // 閉じる。restart 検証はこのブロック内で行う。

                // Choice — 実 readout。callback は CompletionThreads 配送。
                CompletableFuture<SomRouter.RouteReply> f =
                        router.request(choiceBody(
                                "Where do I update my credit card?"));
                f.whenComplete((r, e) -> callbackThread.set(Thread.currentThread()));
                SomRouter.RouteReply rep = f.get(120, TimeUnit.SECONDS);
                assertEquals(SomRouter.RouteUsed.NATIVE, rep.route());
                JsonObject a = rep.body().getAsJsonObject("answers")
                        .getAsJsonObject("q");
                assertEquals("choice", a.get("type").getAsString());
                String choice = a.get("choice").getAsString();
                assertTrue(a.getAsJsonObject("probabilities").has(choice),
                        "choice は criteria key のどれか: " + choice);
                double sum = a.getAsJsonObject("probabilities")
                        .entrySet().stream()
                        .mapToDouble(e -> e.getValue().getAsDouble()).sum();
                assertEquals(1.0, sum, 0.01, "校正済み確率は正規化される");
                assertTrue(a.get("input_tokens").getAsInt() > 0);
                assertEquals("billing", choice,
                        "credit card の問は billing へ寄るはず(品質 sanity)");
                assertNotNull(callbackThread.get());
                assertTrue(callbackThread.get().isVirtual()
                                || "localinferenceapi-completion".equals(
                                        callbackThread.get().getName()),
                        "応答は CompletionThreads 経由で配送される: "
                                + callbackThread.get());

                // Score — caller の level 順に index 確率
                JsonObject sa = router.request(scoreBody())
                        .get(120, TimeUnit.SECONDS).body()
                        .getAsJsonObject("answers").getAsJsonObject("q");
                assertEquals("score", sa.get("type").getAsString());
                assertEquals(3, sa.getAsJsonObject("probabilities").size());
                double score = sa.get("score").getAsDouble();
                assertTrue(score >= 0 && score <= 2, "score は index 加重平均");
                assertTrue(sa.get("selected").getAsInt() >= 0);
                assertEquals(2, sa.get("selected").getAsInt(),
                        "二重請求+家賃は最上級へ寄るはず(品質 sanity)");

                // Noul — true の確率
                JsonObject na = router.request(noulBody())
                        .get(120, TimeUnit.SECONDS).body()
                        .getAsJsonObject("answers").getAsJsonObject("q");
                assertEquals("noul", na.get("type").getAsString());
                double noul = na.get("noul").getAsDouble();
                assertTrue(noul >= 0 && noul <= 1);
                assertTrue(noul > 0.5,
                        "明示的な返金要求は P(true)>0.5 へ寄るはず(品質 sanity): "
                                + noul);

                // 日本語入力 — 失敗しないことと envelope の形だけ見る
                // (日本語品質は未保証 — 形状と応答の存在だけ検査)
                JsonObject ja = router.request(choiceBody(
                                "クレジットカードの番号を変更したい"))
                        .get(120, TimeUnit.SECONDS).body()
                        .getAsJsonObject("answers").getAsJsonObject("q");
                assertEquals("choice", ja.get("type").getAsString());
                assertTrue(ja.getAsJsonObject("probabilities")
                        .has(ja.get("choice").getAsString()));

                // auth — api-key なしの推論 endpoint は 401、/health は開放
                HttpClient raw = HttpClient.newHttpClient();
                HttpResponse<String> unauth = raw.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + gate.port()
                                        + "/completion"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, unauth.statusCode(),
                        "per-launch api key なしの推論 call は拒否される");
                HttpResponse<String> health = raw.send(HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + gate.port()
                                        + "/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode());

                // loopback 限定 — 非 loopback address への接続は拒否される
                java.util.Enumeration<java.net.NetworkInterface> ifs =
                        java.net.NetworkInterface.getNetworkInterfaces();
                boolean checked = false;
                while (ifs.hasMoreElements()) {
                    var nif = ifs.nextElement();
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    var addrs = nif.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        var addr = addrs.nextElement();
                        if (addr.isLoopbackAddress()
                                || addr instanceof java.net.Inet6Address) {
                            continue;
                        }
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(addr,
                                    gate.port()), 1000);
                            fail("non-loopback address へ listen してはいけない: "
                                    + addr);
                        } catch (IOException expected) {
                            checked = true; // refused — loopback のみ
                        }
                    }
                }
                assumeTrue(checked, "非 loopback の自機 address が無い環境では検査できない");

                // 再起動 — invalidateWorker で子を落とし、再 provision 後に
                // router 経由の要求が新 port の worker へ届くこと
                int firstPort = gate.port();
                gate.invalidateWorker();
                assertEquals(-1, gate.port(), "invalidate 後は port を畳む");
                awaitServing(gate,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(240));
                assertNotEquals(-1, gate.port());
                assertNotEquals(firstPort, gate.port(),
                        "再 spawn は新しい ephemeral port へ bind される");
                JsonObject ra = router.request(choiceBody("How do I change my "
                                + "billing address on the account page?"))
                        .get(120, TimeUnit.SECONDS).body()
                        .getAsJsonObject("answers").getAsJsonObject("q");
                assertEquals("choice", ra.get("type").getAsString(),
                        "再起動した worker も実 readout で応答する");
            }
        }

        // --- gate 2: offline cache — commit 済みなら fetch 無しで立つ ---
        try (LlamaGate offline = new LlamaGate(gameDir, platform, OFFLINE,
                SomPin::openEmbedded, LlamaGate.ProcessBuilderLauncher.INSTANCE,
                Duration.ofSeconds(240), Duration.ofMinutes(10),
                Duration.ofSeconds(1))) {
            awaitServing(offline,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(300));
            try (LlamaSystemoneChannel ch = new LlamaSystemoneChannel(
                    new LlamaClient(offline.port(), offline::apiKey,
                            Duration.ofSeconds(60)),
                    Duration.ofSeconds(90), offline::touch)) {
                JsonObject a = ch.request(choiceBody("Where is my order?"))
                        .get(120, TimeUnit.SECONDS)
                        .getAsJsonObject("answers").getAsJsonObject("q");
                assertEquals("shipping", a.get("choice").getAsString(),
                        "offline cache から立った worker も実 readout する");
            }
        }

        // --- gate 3: idle unload — touch は request 開始時だけなので、
        //     idle を request と並行させると要求途中で worker が落ちる。
        //     ここは要求を挟まず serve → 放置 → unload → 再 spawn だけ見る。 ---
        try (LlamaGate idle = new LlamaGate(gameDir, platform, OFFLINE,
                SomPin::openEmbedded, LlamaGate.ProcessBuilderLauncher.INSTANCE,
                Duration.ofSeconds(240), Duration.ofSeconds(2),
                Duration.ofSeconds(1))) {
            awaitServing(idle,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(240));
            assertTrue(idle.port() > 0);
            long idleDeadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(15);
            while (idle.port() != -1
                    && System.nanoTime() < idleDeadline) {
                Thread.sleep(250);
            }
            assertEquals(-1, idle.port(), "idle timeout で worker を畳む");
            // 次の demand で再 provision — stage/unpack は cache と marker で
            // short-circuit し、spawn だけが走る。
            awaitServing(idle,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(240));
            assertTrue(idle.port() > 0, "idle unload 後の再起動");
        }
    }
}
