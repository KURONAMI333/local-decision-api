package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * 1 backend への request channel。router は最大2本を持つ: supervised native
 * worker への channel(起動毎に遅延 open、port で識別)と Java-CPU fallback
 * channel(製品では WorkerClient を包む {@link ChannelTypedAdapter})。
 *
 * 契約は WorkerClient に揃える: 混雑・故障・終了時は例外完了し、回答を捏造
 * しない。呼出側をブロックしてはいけない。
 */
public interface SomChannel extends AutoCloseable {

    /**
     * 1件の request body を送る。transport エラー・非2xx・不正応答・取消は
     * 例外完了で返し、null を返さない。
     */
    CompletableFuture<JsonObject> request(JsonObject body);

    @Override
    default void close() {}
}
