package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * supervised native worker の loopback サービス(POST /v1/systemone)への HTTP
 * channel。構造上 loopback 限定: URI の host は 127.0.0.1 に固定し、port は
 * ゲートが起動毎に割り当てた値だけを受け取る。
 */
public final class HttpSomChannel implements SomChannel {

    private final HttpClient client;
    private final URI endpoint;
    private final Duration requestTimeout;

    public HttpSomChannel(int loopbackPort, Duration requestTimeout) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build(), loopbackPort, requestTimeout);
    }

    HttpSomChannel(HttpClient client, int loopbackPort, Duration requestTimeout) {
        this.client = client;
        this.endpoint = URI.create("http://127.0.0.1:" + loopbackPort + "/v1/systemone");
        this.requestTimeout = requestTimeout;
    }

    @Override
    public CompletableFuture<JsonObject> request(JsonObject body) {
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException(
                                new IOException("native worker HTTP " + resp.statusCode()));
                    }
                    try {
                        return JsonParser.parseString(resp.body()).getAsJsonObject();
                    } catch (RuntimeException malformed) {
                        throw new RuntimeException(
                                new IOException("malformed native response", malformed));
                    }
                });
    }

    @Override
    public void close() {
        // HttpClient はゲーム baseline では close を持たず、channel と共に GC される。
    }
}
