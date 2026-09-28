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
     * @return 候補indexと確率。混雑・故障・終了時は例外完了する
     */
    public static CompletableFuture<DecisionResult> decide(DecisionRequest request) {
        return InferenceLifecycle.decide(request);
    }

    /** 順序付き尺度で評価する。段階valueの確率加重平均を返す。 */
    public static CompletableFuture<ScoreResult> score(ScoreRequest request) {
        return InferenceLifecycle.score(request);
    }

    /** 命題のモデル内の真確率を求める。実世界での較正は未保証。 */
    public static CompletableFuture<NoulResult> noul(NoulRequest request) {
        return InferenceLifecycle.noul(request);
    }
}
