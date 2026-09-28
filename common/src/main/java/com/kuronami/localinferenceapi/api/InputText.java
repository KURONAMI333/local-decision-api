package com.kuronami.localinferenceapi.api;

import java.util.List;
import java.util.Objects;

final class InputText {
    private InputText() {}

    static void validate(String text, String field, int maximum) {
        Objects.requireNonNull(text, field);
        if (text.isBlank() || text.length() > maximum) {
            throw new IllegalArgumentException(field + " is empty or exceeds its size limit");
        }
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
