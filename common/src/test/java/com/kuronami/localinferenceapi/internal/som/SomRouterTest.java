package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.internal.CancellableFutures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SomRouter の失敗分類試験。native gate は任意の Availability を返す fake、
 * channel は canned 応答の fake。実プロセス・実 manifest なしで router の
 * 全失敗経路を決定的に検査する。
 */
class SomRouterTest {

    /** canned の Availability を返す gate。再 gate 回数・invalidate を数える。 */
    private static final class FakeGate implements SomNativeGate {
        Availability availability;
        int port = 0;
        final AtomicInteger ensureCalls = new AtomicInteger();
        final AtomicInteger invalidations = new AtomicInteger();
        FakeGate(Availability a) { this.availability = a; }
        @Override public Availability ensureServing() {
            ensureCalls.incrementAndGet();
            return availability;
        }
        @Override public int port() { return port; }
        @Override public void invalidateWorker() { invalidations.incrementAndGet(); }
    }

    /** canned 成功/失敗/保留を返す決定的 channel。 */
    private static final class FakeChannel implements SomChannel {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        Function<JsonObject, CompletableFuture<JsonObject>> impl;
        FakeChannel(Function<JsonObject, CompletableFuture<JsonObject>> impl) {
            this.impl = impl;
        }
        @Override public CompletableFuture<JsonObject> request(JsonObject body) {
            calls.incrementAndGet();
            return impl.apply(body);
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static JsonObject cannedOk() {
        return JsonParser.parseString(
                "{\"answers\":{\"q\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":1.0}}}}")
                .getAsJsonObject();
    }
    private static CompletableFuture<JsonObject> ok(JsonObject b) {
        return CompletableFuture.completedFuture(cannedOk());
    }
    private static CompletableFuture<JsonObject> boom(JsonObject b) {
        return CompletableFuture.failedFuture(new IOException("cpu/native dead"));
    }
    private static CompletableFuture<JsonObject> never(JsonObject b) {
        return new CompletableFuture<>();
    }

    private static SomRouter router(SomNativeGate gate,
                                    SomRouter.NativeChannelFactory nf, SomChannel cpu) {
        return new SomRouter(gate, nf, cpu, Duration.ofSeconds(2), 3);
    }

    @Test void nativeServingRoutesToNative() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        gate.port = 4242;
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = router(gate,
                port -> new FakeChannel(SomRouterTest::ok), cpu)) {
            var reply = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.NATIVE, reply.route());
            assertTrue(reply.body().has("answers"));
            assertEquals(SomRouter.Status.NATIVE, r.status());
            assertEquals(0, cpu.calls.get(), "native が応答する間 CPU を触らない");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void cancellationAfterNativeDispatchReleasesRouterCapacity() throws Exception {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        gate.port = 4242;
        BlockingQueue<CompletableFuture<JsonObject>> dispatched = new LinkedBlockingQueue<>();
        FakeChannel nativeChannel = new FakeChannel(body -> {
            CompletableFuture<JsonObject> response = new CompletableFuture<>();
            dispatched.add(response);
            return response;
        });
        try (SomRouter router = router(gate, port -> nativeChannel,
                new FakeChannel(SomRouterTest::ok))) {
            for (int i = 0; i < 17; i++) {
                CompletableFuture<SomRouter.RouteReply> cancelled = router.request(new JsonObject());
                CompletableFuture<JsonObject> response = dispatched.poll(3, TimeUnit.SECONDS);
                assertNotNull(response, "native request was not dispatched");
                assertTrue(cancelled.cancel(false));
                assertTrue(response.isCancelled(), "cancellation must reach native channel");
                response.complete(cannedOk());
            }
            CompletableFuture<SomRouter.RouteReply> next = router.request(new JsonObject());
            CompletableFuture<JsonObject> response = dispatched.poll(3, TimeUnit.SECONDS);
            assertNotNull(response, "cancelled requests must not fill router capacity");
            response.complete(cannedOk());
            assertEquals(SomRouter.RouteUsed.NATIVE, next.get(3, TimeUnit.SECONDS).route());
        }
    }

    @Test void noCommitDegradesToCpu() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.NO_COMMIT);
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = router(gate, port -> { throw new AssertionError(); }, cpu)) {
            var reply = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.CPU, reply.route());
            assertEquals(SomRouter.Status.DEGRADED, r.status());
            assertEquals(SomNativeGate.Availability.NO_COMMIT, r.lastNativeAvailability());
            assertFalse(r.trustFailure());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void cancellingMappedPublicRequestSkipsQueuedBackendCall() throws Exception {
        CountDownLatch routingStarted = new CountDownLatch(1);
        CountDownLatch releaseRouting = new CountDownLatch(1);
        AtomicInteger gateCalls = new AtomicInteger();
        SomNativeGate gate = new SomNativeGate() {
            @Override public Availability ensureServing() {
                if (gateCalls.getAndIncrement() == 0) {
                    routingStarted.countDown();
                    try {
                        assertTrue(releaseRouting.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Availability.NO_COMMIT;
            }
            @Override public int port() { return -1; }
            @Override public void invalidateWorker() {}
        };
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter router = router(gate, port -> { throw new AssertionError(); }, cpu);
             SomTypedClient typed = new SomTypedClient(router)) {
            var first = router.request(new JsonObject());
            assertTrue(routingStarted.await(3, TimeUnit.SECONDS));
            var queued = CancellableFutures.map(
                    typed.decide(new DecisionRequest("context", "question",
                            List.of("first", "second"))), reply -> reply.result());
            assertTrue(queued.cancel(false));
            releaseRouting.countDown();
            first.get(3, TimeUnit.SECONDS);
            router.request(new JsonObject()).get(3, TimeUnit.SECONDS);
            assertEquals(2, cpu.calls.get(),
                    "cancelled second request must not reach the CPU backend");
        } finally {
            releaseRouting.countDown();
        }
    }

    @Test void cancelledCpuRequestDoesNotMarkBackendDisabled() throws Exception {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.NO_COMMIT);
        CompletableFuture<JsonObject> pending = new CompletableFuture<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FakeChannel cpu = new FakeChannel(body -> {
            if (calls.incrementAndGet() == 2) {
                dispatched.countDown();
                return pending;
            }
            return CompletableFuture.completedFuture(cannedOk());
        });
        try (SomRouter router = router(gate, port -> { throw new AssertionError(); }, cpu)) {
            router.request(new JsonObject()).get(3, TimeUnit.SECONDS);
            assertEquals(SomRouter.Status.DEGRADED, router.status());
            CompletableFuture<SomRouter.RouteReply> cancelled = router.request(new JsonObject());
            assertTrue(dispatched.await(3, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(false));
            assertTrue(pending.isCancelled(), "cancellation must reach the CPU backend");
            assertEquals(SomRouter.Status.DEGRADED, router.status(),
                    "caller cancellation is not a backend failure");
            router.request(new JsonObject()).get(3, TimeUnit.SECONDS);
            assertEquals(SomRouter.Status.DEGRADED, router.status());
        }
    }

    @Test void tamperLatchesTrustFailureAndServesCpu() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.TAMPERED);
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = router(gate, port -> { throw new AssertionError(); }, cpu)) {
            var reply = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.CPU, reply.route());
            assertTrue(r.trustFailure(), "trust 失敗は可視のまま残す");
            assertEquals(SomNativeGate.Availability.TAMPERED, r.lastNativeAvailability());
            // latch: 2回目は gate 自体を踏まない
            int callsAtLatch = gate.ensureCalls.get();
            var reply2 = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.CPU, reply2.route());
            assertEquals(callsAtLatch, gate.ensureCalls.get());
            assertEquals(2, cpu.calls.get());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void launchFailureDegrades() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.LAUNCH_FAILED);
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = router(gate, port -> { throw new AssertionError(); }, cpu)) {
            var reply = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.CPU, reply.route());
            assertEquals(SomNativeGate.Availability.LAUNCH_FAILED,
                    r.lastNativeAvailability());
            assertFalse(r.trustFailure(), "launch 失敗は trust 失敗ではない");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void nativeRequestFailureFallsBackAndOpensCircuit() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        gate.port = 1;
        AtomicInteger nativeOpens = new AtomicInteger();
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = router(gate,
                port -> { nativeOpens.incrementAndGet();
                          return new FakeChannel(SomRouterTest::boom); }, cpu)) {
            for (int i = 0; i < 3; i++) {
                var reply = r.request(new JsonObject()).get(15, TimeUnit.SECONDS);
                assertEquals(SomRouter.RouteUsed.CPU, reply.route());
            }
            assertTrue(r.nativeCircuitOpen(), "連続失敗で circuit latch");
            int opensAtLatch = nativeOpens.get();
            var reply = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.CPU, reply.route());
            assertEquals(opensAtLatch, nativeOpens.get(),
                    "circuit open 後は native gate/channel を踏まない");
            assertEquals(SomRouter.Status.DEGRADED, r.status());
            assertTrue(gate.invalidations.get() >= 3, "wedged worker は都度報告する");
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void channelOverloadDoesNotInvalidateHealthyWorker() throws Exception {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        AtomicInteger count = new AtomicInteger();
        try (SomRouter r = router(gate, port -> new FakeChannel(body -> {
            if (count.getAndIncrement() == 0) return CompletableFuture.failedFuture(
                    new java.util.concurrent.RejectedExecutionException("saturated"));
            return ok(body);
        }), cpu)) {
            var error = assertThrows(ExecutionException.class,
                    () -> r.request(new JsonObject()).get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, error.getCause());
            assertEquals(SomRouter.RouteUsed.NATIVE,
                    r.request(new JsonObject()).get(5, TimeUnit.SECONDS).route());
            assertEquals(0, gate.invalidations.get());
            assertEquals(0, cpu.calls.get());
            assertFalse(r.nativeCircuitOpen());
        }
    }

    @Test void contextRejectionUsesCpuWithoutPoisoningNativeWorker() throws Exception {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        AtomicInteger count = new AtomicInteger();
        try (SomRouter r = router(gate, port -> new FakeChannel(body -> {
            if (count.getAndIncrement() == 0) return CompletableFuture.failedFuture(
                    new IllegalArgumentException("question exceeds model context"));
            return ok(body);
        }), cpu)) {
            assertEquals(SomRouter.RouteUsed.CPU,
                    r.request(new JsonObject()).get(5, TimeUnit.SECONDS).route());
            assertEquals(SomRouter.RouteUsed.NATIVE,
                    r.request(new JsonObject()).get(5, TimeUnit.SECONDS).route());
            assertEquals(0, gate.invalidations.get());
            assertEquals(1, cpu.calls.get());
            assertFalse(r.nativeCircuitOpen());
        }
    }

    @Test void oneWorkerFailureInvalidatesOnceForAllAffectedRequests() throws Exception {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        List<CompletableFuture<JsonObject>> nativeCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch allRouted = new CountDownLatch(3);
        SomChannel channel = new SomChannel() {
            public CompletableFuture<JsonObject> request(JsonObject body) {
                var call = new CompletableFuture<JsonObject>();
                nativeCalls.add(call);
                allRouted.countDown();
                return call;
            }
            public void close() {
                nativeCalls.forEach(call -> call.completeExceptionally(new IOException("closed")));
            }
        };
        try (SomRouter r = router(gate, port -> channel, cpu)) {
            List<CompletableFuture<SomRouter.RouteReply>> requests = new ArrayList<>();
            for (int i = 0; i < 3; i++) requests.add(r.request(new JsonObject()));
            assertTrue(allRouted.await(3, TimeUnit.SECONDS));
            nativeCalls.get(0).completeExceptionally(new IOException("transport failed"));
            for (var request : requests) assertEquals(SomRouter.RouteUsed.CPU,
                    request.get(5, TimeUnit.SECONDS).route());
            assertEquals(1, gate.invalidations.get(), "one worker death, not three failed calls");
            assertFalse(r.nativeCircuitOpen());
            assertEquals(3, cpu.calls.get());
        }
    }

    @Test void dualFailureDisablesAndCompletesExceptionally() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.NO_COMMIT);
        FakeChannel cpu = new FakeChannel(SomRouterTest::boom);
        try (SomRouter r = router(gate, port -> { throw new AssertionError(); }, cpu)) {
            var fut = r.request(new JsonObject());
            assertThrows(ExecutionException.class, () -> fut.get(10, TimeUnit.SECONDS));
            assertEquals(SomRouter.Status.DISABLED, r.status());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void concurrentRequestsAllCompleteDeterministically() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.NO_COMMIT);
        AtomicInteger cpuCalls = new AtomicInteger();
        FakeChannel cpu = new FakeChannel(b -> {
            cpuCalls.incrementAndGet();
            return CompletableFuture.completedFuture(cannedOk());
        });
        try (SomRouter r = router(gate, port -> { throw new AssertionError(); }, cpu)) {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<SomRouter.RouteReply>> futs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futs.add(CompletableFuture.supplyAsync(() -> {
                    try { start.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return r.request(new JsonObject());
                }).thenCompose(x -> x));
            }
            start.countDown();
            for (var f : futs) {
                assertEquals(SomRouter.RouteUsed.CPU, f.get(10, TimeUnit.SECONDS).route());
            }
            assertEquals(8, cpuCalls.get());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void staleNativeCompletionCannotOverwriteCpuReply() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        gate.port = 1;
        CompletableFuture<JsonObject> heldNative = new CompletableFuture<>();
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = new SomRouter(gate,
                port -> new FakeChannel(b -> heldNative), cpu,
                Duration.ofMillis(300), 3)) {
            var reply = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.CPU, reply.route(),
                    "native が 300ms で timeout → CPU 応答が勝つ");
            assertFalse(heldNative.complete(cannedOk()),
                    "timeout 済み future は終端済みのはず");
            assertEquals(SomRouter.RouteUsed.CPU, r.lastRoute());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void closeFailsPendingAndNewRequests() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.NO_COMMIT);
        FakeChannel cpu = new FakeChannel(SomRouterTest::never);
        SomRouter r = router(gate, port -> { throw new AssertionError(); }, cpu);
        try {
            var fut = r.request(new JsonObject());
            Thread.sleep(200); // routing が CPU channel に届くまで
            r.close();
            assertThrows(ExecutionException.class, () -> fut.get(5, TimeUnit.SECONDS));
            var after = r.request(new JsonObject());
            assertThrows(ExecutionException.class, () -> after.get(5, TimeUnit.SECONDS));
            assertEquals(SomRouter.Status.DISABLED, r.status());
            assertTrue(r.isClosed());
            assertEquals(1, cpu.closes.get());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void portChangeReopensNativeChannel() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        gate.port = 1;
        AtomicInteger nativeOpens = new AtomicInteger();
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = router(gate,
                port -> { nativeOpens.incrementAndGet();
                          return new FakeChannel(SomRouterTest::ok); }, cpu)) {
            var first = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.NATIVE, first.route());
            // worker が死んだ → gate が別 port で再起動した体で次の要求を捌く
            gate.port = 2;
            var second = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.NATIVE, second.route());
            assertEquals(2, nativeOpens.get(), "port 変更で channel を再 open");
            assertEquals(0, cpu.calls.get());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void synchronousNativeThrowFallsBackToCpu() {
        FakeGate gate = new FakeGate(SomNativeGate.Availability.SERVING);
        gate.port = 1;
        FakeChannel cpu = new FakeChannel(SomRouterTest::ok);
        try (SomRouter r = router(gate,
                port -> new FakeChannel(b -> { throw new IllegalStateException("sync boom"); }),
                cpu)) {
            var reply = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertEquals(SomRouter.RouteUsed.CPU, reply.route(),
                    "channel の同期 throw は seq を止めず CPU へ退避する");
            // 次の request も処理される(router seq が生きている)
            var again = r.request(new JsonObject()).get(10, TimeUnit.SECONDS);
            assertNotNull(again.route());
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
