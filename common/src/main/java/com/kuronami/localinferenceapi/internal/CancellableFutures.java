package com.kuronami.localinferenceapi.internal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Maps a result without losing cancellation of a queued upstream request. */
public final class CancellableFutures {
    private CancellableFutures() {}

    public static <S, T> CompletableFuture<T> map(
            CompletableFuture<S> source, Function<? super S, ? extends T> mapper) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        CompletableFuture<T> result = new CompletableFuture<>();
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) source.cancel(false);
        });
        source.whenComplete((value, failure) -> {
            if (result.isDone()) return;
            if (source.isCancelled()) {
                result.cancel(false);
            } else if (failure != null) {
                result.completeExceptionally(failure);
            } else {
                try {
                    result.complete(mapper.apply(value));
                } catch (Throwable mappingFailure) {
                    result.completeExceptionally(mappingFailure);
                }
            }
        });
        return result;
    }
}
