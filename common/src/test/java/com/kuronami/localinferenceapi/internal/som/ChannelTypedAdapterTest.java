package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kuronami.localinferenceapi.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChannelTypedAdapter — JEV wire 形と typed backend の橋を決定的に検査する。
 * backend は canned 応答の fake。製品 LocalInference には触れない。
 */
class ChannelTypedAdapterTest {

    @Test void cancellationReachesTypedBackendFuture() {
        CompletableFuture<DecisionResult> queued = new CompletableFuture<>();
        TypedBackend backend = new TypedBackend() {
            @Override public CompletableFuture<DecisionResult> decide(DecisionRequest r) { return queued; }
            @Override public CompletableFuture<ScoreResult> score(ScoreRequest r) { throw new AssertionError(); }
            @Override public CompletableFuture<NoulResult> noul(NoulRequest r) { throw new AssertionError(); }
        };
        ChannelTypedAdapter adapter = new ChannelTypedAdapter(backend);
        DecisionRequest request = new DecisionRequest("ctx", "choice?", List.of("a", "b"));
        CompletableFuture<JsonObject> answer = adapter.request(JevCodec.encodeDecision(request));
        assertTrue(answer.cancel(false));
        assertTrue(queued.isCancelled());
    }

    /** canned 応答の typed backend。要求の到着を数え、close を記録する。 */
    private static final class FakeBackend implements TypedBackend {
        final AtomicInteger decides = new AtomicInteger();
        final AtomicInteger scores = new AtomicInteger();
        final AtomicInteger nouls = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        DecisionRequest lastDecision;
        ScoreRequest lastScore;
        NoulRequest lastNoul;
        DecisionResult decisionResult =
                new DecisionResult(1, List.of(0.25, 0.75), List.of(-1.0, 1.0), false);
        ScoreResult scoreResult =
                new ScoreResult(1.7, 2, List.of(0.1, 0.2, 0.7), false);
        NoulResult noulResult = new NoulResult(0.9, false);
        @Override public CompletableFuture<DecisionResult> decide(DecisionRequest r) {
            decides.incrementAndGet(); lastDecision = r;
            return CompletableFuture.completedFuture(decisionResult);
        }
        @Override public CompletableFuture<ScoreResult> score(ScoreRequest r) {
            scores.incrementAndGet(); lastScore = r;
            return CompletableFuture.completedFuture(scoreResult);
        }
        @Override public CompletableFuture<NoulResult> noul(NoulRequest r) {
            nouls.incrementAndGet(); lastNoul = r;
            return CompletableFuture.completedFuture(noulResult);
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    @Test void decisionRoundTripsThroughJevWireShape() throws Exception {
        FakeBackend backend = new FakeBackend();
        var adapter = new ChannelTypedAdapter(backend);
        var req = new DecisionRequest("context here", "which action?",
                List.of("Report it", "Ignore it"));
        JsonObject reply = adapter.request(JevCodec.encodeDecision(req))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, backend.decides.get());
        assertEquals("which action?", backend.lastDecision.question());
        assertEquals(List.of("Report it", "Ignore it"), backend.lastDecision.choices());
        JsonObject a = reply.getAsJsonObject("answers").getAsJsonObject("q");
        assertEquals("choice", a.get("type").getAsString());
        assertEquals("Ignore it", a.get("choice").getAsString());
        assertEquals(0.75, a.getAsJsonObject("probabilities")
                .get("Ignore it").getAsDouble(), 1e-9);
        // CPU adapter は製品 record を passthrough で完全保存する
        assertTrue(a.has("logits"));
        assertFalse(a.get("truncated").getAsBoolean());
        // 型の目の前で失われないことを client 側 decode で確認
        DecisionResult back = JevCodec.decodeDecision(reply, req);
        assertEquals(backend.decisionResult.selected(), back.selected());
        assertEquals(backend.decisionResult.logits(), back.logits());
    }

    @Test void scoreAndNoulRoundTripThroughJev() throws Exception {
        FakeBackend backend = new FakeBackend();
        var adapter = new ChannelTypedAdapter(backend);
        var sreq = new ScoreRequest("ctx", "urgency?",
                List.of(new ScoreLevel("not urgent", 0.0),
                        new ScoreLevel("soon", 1.0),
                        new ScoreLevel("blocking", 2.0)));
        JsonObject sReply = adapter.request(JevCodec.encodeScore(sreq))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, backend.scores.get());
        // level_values 独自拡張で ScoreLevel.value が保存される
        assertEquals(List.of(
                new ScoreLevel("not urgent", 0.0),
                new ScoreLevel("soon", 1.0),
                new ScoreLevel("blocking", 2.0)), backend.lastScore.levels());
        ScoreResult sBack = JevCodec.decodeScore(sReply, sreq);
        assertEquals(2, sBack.selectedLevel());
        // decodeScore は wire の score(この fake は意図的に不整合な 1.7)を
        // 信用せず Σ level.value·p で再計算する → 0·0.1+1·0.2+2·0.7 = 1.6
        assertEquals(1.6, sBack.score(), 1e-9);

        var nreq = new NoulRequest("ctx", "will churn?");
        JsonObject nReply = adapter.request(JevCodec.encodeNoul(nreq))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, backend.nouls.get());
        assertEquals("will churn?", backend.lastNoul.proposition());
        assertEquals(0.9, JevCodec.decodeNoul(nReply).trueProbability(), 1e-9);
    }

    @Test void malformedRequestFailsExceptionallyWithoutTouchingBackend() {
        FakeBackend backend = new FakeBackend();
        var adapter = new ChannelTypedAdapter(backend);
        JsonObject bad = JsonObjectBuilder.badChoice(); // criteria が欠ける
        var fut = adapter.request(bad);
        assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
        assertEquals(0, backend.decides.get() + backend.scores.get() + backend.nouls.get());
    }

    @Test void recordValidationRejectsInvalidDecodedRequest() {
        FakeBackend backend = new FakeBackend();
        var adapter = new ChannelTypedAdapter(backend);
        // 25 choice — DecisionRequest の最大は 24。decode は通るが record が拒否する
        JsonObject criteria = new JsonObject();
        for (int i = 0; i < 25; i++) criteria.addProperty("c" + i, "c" + i);
        JsonObject q = new JsonObject();
        q.addProperty("type", "choice");
        q.addProperty("instructions", "q?");
        q.add("criteria", criteria);
        JsonObject questions = new JsonObject();
        questions.add("q", q);
        JsonObject body = new JsonObject();
        body.addProperty("state", "ctx");
        body.add("questions", questions);
        var fut = adapter.request(body);
        var ex = assertThrows(ExecutionException.class,
                () -> fut.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause(),
                "record validation 失敗は request 単位の失敗として可視");
        assertEquals(0, backend.decides.get());
    }

    @Test void backendFailurePropagatesAsFailedFuture() {
        FakeBackend backend = new FakeBackend();
        backend.decisionResult = null;
        var adapter = new ChannelTypedAdapter(new TypedBackend() {
            @Override public CompletableFuture<DecisionResult> decide(DecisionRequest r) {
                return CompletableFuture.failedFuture(new IllegalStateException("worker dead"));
            }
            @Override public CompletableFuture<ScoreResult> score(ScoreRequest r) {
                return CompletableFuture.failedFuture(new IllegalStateException("worker dead"));
            }
            @Override public CompletableFuture<NoulResult> noul(NoulRequest r) {
                return CompletableFuture.failedFuture(new IllegalStateException("worker dead"));
            }
        });
        var fut = adapter.request(JevCodec.encodeDecision(
                new DecisionRequest("c", "q?", List.of("a", "b"))));
        var ex = assertThrows(ExecutionException.class,
                () -> fut.get(5, TimeUnit.SECONDS));
        assertEquals("worker dead", ex.getCause().getMessage());
    }

    @Test void closeDoesNotOwnBackend() {
        // adapter は backend の寿命を所有しない — router.close() の連鎖で
        // 実 WorkerClient が死なないことが製品 wiring の前提
        FakeBackend backend = new FakeBackend();
        var adapter = new ChannelTypedAdapter(backend);
        adapter.close();
        assertEquals(0, backend.closes.get());
    }

    private static final class JsonObjectBuilder {
        static JsonObject badChoice() {
            JsonObject q = new JsonObject();
            q.addProperty("type", "choice");
            q.addProperty("instructions", "q?");
            // criteria 欠落
            JsonObject questions = new JsonObject();
            questions.add("q", q);
            JsonObject body = new JsonObject();
            body.addProperty("state", "ctx");
            body.add("questions", questions);
            return body;
        }
    }

    @Test void scoreWithoutLevelValuesDegradesToIndexValues() throws Exception {
        FakeBackend backend = new FakeBackend();
        var adapter = new ChannelTypedAdapter(backend);
        // upstream の JEV body には level_values が無い — adapter は index を value に使う
        JsonObject q = new JsonObject();
        q.addProperty("type", "score");
        q.addProperty("instructions", "urgency?");
        JsonArray criteria = new JsonArray();
        criteria.add("not urgent"); criteria.add("soon"); criteria.add("blocking");
        q.add("criteria", criteria);
        JsonObject questions = new JsonObject();
        questions.add("q", q);
        JsonObject body = new JsonObject();
        body.addProperty("state", "ctx");
        body.add("questions", questions);
        adapter.request(body).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(
                new ScoreLevel("not urgent", 0.0),
                new ScoreLevel("soon", 1.0),
                new ScoreLevel("blocking", 2.0)), backend.lastScore.levels());
    }
}
