package com.kuronami.localinferenceapi.internal.som;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlamaGate の状態機械試験 — 実 artifact staging・実 spawn は
 * LlamaNativeSmokeTest が担い、ここは環境不要の分岐(NO_RUNTIME_ARTIFACT、
 * fetch 拒否 → NO_COMMIT + cooldown、close latch)を検査する。
 */
class LlamaGateTest {

    private static LlamaGate gate(Path dir, String platform, Fetcher fetcher,
                                  Duration cooldown) {
        return new LlamaGate(dir, platform, fetcher, SomPin::openEmbedded,
                (argv, cwd, log) -> {
                    throw new IOException("test: no spawn");
                },
                Duration.ofSeconds(5), Duration.ofMinutes(1), cooldown);
    }

    private static final Fetcher REJECTING = (uri, off) -> {
        throw new Fetcher.FetchRejected("test: transport down");
    };

    @Test void nullPlatformReportsNoRuntimeArtifact(@TempDir Path dir) {
        try (LlamaGate g = gate(dir, null, REJECTING, Duration.ofSeconds(1))) {
            assertEquals(SomNativeGate.Availability.NO_RUNTIME_ARTIFACT,
                    g.ensureServing());
        }
    }

    @Test void fetchRejectionYieldsNoCommitNotTampered(@TempDir Path dir)
            throws Exception {
        try (LlamaGate g = gate(dir, "macos-arm64", REJECTING,
                Duration.ofMinutes(5))) {
            // provision 進行中・完了後を通じて transport 失敗は NO_COMMIT の
            // まま — TAMPERED は bytes/構成の真正性崩れにだけ立てる latch。
            for (int i = 0; i < 60; i++) {
                assertEquals(SomNativeGate.Availability.NO_COMMIT,
                        g.ensureServing());
                Thread.sleep(25);
            }
        }
    }

    @Test void cooldownBoundsReprovisionAttempts(@TempDir Path dir)
            throws Exception {
        AtomicInteger opens = new AtomicInteger();
        Fetcher counting = (uri, off) -> {
            opens.incrementAndGet();
            throw new Fetcher.FetchRejected("test: transport down");
        };
        try (LlamaGate g = gate(dir, "macos-arm64", counting,
                Duration.ofMinutes(10))) {
            g.ensureServing();
            // macos-arm64 view の fetch 対象は runtime + weights = 2
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (opens.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(2, opens.get(),
                    "最初の stage 失敗は runtime+weights の 2 open に留まる");
            // provision 結果を消費させ、cooldown 中は再 stage しない
            for (int i = 0; i < 100; i++) {
                g.ensureServing();
                Thread.sleep(10);
            }
            assertEquals(2, opens.get(),
                    "cooldown 内は fetch を再試行しない(crash-loop 抑止)");
        }
    }

    @Test void provisionRetriesAfterCooldown(@TempDir Path dir)
            throws Exception {
        AtomicInteger opens = new AtomicInteger();
        Fetcher counting = (uri, off) -> {
            opens.incrementAndGet();
            throw new Fetcher.FetchRejected("test: transport down");
        };
        try (LlamaGate g = gate(dir, "macos-arm64", counting,
                Duration.ofMillis(80))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (opens.get() < 4 && System.nanoTime() < deadline) {
                g.ensureServing();
                Thread.sleep(30);
            }
            assertTrue(opens.get() >= 4,
                    "cooldown 経過後に再 provision が走る: opens=" + opens.get());
        }
    }

    @Test void closedGateStaysNoCommit(@TempDir Path dir) {
        LlamaGate g = gate(dir, "macos-arm64", REJECTING, Duration.ZERO);
        g.close();
        assertEquals(SomNativeGate.Availability.NO_COMMIT, g.ensureServing());
        assertEquals(-1, g.port());
    }
}
