package com.kuronami.localinferenceapi.api;

import java.util.List;
import java.util.Objects;

/** 内容を順序付きの尺度で評価する。 */
public record ScoreRequest(String context, String question, List<ScoreLevel> levels) {
    public ScoreRequest {
        InputText.validate(context, "context", 32_768);
        InputText.validate(question, "question", 4_096);
        Objects.requireNonNull(levels, "levels");
        if (levels.size() < 2 || levels.size() > 10) {
            throw new IllegalArgumentException("Score requires 2 to 10 levels");
        }
        levels = List.copyOf(levels);
        for (int i = 1; i < levels.size(); i++) {
            if (levels.get(i).value() <= levels.get(i - 1).value()) {
                throw new IllegalArgumentException("Score level values must increase strictly");
            }
        }
    }
}
