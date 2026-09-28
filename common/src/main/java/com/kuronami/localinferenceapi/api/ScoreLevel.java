package com.kuronami.localinferenceapi.api;

/** 昇順に並べる評価段階。値は有限で、同じ尺度の要求間で固定する。 */
public record ScoreLevel(String description, double value) {
    public ScoreLevel {
        InputText.validate(description, "description", 4_096);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Score level value must be finite");
    }
}
