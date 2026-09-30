package com.kuronami.localinferenceapi.internal;

import com.kuronami.localinferenceapi.Constants;
import com.kuronami.localinferenceapi.api.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 開発起動時だけ有効にする、loader内から同梱モデルまでの疎通確認。
 * 起動につき1回だけ走る — client では最初の world/server 接続(Loader の
 * join 通知)、dedicated server では MOD 初期化から呼ばれる。
 * client の MOD 初期化では発火しない: world 読み込み冒頭の正規
 * endSession が in-flight の probe 要求を必ず drain する(確定的競合)。
 * probe は session を所有しない — 完了時に endSession しない(他の利用側の
 * in-flight 要求を巻き込むため)。worker の解放は world 遷移・server 停止の
 * 正規 endSession と WorkerClient の idle unload に任せる。
 */
public final class DeveloperProbe {
    /** 観測用の結果。呼出側は通常待たない — 試験と診断が読む。 */
    public enum Outcome { SKIPPED, PASSED, FAILED, CANCELLED }

    private static final AtomicBoolean FIRED = new AtomicBoolean();

    private DeveloperProbe() {}

    public static CompletableFuture<Outcome> runIfRequested() {
        if (!Boolean.getBoolean("localinferenceapi.selfTest")
                || !FIRED.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Outcome.SKIPPED);
        }
        var labels = List.of("Reporting a lost bank card", "Resetting a password", "Requesting a loan");
        long started = System.nanoTime();
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        LocalInference.decide(new DecisionRequest("I lost my wallet and need to block my debit card.",
                "What does this person need?", labels)).thenCompose(first -> {
            if (!Integer.valueOf(0).equals(first.selected())) throw new IllegalStateException("First classification differs: " + first);
            return LocalInference.decide(new DecisionRequest("I cannot remember my login password. Please reset it.",
                    "What does this person need?", labels));
        }).whenComplete((second, failure) -> {
            try {
                if (failure != null && sessionEnded(failure)) {
                    Constants.LOG.warn("LOCAL_INFERENCE_SELF_TEST_CANCELLED elapsed_ms={} — session ended while the probe was in flight", (System.nanoTime() - started) / 1e6);
                    outcome.complete(Outcome.CANCELLED);
                } else if (failure != null) {
                    Constants.LOG.error("LOCAL_INFERENCE_SELF_TEST_FAILED", failure);
                    outcome.complete(Outcome.FAILED);
                } else if (!Integer.valueOf(1).equals(second.selected())) {
                    Constants.LOG.error("LOCAL_INFERENCE_SELF_TEST_FAILED: {}", second);
                    outcome.complete(Outcome.FAILED);
                } else {
                    Constants.LOG.info("LOCAL_INFERENCE_SELF_TEST_PASSED elapsed_ms={} result={}", (System.nanoTime() - started) / 1e6, second);
                    outcome.complete(Outcome.PASSED);
                }
            } catch (RuntimeException broken) {
                outcome.completeExceptionally(broken);
            }
        });
        return outcome;
    }

    /** world 遷移・server 停止・終了で pending が取消された失敗形を識別する。 */
    private static boolean sessionEnded(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException && "router closed".equals(t.getMessage())) return true;
            if (t instanceof CancellationException && "Local Decision API stopped".equals(t.getMessage())) return true;
        }
        return false;
    }

    /** 試験用 — 起動1回の制限を戻す。製品コードからは呼ばない。 */
    static void resetForTesting() { FIRED.set(false); }
}
