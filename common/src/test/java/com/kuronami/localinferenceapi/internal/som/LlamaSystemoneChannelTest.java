package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JEV envelope + letter readout を loopback の偽 llama-server で端から
 * 通す wire 試験 — /tokenize・/completion の request 形状、Bearer 認証、
 * answers.q の wrap、非2xx/不正入力の失敗を検査する。
 */
class LlamaSystemoneChannelTest {

    private HttpServer server;
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private volatile String completionResponse;
    /** 非 null の間 /completion が応答を保留する(wedged worker の再現)。 */
    private volatile CountDownLatch completionGate;

    private int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // wedged handler が他の exchange を押し潰さないよう並行に捌く。
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-llama-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/tokenize", ex -> {
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            // prompt 文字列長に比例する token 数を返す(決定的・小さい)
            String body = new String(ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            int n = Math.max(1, body.length() / 8);
            StringBuilder ids = new StringBuilder("[");
            for (int i = 0; i < n; i++) ids.append(i == 0 ? "" : ",").append(i);
            respond(ex, 200, "{\"tokens\":" + ids + "]}");
        });
        server.createContext("/completion", ex -> {
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            CountDownLatch gate = completionGate;
            if (gate != null) {
                try { gate.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            respond(ex, 200, completionResponse);
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex,
                                int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
        ex.close();
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private static String completionJson(String... letterLogprob) {
        // (letter, logprob) 交互 — letter B が最大になるよう呼出側で並べる
        StringBuilder top = new StringBuilder("[");
        for (int i = 0; i < letterLogprob.length; i += 2) {
            top.append(i == 0 ? "" : ",")
                    .append("{\"token\":\"").append(letterLogprob[i])
                    .append("\",\"logprob\":").append(letterLogprob[i + 1])
                    .append("}");
        }
        return "{\"completion_probabilities\":[{\"top_logprobs\":" + top
                + "]}],\"tokens_evaluated\":4}";
    }

    private static JsonObject choiceBody() {
        JsonObject criteria = new JsonObject();
        criteria.addProperty("billing", "billing");
        criteria.addProperty("other", "other");
        JsonObject q = new JsonObject();
        q.addProperty("type", "choice");
        q.addProperty("instructions", "which dept?");
        q.add("criteria", criteria);
        JsonObject questions = new JsonObject();
        questions.add("q", q);
        JsonObject body = new JsonObject();
        body.addProperty("state", "user asks about refunds");
        body.add("questions", questions);
        return body;
    }

    @Test void requestProducesJevAnswerEnvelope() throws Exception {
        completionResponse = completionJson(
                "B", "-0.1", "A", "-2.0", "x", "-5.0");
        int port = start();
        var client = new LlamaClient(port, () -> "testkey",
                Duration.ofSeconds(5));
        var ch = new LlamaSystemoneChannel(client, Duration.ofSeconds(10),
                () -> {});
        try {
            JsonObject res = ch.request(choiceBody())
                    .get(15, TimeUnit.SECONDS);
            JsonObject a = res.getAsJsonObject("answers")
                    .getAsJsonObject("q");
            assertEquals("choice", a.get("type").getAsString());
            assertEquals("other", a.get("choice").getAsString(),
                    "letter B が最高 → criteria の 2 番目へ写像");
            assertTrue(a.getAsJsonObject("probabilities").has("billing"));
            assertTrue(a.getAsJsonObject("probabilities").has("other"));
            assertTrue(a.get("input_tokens").getAsInt() > 0);
            // tokenize(+budget 確認)と completion で Bearer が出ている
            assertFalse(auth.isEmpty());
            assertTrue(auth.stream().allMatch("Bearer testkey"::equals),
                    "全 endpoint が per-launch key を送る: " + auth);
        } finally {
            ch.close();
        }
    }

    @Test void missingQuestionsFailsRequest() throws Exception {
        completionResponse = completionJson("A", "-0.1");
        int port = start();
        var ch = new LlamaSystemoneChannel(
                new LlamaClient(port, () -> "k", Duration.ofSeconds(5)),
                Duration.ofSeconds(5), () -> {});
        try {
            JsonObject body = new JsonObject();
            body.addProperty("state", "x");
            var f = ch.request(body);
            var e = assertThrows(ExecutionException.class,
                    () -> f.get(10, TimeUnit.SECONDS));
            assertTrue(String.valueOf(e.getCause()).contains("questions.q"));
        } finally {
            ch.close();
        }
    }

    @Test void serverErrorCompletesExceptionally() throws Exception {
        int port = start();
        server.removeContext("/completion");
        server.createContext("/completion", ex ->
                respond(ex, 500, "{\"error\":\"boom\"}"));
        var ch = new LlamaSystemoneChannel(
                new LlamaClient(port, () -> "k", Duration.ofSeconds(5)),
                Duration.ofSeconds(5), () -> {});
        try {
            var f = ch.request(choiceBody());
            assertThrows(ExecutionException.class,
                    () -> f.get(10, TimeUnit.SECONDS));
        } finally {
            ch.close();
        }
    }

    @Test void boundedQueueRejectsOverflowImmediately() throws Exception {
        completionResponse = completionJson("A", "-0.1");
        completionGate = new CountDownLatch(1); // /completion を塞ぐ
        int port = start();
        var ch = new LlamaSystemoneChannel(
                new LlamaClient(port, () -> "k", Duration.ofSeconds(10)),
                Duration.ofSeconds(30), () -> {});
        List<java.util.concurrent.CompletableFuture<JsonObject>> futs =
                new ArrayList<>();
        try {
            // 1 running + 16 queued = 17 が収容上限。18件目は即座に失敗。
            for (int i = 0; i < 18; i++) futs.add(ch.request(choiceBody()));
            var last = futs.get(17);
            var e = assertThrows(ExecutionException.class,
                    () -> last.get(5, TimeUnit.SECONDS));
            assertTrue(String.valueOf(e.getCause()).contains("saturated")
                            || String.valueOf(e.getCause()).contains("closed"),
                    "溢れ分は bounded queue の拒否で即失敗する: " + e.getCause());
            completionGate.countDown();
            // 解放後は滞留分が順に流れて成功する — 拒否されたのは18件目だけ
            for (int i = 0; i < 17; i++) {
                assertTrue(futs.get(i).get(30, TimeUnit.SECONDS)
                        .has("answers"), "queued request " + i);
            }
        } finally {
            completionGate.countDown();
            ch.close();
        }
    }

    @Test void routerOverflowPreservesAcceptedNativeRequestsAndNextRequest() throws Exception {
        completionResponse = completionJson("A", "-0.1", "B", "-2.0");
        completionGate = new CountDownLatch(1);
        int port = start();
        var invalidations = new java.util.concurrent.atomic.AtomicInteger();
        var cpuCalls = new java.util.concurrent.atomic.AtomicInteger();
        SomNativeGate gate = new SomNativeGate() {
            public Availability ensureServing() { return Availability.SERVING; }
            public int port() { return port; }
            public void invalidateWorker() { invalidations.incrementAndGet(); }
        };
        var ch = new LlamaSystemoneChannel(
                new LlamaClient(port, () -> "k", Duration.ofSeconds(10)),
                Duration.ofSeconds(30), () -> {});
        var router = new SomRouter(gate, ignored -> ch, body -> {
            cpuCalls.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(new JsonObject());
        }, Duration.ofSeconds(30), 3);
        var calls = new ArrayList<java.util.concurrent.CompletableFuture<SomRouter.RouteReply>>();
        try {
            for (int i = 0; i < 18; i++) calls.add(router.request(choiceBody()));
            var overflow = assertThrows(ExecutionException.class,
                    () -> calls.get(17).get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, overflow.getCause());
            assertEquals(0, invalidations.get());
            completionGate.countDown();
            for (int i = 0; i < 17; i++) {
                assertEquals(SomRouter.RouteUsed.NATIVE,
                        calls.get(i).get(30, TimeUnit.SECONDS).route(), "accepted request " + i);
            }
            assertEquals(SomRouter.RouteUsed.NATIVE,
                    router.request(choiceBody()).get(10, TimeUnit.SECONDS).route());
            assertEquals(0, invalidations.get());
            assertEquals(0, cpuCalls.get());
            assertFalse(router.nativeCircuitOpen());
        } finally {
            completionGate.countDown();
            router.close();
        }
    }

    @Test void timeoutInterruptsWedgedCallAndQueueRecovers() throws Exception {
        completionResponse = completionJson("A", "-0.1");
        completionGate = new CountDownLatch(1);
        int port = start();
        var ch = new LlamaSystemoneChannel(
                new LlamaClient(port, () -> "k", Duration.ofSeconds(10)),
                Duration.ofMillis(800), () -> {});
        try {
            var wedged = ch.request(choiceBody());
            var e = assertThrows(ExecutionException.class,
                    () -> wedged.get(10, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, e.getCause(),
                    "requestTimeout で TimeoutException へ畳む");
            completionGate.countDown();
            // 直列 thread は interrupt で解放され、次の request が通る —
            // wedged call が queue を塞ぎ続けないことの証明。
            JsonObject res = ch.request(choiceBody())
                    .get(15, TimeUnit.SECONDS);
            assertTrue(res.has("answers"));
        } finally {
            completionGate.countDown();
            ch.close();
        }
    }

    @Test void cancellationInterruptsActiveCallAndUnblocksNextRequest() throws Exception {
        completionResponse = completionJson("A", "-0.1");
        int port = start();
        var firstTokenize = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var tokenizations = new java.util.concurrent.atomic.AtomicInteger();
        server.removeContext("/tokenize");
        server.createContext("/tokenize", ex -> {
            if (tokenizations.incrementAndGet() == 1) {
                firstTokenize.countDown();
                try { releaseFirst.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            respond(ex, 200, "{\"tokens\":[1,2,3]}");
        });
        var ch = new LlamaSystemoneChannel(
                new LlamaClient(port, () -> "k", Duration.ofSeconds(10)),
                Duration.ofSeconds(5), () -> {});
        try {
            var cancelled = ch.request(choiceBody());
            assertTrue(firstTokenize.await(3, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(false));
            var next = ch.request(choiceBody());
            assertTrue(next.get(3, TimeUnit.SECONDS).has("answers"),
                    "a cancelled active HTTP call must release the serial executor");
            assertTrue(tokenizations.get() >= 2);
        } finally {
            releaseFirst.countDown();
            ch.close();
        }
    }

    @Test void closeFailsQueuedRequestsWithoutWaitingForTimeout() throws Exception {
        completionResponse = completionJson("A", "-0.1");
        completionGate = new CountDownLatch(1);
        int port = start();
        var ch = new LlamaSystemoneChannel(
                new LlamaClient(port, () -> "k", Duration.ofSeconds(10)),
                Duration.ofSeconds(60), () -> {}); // timeout は意図的に長く
        var running = ch.request(choiceBody());
        var queued = ch.request(choiceBody());
        ch.close(); // 滞留分は 60s の timeout を待たず即座に失敗する
        assertThrows(ExecutionException.class,
                () -> running.get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> queued.get(5, TimeUnit.SECONDS));
        var after = ch.request(choiceBody());
        assertThrows(ExecutionException.class,
                () -> after.get(5, TimeUnit.SECONDS));
        completionGate.countDown();
    }
}
