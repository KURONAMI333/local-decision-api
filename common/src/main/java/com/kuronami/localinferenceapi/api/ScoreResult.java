package com.kuronami.localinferenceapi.api;

import java.util.List;

/** score は各段階のvalueを確率で重み付けした平均。truncated は入力がモデルの制約に合わせて短縮された印。 */
public record ScoreResult(double score, int selectedLevel, List<Double> probabilities, boolean truncated) {
    public ScoreResult(double score, int selectedLevel, List<Double> probabilities) {
        this(score, selectedLevel, probabilities, false);
    }

    public ScoreResult {
        probabilities = List.copyOf(probabilities);
        if (probabilities.size() < 2 || probabilities.size() > 10) throw new IllegalArgumentException("Invalid score dimensions");
        if (selectedLevel < 0 || selectedLevel >= probabilities.size()) {
            throw new IllegalArgumentException("Invalid selected level");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("Invalid score");
        }
        for (double probability : probabilities) {
            if (!Double.isFinite(probability) || probability < 0 || probability > 1) throw new IllegalArgumentException("Invalid probability");
        }
    }
}
