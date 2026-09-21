package com.kuronami.localinferenceapi.internal;

import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.DecisionResult;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** loader専用。通常のworld終了は次の明示要求を許可し、故障は自動リトライしない。 */
public final class InferenceLifecycle {
    private static Path gameDirectory;
    private static WorkerClient worker;
    private static boolean enabled;
    private InferenceLifecycle() {}

    /** MOD初期化・新しい接続/サーバー開始。ここではプロセスを起動しない。 */
    public static synchronized void initialize(Path directory) {
        gameDirectory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        enabled = true;
        if (worker != null && worker.isClosed()) worker = null;
    }

    /** 公開APIからの要求。故障済みworkerは同じセッションで作り直さない。 */
    public static synchronized CompletableFuture<DecisionResult> decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!enabled) return CompletableFuture.failedFuture(new IllegalStateException("Local Inference API is not initialized"));
        if (worker == null) worker = new WorkerClient(gameDirectory);
        return worker.decide(request);
    }

    /** 古いworldの未完了要求を取消す。タイトル画面での次の要求は新workerを遅延起動する。 */
    public static synchronized void endSession() {
        if (worker != null) {
            worker.close();
            worker = null;
        }
    }

    /** アプリ終了。次のinitializeまで全要求を拒否する。 */
    public static synchronized void shutdown() {
        enabled = false;
        endSession();
    }
}
