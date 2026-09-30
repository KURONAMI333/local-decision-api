package com.kuronami.localinferenceapi.internal;

import com.google.gson.JsonParser;
import com.kuronami.localinferenceapi.api.*;
import com.kuronami.localinferenceapi.internal.som.SomWiring;
import com.kuronami.localinferenceapi.internal.som.UnavailableNativeGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeveloperProbe の起動1回制限と session 終了取消の分類。
 * client 配線(join 後発火)は loader 層にあるため、ここでは common 側の
 * 規約を見る — 要求は InferenceLifecycle 経由、probe は完了しても
 * session を閉じない、world 遷移の endSession に drain された時は
 * FAILED ではなく CANCELLED。
 * worker は FakeWorker プロセス、native gate は常に NO_COMMIT を返す
 * {@link UnavailableNativeGate} — ディスク・network を触らない。
 */
class DeveloperProbeTest {

    @TempDir Path temp;
    private String prevSelfTest;
    private String prevSom;

    @BeforeEach void capture() {
        prevSelfTest = System.getProperty("localinferenceapi.selfTest");
        prevSom = System.getProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY);
        SomWiring.gateFactory = d -> new UnavailableNativeGate();
        DeveloperProbe.resetForTesting();
    }

    @AfterEach void cleanup() {
        InferenceLifecycle.shutdown();
        InferenceLifecycle.workerFactory = WorkerClient::new;
        SomWiring.gateFactory = SomWiring::productionGate;
        DeveloperProbe.resetForTesting();
        restore("localinferenceapi.selfTest", prevSelfTest);
        restore(InferenceLifecycle.SOM_NATIVE_PROPERTY, prevSom);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
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

    /** launch 毎に mode を差し替えられる CountingFactory。 */
    private static final class CountingFactory implements Function<Path, WorkerClient> {
        final AtomicInteger launches = new AtomicInteger();
        final AtomicReference<String> mode;
        private final DeveloperProbeTest self;
        CountingFactory(DeveloperProbeTest self, String mode) {
            this.self = self;
            this.mode = new AtomicReference<>(mode);
        }
        @Override public WorkerClient apply(Path dir) {
            launches.incrementAndGet();
            return new WorkerClient(() -> self.launch(mode.get()),
                    Duration.ofSeconds(10), Duration.ofSeconds(10));
        }
    }

    private CountingFactory installFakeWorker(String mode) {
        CountingFactory f = new CountingFactory(this, mode);
        InferenceLifecycle.workerFactory = f;
        return f;
    }

    private static DecisionRequest trivial() {
        return new DecisionRequest("ctx", "q?", List.of("a", "b"));
    }

    @Test void skippedWithoutProperty() throws Exception {
        System.clearProperty("localinferenceapi.selfTest");
        CountingFactory factory = installFakeWorker("ok");
        InferenceLifecycle.initialize(temp);
        assertEquals(DeveloperProbe.Outcome.SKIPPED,
                DeveloperProbe.runIfRequested().get(5, TimeUnit.SECONDS));
        assertEquals(0, factory.launches.get(), "無効時は worker を起動しない");
    }

    @Test void firesOnceAndKeepsSessionOpen() throws Exception {
        System.setProperty("localinferenceapi.selfTest", "true");
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        CountingFactory factory = installFakeWorker("probe");
        InferenceLifecycle.initialize(temp);

        assertEquals(DeveloperProbe.Outcome.PASSED,
                DeveloperProbe.runIfRequested().get(30, TimeUnit.SECONDS));
        assertEquals(1, factory.launches.get());
        // probe は session を所有しない — 完了後も router は生きている
        var som = InferenceLifecycle.somClient();
        assertNotNull(som);
        assertFalse(som.isClosed(), "probe 完了で session を閉じない");

        // 再接続相当の2回目の呼出しは発火しない(起動1回の制限)
        assertEquals(DeveloperProbe.Outcome.SKIPPED,
                DeveloperProbe.runIfRequested().get(5, TimeUnit.SECONDS));
        // 既存 session をそのまま使う — worker も router も作り直さない。
        // "probe" モードの2件目以降は selected=1 — 同じ worker プロセスの証明にもなる。
        var again = InferenceLifecycle.decide(trivial()).get(30, TimeUnit.SECONDS);
        assertEquals(1, again.selected());
        assertEquals(1, factory.launches.get());
        assertSame(som, InferenceLifecycle.somClient());
    }

    @Test void sessionEndDuringProbeIsCancelledNotFailed() throws Exception {
        System.setProperty("localinferenceapi.selfTest", "true");
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        CountingFactory factory = installFakeWorker("hang");
        InferenceLifecycle.initialize(temp);

        var outcome = DeveloperProbe.runIfRequested();
        // world 遷移相当 — in-flight の probe 要求は閉鎖される router/worker が drain する
        InferenceLifecycle.endSession();
        assertEquals(DeveloperProbe.Outcome.CANCELLED, outcome.get(10, TimeUnit.SECONDS));

        // 取消されても起動1回の制限は消えない — 再 join で再発火しない
        assertEquals(DeveloperProbe.Outcome.SKIPPED,
                DeveloperProbe.runIfRequested().get(5, TimeUnit.SECONDS));

        // 閉じた router の残骸に引きずられない — 次の要求は新 session で捌く
        factory.mode.set("ok");
        var next = InferenceLifecycle.decide(trivial()).get(30, TimeUnit.SECONDS);
        assertEquals(0, next.selected());
        assertEquals(2, factory.launches.get());
    }

    @Test void workerRejectionIsFailedNotCancelled() throws Exception {
        System.setProperty("localinferenceapi.selfTest", "true");
        System.setProperty(InferenceLifecycle.SOM_NATIVE_PROPERTY, "true");
        installFakeWorker("error");
        InferenceLifecycle.initialize(temp);
        // 入力拒否は session 終了ではない — CANCELLED に誤分類しない
        assertEquals(DeveloperProbe.Outcome.FAILED,
                DeveloperProbe.runIfRequested().get(30, TimeUnit.SECONDS));
    }
}
