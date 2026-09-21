package com.kuronami.localinferenceapi.api;

import java.util.List;

/** selected は 0 始まり。null はモデルが選択を保留したことを表す。各 score リストの末尾は保留のスコア。 */
public record DecisionResult(Integer selected, List<Double> probabilities, List<Double> logits) {
    public DecisionResult {
        probabilities = List.copyOf(probabilities);
        logits = List.copyOf(logits);
        if (probabilities.size() < 2 || probabilities.size() > 25 || probabilities.size() != logits.size()) {
            throw new IllegalArgumentException("Invalid result dimensions");
        }
        if (selected != null && (selected < 0 || selected >= probabilities.size() - 1)) {
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
