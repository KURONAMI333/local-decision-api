package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.NoulRequest;
import com.kuronami.localinferenceapi.api.ScoreRequest;
import com.kuronami.localinferenceapi.internal.CancellableFutures;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * TypedBackend を SomRouter の JsonObject channel seam に適合させる bridge。
 * native 経路が送るのと同じ JEV request body を受け取り、組み立てられた元の
 * typed 要求へ decode して typed CPU backend を呼び、結果を JEV 形の answer
 * body へ再 encode する。router から見える wire 形は両経路で同一になる。
 */
public final class ChannelTypedAdapter implements SomChannel {

    private final TypedBackend backend;

    public ChannelTypedAdapter(TypedBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    @Override
    public CompletableFuture<JsonObject> request(JsonObject body) {
        Object typed;
        try {
            typed = JevCodec.decodeRequest(body);
        } catch (JevCodec.MappingException | RuntimeException bad) {
            // mapping 失敗も typed record の validation 拒否も request 単位の
            // 失敗。ここでの同期 throw は router の seq を止めるため返さない。
            return CompletableFuture.failedFuture(bad);
        }
        CompletableFuture<? extends Record> call;
        if (typed instanceof DecisionRequest r) call = backend.decide(r);
        else if (typed instanceof ScoreRequest r) call = backend.score(r);
        else if (typed instanceof NoulRequest r) call = backend.noul(r);
        else return CompletableFuture.failedFuture(
                new IllegalStateException("unsupported typed request"));
        return CancellableFutures.map(call, result -> {
            try {
                return JevCodec.encodeAnswer(result, body);
            } catch (JevCodec.MappingException encode) {
                throw new RuntimeException(encode);
            }
        });
    }

    @Override
    public void close() {
        // adapter は backend を所有しない。製品では WorkerClient の寿命を
        // InferenceLifecycle が管理するため、router.close() → channel.close()
        // の連鎖で実 worker を殺してはいけない(opt-in を途中で外しても
        // Java+ONNX 経路は同じ worker で供用を続ける)。
    }
}
