package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.DecisionResult;
import com.kuronami.localinferenceapi.api.NoulRequest;
import com.kuronami.localinferenceapi.api.NoulResult;
import com.kuronami.localinferenceapi.api.ScoreRequest;
import com.kuronami.localinferenceapi.api.ScoreResult;
import com.kuronami.localinferenceapi.internal.CancellableFutures;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * SomRouter 上の typed な顔。呼出側は製品の request/result record をそのまま
 * 使い、routing・fallback・trust latch・timeout・取消は router が扱う。
 * request 毎の経路は明示されるため、degraded 応答が native 応答と区別不能に
 * なることはない。
 */
public final class SomTypedClient implements AutoCloseable {

    /** typed 結果と、それを実際に処理した経路。 */
    public record TypedReply<T>(T result, SomRouter.RouteUsed route) {}

    private final SomRouter router;

    public SomTypedClient(SomRouter router) {
        this.router = Objects.requireNonNull(router);
    }

    public CompletableFuture<TypedReply<DecisionResult>> decide(DecisionRequest req) {
        return call(JevCodec.encodeDecision(req),
                body -> JevCodec.decodeDecision(body, req));
    }

    public CompletableFuture<TypedReply<ScoreResult>> score(ScoreRequest req) {
        return call(JevCodec.encodeScore(req),
                body -> JevCodec.decodeScore(body, req));
    }

    public CompletableFuture<TypedReply<NoulResult>> noul(NoulRequest req) {
        return call(JevCodec.encodeNoul(req), JevCodec::decodeNoul);
    }

    private <T> CompletableFuture<TypedReply<T>> call(
            JsonObject body, ThrowingParser<T> parser) {
        return CancellableFutures.map(router.request(body), reply -> {
            try {
                return new TypedReply<>(parser.parse(reply.body()), reply.route());
            } catch (Exception bad) {
                throw new RuntimeException(bad);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingParser<T> {
        T parse(JsonObject body) throws Exception;
    }

    public SomRouter.Status status() { return router.status(); }
    public boolean trustFailure() { return router.trustFailure(); }
    public boolean nativeCircuitOpen() { return router.nativeCircuitOpen(); }
    public SomRouter.RouteUsed lastRoute() { return router.lastRoute(); }
    public SomNativeGate.Availability lastNativeAvailability() { return router.lastNativeAvailability(); }
    public boolean isClosed() { return router.isClosed(); }

    @Override
    public void close() { router.close(); }
}
