package com.kuronami.localinferenceapi.api;

/** 命題の真偽を二択で問う。証拠不足の判定は持たない。 */
public record NoulRequest(String context, String proposition) {
    public NoulRequest {
        InputText.validate(context, "context", 32_768);
        InputText.validate(proposition, "proposition", 4_096);
    }
}
