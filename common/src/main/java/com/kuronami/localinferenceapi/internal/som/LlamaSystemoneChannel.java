package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JevK5 readout を llama-server へ向ける {@link SomChannel}。JEV wire
 * ({@code v1/systemone})の見た目を保ちつつ、transport は mod 内の
 * {@link JevReadout} が llama-server の /tokenize + /completion へ直結する
 * — python shim / /v1/systemone サーバーは配布物に含めない。
 *
 * llama-server は単一 slot で使うため request は専用の単一スレッド
 * executor で直列化し、queue は WorkerClient と同じ 16 で束ねる — 超過分は
 * RejectedExecutionException で即座に失敗し、router が CPU 経路へ退避させる
 * (待ち行列を無制限にしない)。requestTimeout で打ち切られた要求は実行
 * thread を interrupt して解放し、後続の request を wedged にしない
 * (HttpClient.send は割込みで失敗する)。
 */
final class LlamaSystemoneChannel implements SomChannel {

    /** WorkerClient と同じ queue 上限(要求は直列 executor 前に滞留する)。 */
    private static final int MAX_QUEUED = 16;

    private final LlamaClient client;
    private final Duration requestTimeout;
    private final Runnable onActivity;
    private final ExecutorService exec;
    /** 完了をまだ返していない呼出し — close で即座に失敗させる対象。 */
    private final java.util.Set<CompletableFuture<JsonObject>> inflight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    LlamaSystemoneChannel(LlamaClient client, Duration requestTimeout,
                          Runnable onActivity) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.onActivity = onActivity;
        this.exec = new java.util.concurrent.ThreadPoolExecutor(1, 1,
                0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(MAX_QUEUED),
                r -> {
                    Thread t = new Thread(r, "localinferenceapi-som-llama-io");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public CompletableFuture<JsonObject> request(JsonObject body) {
        CompletableFuture<JsonObject> out = new CompletableFuture<>();
        final Future<?>[] task = new Future<?>[1];
        try {
            inflight.add(out);
            task[0] = exec.submit(() -> {
                try {
                    onActivity.run();
                    out.complete(call(body));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                } finally {
                    inflight.remove(out);
                }
            });
        } catch (RejectedExecutionException closed) {
            // shutdown 済み or bounded queue 満杯 — 即座に失敗で返す
            inflight.remove(out);
            out.completeExceptionally(new IOException(
                    "native channel closed or saturated", closed));
            return out;
        }
        // timeout 時は実行 thread を interrupt する — llama-server が wedged
        // でも直列 queue が詰まらない。task は submit 前の取消 race があるため
        // 配列越しに参照する。
        out.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> {
                    inflight.remove(out);
                    if (e instanceof TimeoutException && task[0] != null) {
                        task[0].cancel(true);
                    }
                });
        return out;
    }

    private JsonObject call(JsonObject body) throws IOException {
        // JEV wire: {"state": "<context>", "questions": {"q": {...}}}
        JsonObject questions = body.getAsJsonObject("questions");
        JsonObject q = questions != null ? questions.getAsJsonObject(JevCodec.Q) : null;
        if (q == null) throw new IOException("native request missing questions.q");
        String context = body.has("state") && body.get("state").isJsonPrimitive()
                ? body.get("state").getAsString() : "";
        JevReadout.Probs probs = JevReadout.probabilities(client, context, q);
        JsonObject answers = new JsonObject();
        answers.add(JevCodec.Q, JevReadout.answer(q, probs));
        JsonObject response = new JsonObject();
        response.add("answers", answers);
        return response;
    }

    @Override
    public void close() {
        exec.shutdownNow();
        // queue に残った request を timeout まで宙に浮かせない — close は
        // server 停止経路からも来るため、滞留分はここで確定失敗させる。
        IOException closed = new IOException("native channel closed");
        for (CompletableFuture<JsonObject> f : inflight) {
            f.completeExceptionally(closed);
        }
    }
}
