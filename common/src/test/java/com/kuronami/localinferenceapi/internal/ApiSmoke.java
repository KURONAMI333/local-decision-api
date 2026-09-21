package com.kuronami.localinferenceapi.internal;

import com.kuronami.localinferenceapi.api.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 配布JARのAPIを2つの呼出元で共有し、終了・再初期化を実workerで確認する手動試験。 */
public final class ApiSmoke {
    private static DecisionRequest request(String text) {
        return new DecisionRequest(text, "What does this person need?", List.of(
                "Reporting a lost bank card", "Resetting a password", "Requesting a loan"));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass an isolated game directory");
        long start = System.nanoTime();
        Path directory = Path.of(args[0]);
        InferenceLifecycle.initialize(directory);
        try {
            var first = LocalInference.decide(request("I lost my wallet and need to block my debit card."));
            var second = LocalInference.decide(request("I cannot remember my login password. Please reset it."));
            var firstResult = first.get(240, TimeUnit.SECONDS);
            var secondResult = second.get(90, TimeUnit.SECONDS);
            if (!Integer.valueOf(0).equals(firstResult.selected()) || !Integer.valueOf(1).equals(secondResult.selected()))
                throw new AssertionError("Classification regression: " + firstResult + " / " + secondResult);
            if (firstResult.probabilities().size() != 4 || secondResult.probabilities().size() != 4)
                throw new AssertionError("Invalid score dimensions");
            var children = ProcessHandle.current().children().filter(ProcessHandle::isAlive).toList();
            if (children.size() != 1) throw new AssertionError("Expected one shared worker: " + children.size());
            InferenceLifecycle.endSession();
            children.getFirst().onExit().get(15, TimeUnit.SECONDS);
            var menuResult = LocalInference.decide(request("I cannot remember my login password. Please reset it.")).get(240, TimeUnit.SECONDS);
            if (!Integer.valueOf(1).equals(menuResult.selected())) throw new AssertionError("Title/menu request after session end failed");
            var menuChildren = ProcessHandle.current().children().filter(ProcessHandle::isAlive).toList();
            if (menuChildren.size() != 1 || menuChildren.getFirst().pid() == children.getFirst().pid())
                throw new AssertionError("Menu request did not lazily start a fresh single worker");
            InferenceLifecycle.shutdown();
            menuChildren.getFirst().onExit().get(15, TimeUnit.SECONDS);
            if (!LocalInference.decide(request("closed")).isCompletedExceptionally())
                throw new AssertionError("close did not reject the next request");
            InferenceLifecycle.initialize(directory);
            var reopened = LocalInference.decide(request("I cannot remember my login password. Please reset it.")).get(240, TimeUnit.SECONDS);
            if (!Integer.valueOf(1).equals(reopened.selected())) throw new AssertionError("Reopen regression");
            var newChildren = ProcessHandle.current().children().filter(ProcessHandle::isAlive).toList();
            if (newChildren.size() != 1 || newChildren.getFirst().pid() == children.getFirst().pid())
                throw new AssertionError("A fresh single worker was not launched");
            InferenceLifecycle.shutdown();
            newChildren.getFirst().onExit().get(15, TimeUnit.SECONDS);
            System.out.println("API_SMOKE_PASS concurrentRequests=2 sharedWorker=1 close=true menuAfterEnd=true reopen=true");
            System.out.println("elapsed-seconds=" + (System.nanoTime() - start) / 1_000_000_000.0);
        } finally { InferenceLifecycle.shutdown(); }
    }
}
