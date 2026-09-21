package com.kuronami.localinferenceapi.api;

import com.kuronami.localinferenceapi.internal.InferenceLifecycle;
import java.util.concurrent.CompletableFuture;

/** ゲームの状態を文字列のsnapshotとして送り、候補からの選択を非同期で受け取る共有API。 */
public final class LocalInference {
    private LocalInference() {}

    /**
     * 判定を要求する。完了callbackのゲームスレッド実行は保証しない。
     * ワールド変更はゲームスレッドへ移し、対象のworld/playerが今も有効か再確認すること。
     * @param request 変更不能なcontext・question・choices
     * @return 候補indexまたは棄権を含む結果。混雑・故障・終了時は例外完了する
     */
    public static CompletableFuture<DecisionResult> decide(DecisionRequest request) {
        return InferenceLifecycle.decide(request);
    }
}
