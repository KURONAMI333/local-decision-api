package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * llama-server の loopback HTTP client。同期呼出し — caller
 * ({@link LlamaSystemoneChannel} の直列 executor や gate の seq)側が
 * ブロックを管理する。全 endpoint は 127.0.0.1 のみ、Authorization は
 * gate が発行した per-launch key。
 *
 * 使う endpoint は3つだけ:
 *   GET  /health     — 起動完了待ち(api-key 設定時も認証不要)
 *   POST /tokenize   — add_special:false + parse_special:true
 *   POST /completion — prompt=token id 配列, n_predict=1, n_probs=topK,
 *                      temperature=0, cache_prompt=false
 */
final class LlamaClient implements JevReadout.Io {

    private final HttpClient client;
    private final URI base;
    private final Supplier<String> apiKey;
    private final Duration requestTimeout;
    private final Duration healthTimeout;

    LlamaClient(int loopbackPort, Supplier<String> apiKey, Duration requestTimeout) {
        this(loopbackPort, apiKey, requestTimeout, Duration.ofMillis(500));
    }

    LlamaClient(int loopbackPort, Supplier<String> apiKey, Duration requestTimeout,
                Duration healthTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.base = URI.create("http://127.0.0.1:" + loopbackPort);
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.healthTimeout = healthTimeout;
    }

    /** 起動中の probe — 接続拒否・timeout・非 200 は全て false。 */
    boolean healthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(base.resolve("/health"))
                    .timeout(healthTimeout).GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return false;
            JsonElement body = JsonParser.parseString(res.body());
            // llama-server は {"status":"ok"} — shape が変わっても 200 を生かす
            return !body.isJsonObject()
                    || !body.getAsJsonObject().has("status")
                    || "ok".equals(body.getAsJsonObject().get("status").getAsString());
        } catch (Exception notReady) {
            if (notReady instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** prompt を token id 列へ。special token は文字列として埋め込まれている。 */
    @Override
    public int[] tokenize(String content) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);
        payload.addProperty("add_special", false);
        payload.addProperty("parse_special", true);
        JsonObject res = post("/tokenize", payload);
        JsonArray tokens = res.getAsJsonArray("tokens");
        if (tokens == null || tokens.isEmpty()) {
            throw new IOException("tokenize returned no tokens");
        }
        int[] ids = new int[tokens.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = tokens.get(i).getAsInt();
        return ids;
    }

    record Completion(Map<String, Double> topLogprobs, int tokensEvaluated) {}

    /** 次 token 位置の top_k logprob — 選択肢 letter の写像に使う。 */
    @Override
    public Completion completion(int[] tokens, int topK) throws IOException {
        JsonArray ids = new JsonArray();
        for (int t : tokens) ids.add(t);
        JsonObject payload = new JsonObject();
        payload.add("prompt", ids);
        payload.addProperty("n_predict", 1);
        payload.addProperty("n_probs", topK);
        payload.addProperty("temperature", 0);
        payload.addProperty("cache_prompt", false);
        JsonObject res = post("/completion", payload);
        JsonArray probs = res.getAsJsonArray("completion_probabilities");
        if (probs == null || probs.isEmpty()) {
            throw new IOException("completion returned no probabilities");
        }
        JsonArray top = probs.get(0).getAsJsonObject().getAsJsonArray("top_logprobs");
        if (top == null) throw new IOException("completion missing top_logprobs");
        Map<String, Double> seen = new LinkedHashMap<>();
        for (JsonElement e : top) {
            JsonObject o = e.getAsJsonObject();
            seen.put(o.get("token").getAsString(), o.get("logprob").getAsDouble());
        }
        int evaluated = res.has("tokens_evaluated") && res.get("tokens_evaluated").isJsonPrimitive()
                ? res.get("tokens_evaluated").getAsInt() : tokens.length;
        return new Completion(seen, evaluated);
    }

    private JsonObject post(String path, JsonObject payload) throws IOException {
        String key = apiKey.get();
        HttpRequest.Builder req = HttpRequest.newBuilder(base.resolve(path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        if (key != null && !key.isEmpty()) {
            req.header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> res;
        try {
            res = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IllegalArgumentException | java.net.ConnectException e) {
            throw new IOException("llama-server unreachable: " + e.getMessage(), e);
        }
        if (res.statusCode() != 200) {
            throw new IOException("llama-server HTTP " + res.statusCode() + " on " + path);
        }
        JsonElement body;
        try {
            body = JsonParser.parseString(res.body());
        } catch (RuntimeException bad) {
            throw new IOException("malformed llama-server response", bad);
        }
        if (!body.isJsonObject()) throw new IOException("llama-server response not an object");
        return body.getAsJsonObject();
    }
}
