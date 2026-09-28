package com.kuronami.localinferenceapi.internal.som;

import com.kuronami.localinferenceapi.internal.WorkerClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 製品配線: SOM 経路の実配線を1箇所に集める。native 側は
 * {@link LlamaGate} — pin 済み manifest の検証・platform 別 runtime の
 * stage/展開・bytes 再照合を通ってから llama-server を 127.0.0.1 限定で
 * 起動する。stage 中・cooldown 中・未対応 platform では NO_COMMIT 系の
 * availability を返し、router が CPU 経路へ退避させる。CPU 側は既存の
 * WorkerClient を JEV wire 形のまま包み、router が見る経路を native と
 * 同形にする。
 *
 * 試験がゲートを差し替える経路は {@link #create(Path, WorkerClient,
 * SomNativeGate)} の package-private 版。
 */
public final class SomWiring {

    /** native 呼出の timeout。WorkerClient の推論 timeout と同じ 60 秒。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** 連続する native request 失敗で circuit を latch する閾値。 */
    private static final int MAX_NATIVE_FAILURES = 3;

    /**
     * 試験 seam — native gate の生成を差し替える。製品既定は
     * {@link #productionGate}(LlamaGate)。WorkerClient 側の workerFactory
     * と同じ「内部試験のための static volatile」規則に従う。
     */
    public static volatile java.util.function.Function<Path, SomNativeGate>
            gateFactory = SomWiring::productionGate;

    /** 製品 native gate — pin 済み manifest の staging + llama-server 監督。 */
    public static SomNativeGate productionGate(Path gameDirectory) {
        return new LlamaGate(gameDirectory);
    }

    private SomWiring() {}

    public static SomTypedClient create(Path gameDirectory, WorkerClient worker) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        return create(gameDirectory, worker, gateFactory.apply(gameDirectory));
    }

    /** 試験 seam — gate を差し替える(fail-closed 既定・stub・fake spawn)。 */
    static SomTypedClient create(Path gameDirectory, WorkerClient worker,
                               SomNativeGate gate) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(worker, "worker");
        LlamaGate llama = gate instanceof LlamaGate g ? g : null;
        SomRouter router = new SomRouter(
                gate,
                port -> {
                    LlamaClient client = new LlamaClient(port,
                            llama != null ? llama::apiKey : () -> null,
                            REQUEST_TIMEOUT);
                    return new LlamaSystemoneChannel(client, REQUEST_TIMEOUT,
                            llama != null ? llama::touch : () -> {});
                },
                new ChannelTypedAdapter(worker),
                REQUEST_TIMEOUT,
                MAX_NATIVE_FAILURES);
        return new SomTypedClient(router);
    }
}
