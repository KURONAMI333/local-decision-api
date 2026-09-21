package com.kuronami.somcore.api;

import com.kuronami.somcore.internal.WorkerClient;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** ローカル判定 API。完了 callback のゲームスレッド実行は保証しないため、ワールド変更は呼出側で再スケジュールする。 */
public final class SomCore {
    private static WorkerClient worker;
    private SomCore() {}

    /** loader の lifecycle から呼ぶ。ここではモデルや subprocess を起動しない。 */
    public static synchronized void initialize(Path gameDirectory) {
        if (worker == null || worker.isClosed()) worker = new WorkerClient(gameDirectory.toAbsolutePath().normalize());
    }

    public static synchronized CompletableFuture<DecisionResult> decide(DecisionRequest request) {
        if (worker == null) return CompletableFuture.failedFuture(new IllegalStateException("SOM Core is not initialized"));
        return worker.decide(request);
    }

    public static synchronized void close() {
        if (worker != null) worker.close();
    }
}
