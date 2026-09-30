package com.kuronami.localinferenceapi.internal;

import com.kuronami.localinferenceapi.Constants;
import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.DecisionResult;
import com.kuronami.localinferenceapi.api.ScoreRequest;
import com.kuronami.localinferenceapi.api.ScoreResult;
import com.kuronami.localinferenceapi.api.NoulRequest;
import com.kuronami.localinferenceapi.api.NoulResult;
import com.kuronami.localinferenceapi.internal.som.SomTypedClient;
import com.kuronami.localinferenceapi.internal.som.SomWiring;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** loader専用。通常のworld終了は次の明示要求を許可し、故障は自動リトライしない。 */
public final class InferenceLifecycle {
    /** SOM native 経路(JevK5/llama.cpp)の有効化。1.0.0 では既定 ON —
     *  "false" を明示した時だけ従来の Java worker 単独経路へ戻る。
     *  経路が変わるだけで公開APIの型・失敗契約は変わらない。 */
    public static final String SOM_NATIVE_PROPERTY = "localinferenceapi.som.native";

    private static Path gameDirectory;
    private static WorkerClient worker;
    private static SomTypedClient som;
    private static boolean somOptIn;
    private static boolean enabled;

    /** 試験用の worker 生成 seam。製品コードでは常に同梱 worker を起動する。 */
    static volatile Function<Path, WorkerClient> workerFactory = WorkerClient::new;

    private InferenceLifecycle() {}

    /** MOD初期化・新しい接続/サーバー開始。ここではプロセスを起動しない。 */
    public static synchronized void initialize(Path directory) {
        gameDirectory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        enabled = true;
        somOptIn = !"false".equalsIgnoreCase(
                System.getProperty(SOM_NATIVE_PROPERTY, "true"));
        if (worker != null && worker.isClosed()) worker = null;
        // som は同じ worker を CPU backend として握る。worker を捨てる・
        // router が閉じた・opt-in が外れた時は router 側も閉じて作り直す。
        if (som != null && (som.isClosed() || worker == null || !somOptIn)) {
            som.close();
            som = null;
        }
    }

    /** 公開APIからの要求。故障済みworkerは同じセッションで作り直さない。 */
    public static synchronized CompletableFuture<DecisionResult> decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!enabled) return CompletableFuture.failedFuture(new IllegalStateException("Local Decision API is not initialized"));
        if (worker == null) worker = workerFactory.apply(gameDirectory);
        if (!somOptIn) return worker.decide(request);
        return CancellableFutures.map(som().decide(request), reply -> reply.result());
    }

    public static synchronized CompletableFuture<ScoreResult> score(ScoreRequest request) {
        Objects.requireNonNull(request, "request");
        if (!enabled) return CompletableFuture.failedFuture(new IllegalStateException("Local Decision API is not initialized"));
        if (worker == null) worker = workerFactory.apply(gameDirectory);
        if (!somOptIn) return worker.score(request);
        return CancellableFutures.map(som().score(request), reply -> reply.result());
    }

    public static synchronized CompletableFuture<NoulResult> noul(NoulRequest request) {
        Objects.requireNonNull(request, "request");
        if (!enabled) return CompletableFuture.failedFuture(new IllegalStateException("Local Decision API is not initialized"));
        if (worker == null) worker = workerFactory.apply(gameDirectory);
        if (!somOptIn) return worker.noul(request);
        return CancellableFutures.map(som().noul(request), reply -> reply.result());
    }

    private static SomTypedClient som() {
        if (som == null) {
            som = SomWiring.create(gameDirectory, worker);
            Constants.LOG.info("SOM native route enabled; pinned llama.cpp/JevK5 runtime is staged and launched on demand, and the bundled Java worker serves requests until the native worker is serving or if it is unavailable");
        }
        return som;
    }

    /** 経路の可視性(native の未導入や trust 失敗を黙らせない)。internal・試験と診断用。 */
    static synchronized SomTypedClient somClient() { return som; }
    static synchronized boolean somNativeOptIn() { return somOptIn; }

    /** 古いworldの未完了要求を取消す。タイトル画面での次の要求は新workerを遅延起動する。 */
    public static synchronized void endSession() {
        if (som != null) {
            som.close();
            som = null;
        }
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
