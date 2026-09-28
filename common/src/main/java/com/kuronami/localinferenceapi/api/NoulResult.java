package com.kuronami.localinferenceapi.api;

/** 命題が成り立つとモデルが割り当てた確率。較正済みの事実確率ではない。truncated は入力がモデルの制約に合わせて短縮された印。 */
public record NoulResult(double trueProbability, boolean truncated) {
    public NoulResult(double trueProbability) {
        this(trueProbability, false);
    }

    public NoulResult {
        if (!Double.isFinite(trueProbability) || trueProbability < 0 || trueProbability > 1)
            throw new IllegalArgumentException("Invalid true probability");
    }
}
