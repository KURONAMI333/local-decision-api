package com.kuronami.inferencefixture;

import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.LocalInference;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;

/** 別MODとして公開APIを使い、2利用者の成功後に明示された故障試験を行う。 */
public final class SharedFailureProbe {
    private static volatile MinecraftServer owner;
    private static long secondStarted;
    private static long secondFinished;
    private static int startTick;
    private static int failureTick = -1;
    private static boolean secondComplete;
    private static boolean injected;
    private static boolean done;
    private static long deadline;
    private SharedFailureProbe() {}

    public static void started(MinecraftServer server) {
        if (!Boolean.getBoolean("inferenceexample.smoke")) return;
        owner = server;
        secondStarted = System.nanoTime();
        startTick = server.getTickCount();
        deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        failureTick = -1;
        secondComplete = injected = done = false;
        LocalInference.decide(request()).whenComplete((result, failure) -> {
            long completedAt = System.nanoTime();
            server.execute(() -> {
            if (owner != server || done) return;
            if (failure != null) { fail("second consumer inference failed"); return; }
            secondFinished = completedAt;
            secondComplete = true;
            LogUtils.getLogger().info("INFERENCE_SECOND_CONSUMER_PASS selected={}", result.selected());
            });
        });
    }

    public static void tick(MinecraftServer server) {
        if (owner != server || done) return;
        if (System.nanoTime() > deadline) { fail("probe deadline exceeded"); return; }
        if (failureTick >= 0 && server.getTickCount() - failureTick >= 5) {
            done = true;
            LogUtils.getLogger().info("INFERENCE_SHARED_FAILURE_PASS consumers=2 singleWorker=true nextRequestFailed=true ticksAfterFailure={}",
                    server.getTickCount() - failureTick);
            return;
        }
        if (injected || !secondComplete || server.getTickCount() <= startTick) return;
        String token = Integer.toHexString(System.identityHashCode(server));
        if (!token.equals(System.getProperty("inferenceexample.primaryComplete"))) return;
        long primaryStarted = Long.parseLong(System.getProperty("inferenceexample.primaryStartedNanos", "0"));
        long primaryFinished = Long.parseLong(System.getProperty("inferenceexample.primaryFinishedNanos", "0"));
        if (primaryStarted <= 0 || secondStarted > primaryFinished || primaryStarted > secondFinished) {
            fail("consumer requests did not overlap");
            return;
        }
        LogUtils.getLogger().info("INFERENCE_CONCURRENT_REQUESTS_PASS overlap=true elapsedServerTicks={}", server.getTickCount() - startTick);
        injected = true;
        // process照合・待機をサーバースレッドから完全に離す。
        Thread worker = new Thread(() -> inspectAndFail(server), "inference-fixture-failure-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static void inspectAndFail(MinecraftServer server) {
        try {
            var matches = OwnedWorker.find();
            if (matches.size() != 1) throw new IllegalStateException("Expected exactly one owned worker, found " + matches.size());
            ProcessHandle child = matches.getFirst();
            LogUtils.getLogger().info("INFERENCE_SHARED_WORKER_PASS consumers=2 workerPid={}", child.pid());
            if (!Boolean.getBoolean("inferenceexample.failureSmoke")) {
                server.execute(() -> { if (owner == server) done = true; });
                return;
            }
            if (owner != server || server.isStopped()) return;
            // 直前にも所有・引数・実行ファイルを照合。違えば一切終了しない。
            if (!OwnedWorker.matches(child) || !child.destroyForcibly()) throw new IllegalStateException("Owned worker termination refused");
            child.onExit().get(15, TimeUnit.SECONDS);
            if (owner != server || server.isStopped()) return;
            LocalInference.decide(request()).whenComplete((result, failure) -> server.execute(() -> {
                if (owner != server || done) return;
                if (failure == null) { fail("request unexpectedly succeeded after worker exit"); return; }
                failureTick = server.getTickCount();
                LogUtils.getLogger().info("INFERENCE_EXPECTED_FAILURE_OBSERVED serverThread={}", server.isSameThread());
            }));
        } catch (Exception failure) {
            server.execute(() -> { if (owner == server) fail(failure.toString()); });
        }
    }

    private static DecisionRequest request() {
        return new DecisionRequest("I cannot remember my password. Please help me reset it.", "What does this person need?",
                List.of("Reporting a lost bank card", "Resetting a password", "Requesting a loan"));
    }
    private static void fail(String reason) {
        done = true;
        LogUtils.getLogger().error("INFERENCE_SHARED_FAILURE_FAIL {}", reason);
    }
    public static void stopping(MinecraftServer server) {
        if (owner == server) { owner = null; done = true; }
    }
}
