package com.kuronami.localinferenceapi.internal.som;

import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.DecisionResult;
import com.kuronami.localinferenceapi.api.NoulRequest;
import com.kuronami.localinferenceapi.api.NoulResult;
import com.kuronami.localinferenceapi.api.ScoreRequest;
import com.kuronami.localinferenceapi.api.ScoreResult;

import java.util.concurrent.CompletableFuture;

/**
 * typed SOM backend — 3 primitive を seam 化したもの。製品の CPU 実装は
 * {@link com.kuronami.localinferenceapi.internal.WorkerClient} そのもので、
 * テストは mock を差し替える。契約は LocalInference と同じ: 混雑・故障・
 * 終了時は例外完了し、null を返さない。
 */
public interface TypedBackend extends AutoCloseable {
    CompletableFuture<DecisionResult> decide(DecisionRequest request);
    CompletableFuture<ScoreResult> score(ScoreRequest request);
    CompletableFuture<NoulResult> noul(NoulRequest request);

    @Override
    default void close() {}
}
