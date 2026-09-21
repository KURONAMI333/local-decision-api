package com.kuronami.somcore.api;

import java.util.List;
import java.util.Objects;

/** モデルへ渡す文字列だけのスナップショット。Minecraft のオブジェクトを保持しない。 */
public record DecisionRequest(String context, String question, List<String> choices) {
    public DecisionRequest {
        validate(context, "context", 32_768);
        validate(question, "question", 4_096);
        Objects.requireNonNull(choices, "choices");
        if (choices.isEmpty() || choices.size() > 24) {
            throw new IllegalArgumentException("choices must contain 1 to 24 entries");
        }
        choices = List.copyOf(choices);
        for (String choice : choices) validate(choice, "choice", 4_096);
        if (choices.stream().distinct().count() != choices.size()) {
            throw new IllegalArgumentException("choices must be distinct");
        }
    }

    private static void validate(String text, String field, int maximum) {
        Objects.requireNonNull(text, field);
        if (text.isBlank() || text.length() > maximum) {
            throw new IllegalArgumentException(field + " is empty or exceeds its size limit");
        }
        // prompt の構造を変えるモデル区切りと tokenizer の特殊トークンを受け入れない。
        for (String reserved : List.of("<<LABEL>>", "<<SEP>>", "<<ENTAILMENT>>", "<|", "|>",
                "[CLS]", "[SEP]", "[MASK]", "[PAD]", "[UNK]", "[unused", "|||")) {
            if (text.contains(reserved)) throw new IllegalArgumentException(field + " contains a reserved token");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                throw new IllegalArgumentException(field + " contains a control character");
            }
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException(field + " contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(field + " contains invalid Unicode");
            }
        }
    }
}
