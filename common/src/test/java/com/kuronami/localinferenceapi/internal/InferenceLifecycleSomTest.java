package com.kuronami.localinferenceapi.internal;

import com.google.gson.JsonParser;
import com.kuronami.localinferenceapi.api.*;
import com.kuronami.localinferenceapi.internal.som.SomRouter;
import com.kuronami.localinferenceapi.internal.som.SomNativeGate;
import com.kuronami.localinferenceapi.internal.som.SomTypedClient;
import com.kuronami.localinferenceapi.internal.som.SomWiring;
import com.kuronami.localinferenceapi.internal.som.UnavailableNativeGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InferenceLifecycle の SOM native 経路試験。1.0.0 では native 経路は既定
 * ON("false" のみ opt-out)。workerFactory に fake-worker プロセスを、
 * SomWiring.gateFactory に {@link UnavailableNativeGate} を差して実
 * WorkerClient 経由の adapter round-trip を検査する — stub gate は常に
 * NO_COMMIT を返しディスク・network を一切触らないため、staging や実
 * llama-server の起動はこの試験に登場しない。
 */
class InferenceLifecycleSomTest {

    @TempDir Path temp;
    private String prevProperty;

    @BeforeEach void captureProperty() {
        prevProperty = System.getProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY);
        // 実 LlamaGate は stage のために network に出る — 本試験は配線と
        // record round-trip のみを見るため stub gate に固定する。
        SomWiring.gateFactory = d -> new UnavailableNativeGate();
    }

    @AfterEach void cleanup() {
        InferenceLifecycle.shutdown();
        InferenceLifecycle.workerFactory = WorkerClient::new;
        SomWiring.gateFactory = SomWiring::productionGate;
        if (prevProperty == null) System.clearProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY);
        else System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, prevProperty);
    }

    private Process launch(String mode) throws java.io.IOException {
        String cp = String.join(java.io.File.pathSeparator,
                java.util.stream.Stream.of(FakeWorker.class, WorkerClient.class, JsonParser.class)
                        .map(type -> {
                            try {
                                return Path.of(type.getProtectionDomain()
                                        .getCodeSource().getLocation().toURI()).toString();
                            } catch (Exception e) { throw new IllegalStateException(e); }
                        }).toList());
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new ProcessBuilder(java.toString(), "-cp", cp,
                FakeWorker.class.getName(), mode).start();
    }

    private static final class CountingFactory implements Function<Path, WorkerClient> {
        final java.util.concurrent.atomic.AtomicInteger launches =
                new java.util.concurrent.atomic.AtomicInteger();
        private final InferenceLifecycleSomTest self;
        CountingFactory(InferenceLifecycleSomTest self) { this.self = self; }
        @Override public WorkerClient apply(Path dir) {
            launches.incrementAndGet();
            return new WorkerClient(() -> self.launch("ok"),
                    Duration.ofSeconds(10), Duration.ofSeconds(10));
        }
    }

    private CountingFactory installFakeWorker() {
        CountingFactory f = new CountingFactory(this);
        InferenceLifecycle.workerFactory = f;
        return f;
    }

    private static DecisionRequest sampleDecision() {
        return new DecisionRequest(
                "Support ticket: my payment was charged twice this month.",
                "What should the support agent do next?",
                List.of("Report it", "Ignore it"));
    }

    @Test void nativeDefaultsToOn() throws Exception {
        System.clearProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY);
        CountingFactory factory = installFakeWorker();
        InferenceLifecycle.initialize(temp);
        assertTrue(InferenceLifecycle.somNativeOptIn(), "未設定時は native 経路 ON");
        DecisionResult r = InferenceLifecycle.decide(sampleDecision()).get(30, TimeUnit.SECONDS);
        assertEquals(0, r.selected());
        assertNotNull(InferenceLifecycle.somClient(),
                "既定 ON では SOM router が存在する");
        assertEquals(SomRouter.RouteUsed.CPU, InferenceLifecycle.somClient().lastRoute(),
                "stub gate は NO_COMMIT — CPU adapter が応答する");
        assertEquals(1, factory.launches.get());
    }

    @Test void falsePropertyDisablesNativeRoute() {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "false");
        installFakeWorker();
        InferenceLifecycle.initialize(temp);
        assertFalse(InferenceLifecycle.somNativeOptIn(),
                "明示 \"false\" のみ native 経路を OFF にする");
        assertNull(InferenceLifecycle.somClient());
    }

    @Test void nonFalsePropertyKeepsNativeOn() {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "yes");
        installFakeWorker();
        InferenceLifecycle.initialize(temp);
        assertTrue(InferenceLifecycle.somNativeOptIn(),
                "\"false\" 以外の値は既定 ON を維持する");
    }

    @Test void optInServesViaSomAdapterWithSingleWorker() throws Exception {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        CountingFactory factory = installFakeWorker();
        InferenceLifecycle.initialize(temp);
        assertTrue(InferenceLifecycle.somNativeOptIn());
        DecisionResult r = InferenceLifecycle.decide(sampleDecision()).get(30, TimeUnit.SECONDS);
        // FakeWorker の canned 応答が JEV round-trip を経て同じ record になる
        assertEquals(0, r.selected());
        assertEquals(List.of(0.5, 0.5), r.probabilities());
        assertEquals(List.of(0.0, 0.0), r.logits());
        assertFalse(r.truncated());
        SomTypedClient som = InferenceLifecycle.somClient();
        assertNotNull(som, "opt-in で router が存在する");
        assertEquals(SomRouter.RouteUsed.CPU, som.lastRoute(),
                "UnavailableNativeGate は native を拒否し CPU adapter が応答する");
        assertEquals(SomRouter.Status.DEGRADED, som.status());
        assertFalse(som.trustFailure());
        assertEquals(SomNativeGate.Availability.NO_COMMIT, som.lastNativeAvailability());
        assertEquals(1, factory.launches.get(),
                "SOM 経路でも実 WorkerClient は1プロセスで済む");
    }

    @Test void optInResultMatchesDirectPath() throws Exception {
        // native OFF で直接経路の基準応答を採る
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "false");
        installFakeWorker();
        InferenceLifecycle.initialize(temp);
        DecisionResult direct = InferenceLifecycle.decide(sampleDecision())
                .get(30, TimeUnit.SECONDS);
        InferenceLifecycle.endSession();

        // native ON: 同じ request の結果は record 単位で一致する
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        InferenceLifecycle.initialize(temp);
        DecisionResult routed = InferenceLifecycle.decide(sampleDecision())
                .get(30, TimeUnit.SECONDS);
        assertEquals(direct.selected(), routed.selected());
        assertEquals(direct.probabilities(), routed.probabilities());
        assertEquals(direct.logits(), routed.logits());
        assertEquals(direct.truncated(), routed.truncated());
    }

    @Test void optInServesScoreAndNoul() throws Exception {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        installFakeWorker();
        InferenceLifecycle.initialize(temp);
        ScoreResult s = InferenceLifecycle.score(new ScoreRequest(
                        "ctx", "urgency?",
                        List.of(new ScoreLevel("not urgent", 0.0),
                                new ScoreLevel("soon", 1.0),
                                new ScoreLevel("blocking", 2.0))))
                .get(30, TimeUnit.SECONDS);
        assertEquals(1.5, s.score(), 1e-9);
        assertEquals(2, s.selectedLevel());
        assertEquals(List.of(0.1, 0.3, 0.6), s.probabilities());

        NoulResult n = InferenceLifecycle.noul(
                new NoulRequest("ctx", "p?")).get(30, TimeUnit.SECONDS);
        assertEquals(0.8, n.trueProbability(), 1e-9);
        assertFalse(n.truncated());
    }

    @Test void initializeRebuildsSomWhenOptInChanges() throws Exception {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "false");
        installFakeWorker();
        InferenceLifecycle.initialize(temp);
        InferenceLifecycle.decide(sampleDecision()).get(30, TimeUnit.SECONDS);
        assertNull(InferenceLifecycle.somClient());

        // 次の初期化(新しい接続)で native が有効になれば router を作る
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        InferenceLifecycle.initialize(temp);
        InferenceLifecycle.decide(sampleDecision()).get(30, TimeUnit.SECONDS);
        assertNotNull(InferenceLifecycle.somClient());
    }

    @Test void endSessionClosesSomAndReleasesWorker() throws Exception {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        CountingFactory factory = installFakeWorker();
        InferenceLifecycle.initialize(temp);
        InferenceLifecycle.decide(sampleDecision()).get(30, TimeUnit.SECONDS);
        SomTypedClient som = InferenceLifecycle.somClient();
        InferenceLifecycle.endSession();
        assertTrue(som.isClosed(), "session 終了で router も閉じる");
        assertNull(InferenceLifecycle.somClient());
        // メニュー復帰後の新しい要求は新 worker + 新 router で捌く
        DecisionResult again = InferenceLifecycle.decide(sampleDecision())
                .get(30, TimeUnit.SECONDS);
        assertEquals(0, again.selected());
        assertEquals(2, factory.launches.get());
        assertNotNull(InferenceLifecycle.somClient());
    }

    @Test void shutdownRejectsRequestsUnderOptIn() {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        installFakeWorker();
        InferenceLifecycle.initialize(temp);
        InferenceLifecycle.shutdown();
        var fut = InferenceLifecycle.decide(sampleDecision());
        var ex = assertThrows(ExecutionException.class,
                () -> fut.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test void stubGateDoesNotCreateNativeArtifacts() throws Exception {
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        installFakeWorker();
        InferenceLifecycle.initialize(temp);
        InferenceLifecycle.decide(sampleDecision()).get(30, TimeUnit.SECONDS);
        // UnavailableNativeGate は一切ディスクに触れない —
        // runtime artifact / commit marker / staging directory が無いことを確認。
        // 製品の LlamaGate は最初の request で staging を始めるので、この主張は
        // stub 差し込み時だけ有効(実 gate の stage/起動は LlamaGate 系の試験で見る)。
        try (var stream = java.nio.file.Files.walk(temp)) {
            List<String> somFiles = stream.skip(1) // temp ルート自身は除く
                    .map(p -> p.getFileName().toString().toLowerCase())
                    .filter(n -> n.contains("som") || n.contains("native"))
                    .toList();
            assertTrue(somFiles.isEmpty(),
                    "stub gate では native artifact や staging が作られない: " + somFiles);
        }
    }
}
