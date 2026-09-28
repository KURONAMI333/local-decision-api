package com.kuronami.localinferenceapi.api;

import java.util.List;

/** selected は 0 始まり。確率とlogitは各候補に対応する。truncated は入力がモデルの制約に合わせて短縮された印。 */
public record DecisionResult(Integer selected, List<Double> probabilities, List<Double> logits, boolean truncated) {
    public DecisionResult(Integer selected, List<Double> probabilities, List<Double> logits) {
        this(selected, probabilities, logits, false);
    }

    public DecisionResult {
        probabilities = List.copyOf(probabilities);
        logits = List.copyOf(logits);
        if (probabilities.isEmpty() || probabilities.size() > 24 || probabilities.size() != logits.size()) {
            throw new IllegalArgumentException("Invalid result dimensions");
        }
        if (selected == null || selected < 0 || selected >= probabilities.size()) {
            throw new IllegalArgumentException("Invalid selected index");
        }
        for (double probability : probabilities) {
            if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
                throw new IllegalArgumentException("Invalid probability");
            }
        }
        for (double logit : logits) if (!Double.isFinite(logit)) throw new IllegalArgumentException("Invalid logit");
    }
}
