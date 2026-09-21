package com.kuronami.localinferenceapi.internal;

import com.kuronami.localinferenceapi.api.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 実モデル用の手動 smoke。classpath の /localinferenceapi/runtime.jar を外側 API から起動する。 */
public final class ApiSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass an isolated game directory");
        long start = System.nanoTime();
        LocalInference.initialize(Path.of(args[0]));
        try {
            var lost = LocalInference.decide(new DecisionRequest(
                    "I lost my bank card yesterday and do not recognize a purchase on my account.",
                    "What should I do next?",
                    List.of("Freeze the card and contact the bank", "Ignore the unfamiliar purchase"))).get(240, TimeUnit.SECONDS);
            System.out.println("lost-card=" + lost);
            var password = LocalInference.decide(new DecisionRequest(
                    "A stranger sent an email asking for my bank password to unlock my account.",
                    "What should I do next?",
                    List.of("Send the stranger my password", "Do not share the password and contact the bank directly"))).get(90, TimeUnit.SECONDS);
            System.out.println("password=" + password);
            if (lost.probabilities().size() != 3 || password.probabilities().size() != 3) throw new AssertionError("Invalid score dimensions");
            System.out.println("elapsed-seconds=" + (System.nanoTime() - start) / 1_000_000_000.0);
        } finally { LocalInference.close(); }
        if (!LocalInference.decide(new DecisionRequest("context", "question", List.of("choice"))).isCompletedExceptionally()) {
            throw new AssertionError("close did not reject the next request");
        }
    }
}
