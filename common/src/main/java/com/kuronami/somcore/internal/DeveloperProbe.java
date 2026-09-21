package com.kuronami.somcore.internal;

import com.kuronami.somcore.Constants;
import com.kuronami.somcore.api.*;
import java.util.List;

/** 開発起動時だけ有効にする、loader内から同梱モデルまでの疎通確認。 */
public final class DeveloperProbe {
    private DeveloperProbe() {}
    public static void runIfRequested() {
        if (!Boolean.getBoolean("somcore.selfTest")) return;
        var labels = List.of("Reporting a lost bank card", "Resetting a password", "Requesting a loan");
        long started = System.nanoTime();
        SomCore.decide(new DecisionRequest("I lost my wallet and need to block my debit card.",
                "What does this person need?", labels)).thenCompose(first -> {
            if (!Integer.valueOf(0).equals(first.selected())) throw new IllegalStateException("First classification differs: " + first);
            return SomCore.decide(new DecisionRequest("I cannot remember my login password. Please reset it.",
                    "What does this person need?", labels));
        }).whenComplete((second, failure) -> {
            try {
                if (failure != null) Constants.LOG.error("SOM_SELF_TEST_FAILED", failure);
                else if (!Integer.valueOf(1).equals(second.selected())) Constants.LOG.error("SOM_SELF_TEST_FAILED: {}", second);
                else Constants.LOG.info("SOM_SELF_TEST_PASSED elapsed_ms={} result={}", (System.nanoTime() - started) / 1e6, second);
            } finally { SomCore.close(); }
        });
    }
}
