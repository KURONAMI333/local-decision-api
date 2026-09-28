package com.kuronami.localinferenceapi.api;

import java.util.List;
import java.util.Objects;

/** モデルへ渡す文字列だけのスナップショット。Minecraft のオブジェクトを保持しない。 */
public record DecisionRequest(String context, String question, List<String> choices) {
    public DecisionRequest {
        InputText.validate(context, "context", 32_768);
        InputText.validate(question, "question", 4_096);
        Objects.requireNonNull(choices, "choices");
        if (choices.isEmpty() || choices.size() > 24) {
            throw new IllegalArgumentException("choices must contain 1 to 24 entries");
        }
        choices = List.copyOf(choices);
        for (String choice : choices) InputText.validate(choice, "choice", 4_096);
        if (choices.stream().distinct().count() != choices.size()) {
            throw new IllegalArgumentException("choices must be distinct");
        }
    }

}
