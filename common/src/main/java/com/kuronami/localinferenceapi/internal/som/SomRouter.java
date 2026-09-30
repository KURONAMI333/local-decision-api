package com.kuronami.localinferenceapi.internal.som;

import com.google.gson.JsonObject;
import com.kuronami.localinferenceapi.internal.CompletionThreads;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SOM degraded router: SOM 要求を gated native worker へ向け、Java-CPU
 * channel(製品では WorkerClient を包む ChannelTypedAdapter)へ退避し、
 * 両方失敗時は DISABLED を報告する。
 *
 * 失敗分類(意図的に非対称):
 *
 *  - TRUST 失敗(gate が TAMPERED): パッケージ/marker の真正性検査に落ちた。
 *    native 経路は fail-closed — この router の存続中 latch され、信頼できない
 *    artifact は二度と起動を試みない。要求は信頼済みの JAR 内 CPU 経路で応答
 *    できるが、trustFailure() は呼出側へ可視のまま残る。
 *  - AVAILABILITY 失敗(NO_COMMIT / NO_RUNTIME_ARTIFACT): native が単純に
 *    未導入。CPU へ退避し、次回 request で再 gate する。
 *  - LAUNCH 失敗(LAUNCH_FAILED / STARTUP_TIMEOUT): CPU へ退避。gate 側の
 *    bounded retry に任せ、ここでは loop しない。
 *  - REQUEST 失敗(timeout / transport / 非2xx / worker 死): worker を
 *    invalidateWorker() で落とし、その request は CPU が応答する。連続失敗が
 *    maxNativeFailures に達したら circuit を latch し、要求毎の flapping を
 *    防ぐ。
 *  - DISABLED: CPU も失敗 — request は例外完了(WorkerClient の慣例)し、
 *    status() は DISABLED を報告する。
 */
public final class SomRouter implements AutoCloseable {

    public enum Status { NATIVE, DEGRADED, DISABLED }
    public enum RouteUsed { NATIVE, CPU, NONE }

    /** 1回の routed request の結果 — 実際に処理した経路を明示する。 */
    public record RouteReply(JsonObject body, RouteUsed route) {}

    /** 起動直後の native worker へ request channel を開く。 */
    @FunctionalInterface
    public interface NativeChannelFactory {
        SomChannel open(int loopbackPort);
    }

    /** 1 active request plus 16 waiting, shared by native and CPU routes. */
    private static final int MAX_PENDING = 17;

    private final SomNativeGate gate;
    private final NativeChannelFactory nativeFactory;
    private final SomChannel cpu;
    private final Duration requestTimeout;
    private final int maxNativeFailures;
    private final ExecutorService seq =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "localinferenceapi-som-router");
                t.setDaemon(true);
                return t;
            });
    private final Set<CompletableFuture<RouteReply>> pending = new HashSet<>();

    private SomChannel nativeChannel;
    private int nativePort = -1;
    private int consecutiveNativeFailures = 0;
    private boolean trustFailure;      // latch — 非信頼 set は再 gate しない
    private boolean circuitOpen;       // latch — 連続 request 失敗が上限へ
    private boolean closed;
    private volatile Status status = Status.DEGRADED;
    private volatile RouteUsed lastRoute = RouteUsed.NONE;
    private volatile SomNativeGate.Availability lastAvailability =
            SomNativeGate.Availability.NO_COMMIT;

    public SomRouter(SomNativeGate gate, NativeChannelFactory nativeFactory,
                     SomChannel cpuChannel, Duration requestTimeout,
                     int maxNativeFailures) {
        this.gate = java.util.Objects.requireNonNull(gate);
        this.nativeFactory = java.util.Objects.requireNonNull(nativeFactory);
        this.cpu = java.util.Objects.requireNonNull(cpuChannel);
        this.requestTimeout = requestTimeout;
        this.maxNativeFailures = Math.max(1, maxNativeFailures);
    }

    public Status status() { return status; }
    public RouteUsed lastRoute() { return lastRoute; }
    public boolean trustFailure() { return trustFailure; }
    public boolean nativeCircuitOpen() { return circuitOpen; }
    public SomNativeGate.Availability lastNativeAvailability() { return lastAvailability; }
    public synchronized boolean isClosed() { return closed; }

    /** 1 request を振り分ける。呼出側をブロックせず、close 後は即座に失敗する。 */
    public CompletableFuture<RouteReply> request(JsonObject body) {
        CompletableFuture<RouteReply> out = new CompletableFuture<>();
        synchronized (this) {
            if (closed) {
                out.completeExceptionally(
                        new IllegalStateException("router closed"));
                return out;
            }
            if (pending.size() >= MAX_PENDING) {
                out.completeExceptionally(new RejectedExecutionException(
                        "Local Decision API request queue is full"));
                return out;
            }
            pending.add(out);
        }
        // Cancellation can happen after dispatch while a native response is
        // pending. Its callback deliberately ignores a completed out, so the
        // caller's cancellation must release this capacity itself.
        out.whenComplete((reply, failure) -> {
            if (out.isCancelled()) {
                synchronized (this) { pending.remove(out); }
            }
        });
        try {
            seq.submit(() -> routeRequest(body, out));
        } catch (RejectedExecutionException race) {
            // pending.add と submit の間に close() が入った。close は既に
            // pending の全件へ失敗を配送しているので、ここでは二重完了を避ける
            // だけでよい(完了済みへの completeExceptionally は no-op)。
            synchronized (this) { pending.remove(out); }
            out.completeExceptionally(new IllegalStateException("router closed"));
        }
        return out;
    }

    private void routeRequest(JsonObject body, CompletableFuture<RouteReply> out) {
        if (out.isDone()) { // routing 前に caller が取消した
            synchronized (this) { pending.remove(out); }
            return;
        }
        if (!trustFailure && !circuitOpen) {
            SomNativeGate.Availability a = gate.ensureServing();
            synchronized (this) {
                lastAvailability = a;
                if (a == SomNativeGate.Availability.TAMPERED) {
                    trustFailure = true; // native は永久に fail closed
                }
            }
            if (a == SomNativeGate.Availability.SERVING) {
                SomChannel ch = channelForCurrentWorker();
                CompletableFuture<JsonObject> call;
                try {
                    call = ch.request(body); // 同期 throw で seq を止めない
                } catch (RuntimeException sync) {
                    call = CompletableFuture.failedFuture(sync);
                }
                CompletableFuture<JsonObject> nativeCall = call;
                out.whenComplete((reply, failure) -> {
                    if (out.isCancelled()) nativeCall.cancel(false);
                });
                call.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                        .whenComplete((res, err) -> {
                            // Handle completion on the routing executor. Closing a failed
                            // channel completes every queued call; those callbacks must not
                            // recursively invalidate the same worker.
                            try {
                                seq.execute(() -> {
                                    if (out.isDone() || isClosed()) return;
                                    if (err == null) {
                                        nativeOk(ch);
                                        finish(out, new RouteReply(res, RouteUsed.NATIVE));
                                    } else {
                                        Throwable cause = unwrap(err);
                                        if (cause instanceof RejectedExecutionException) {
                                            // Overload belongs to this request, not the worker.
                                            finish(out, cause);
                                            return;
                                        }
                                        if (!(cause instanceof IllegalArgumentException)) {
                                            nativeFailed(ch);
                                        }
                                        // A model context/input rejection can still use CPU,
                                        // but it must not destroy the healthy native worker.
                                        serveOnCpu(body, out);
                                    }
                                });
                            } catch (RejectedExecutionException stopped) {
                                finish(out, new IllegalStateException("router closed"));
                            }
                        });
                return;
            }
        }
        serveOnCpu(body, out);
    }

    /** 現在の worker の port に対応した channel。再起動後は作り直す。 */
    private synchronized SomChannel channelForCurrentWorker() {
        int p = gate.port();
        if (nativeChannel == null || p != nativePort) {
            if (nativeChannel != null) nativeChannel.close();
            nativeChannel = nativeFactory.open(p);
            nativePort = p;
        }
        return nativeChannel;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException
                || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    private synchronized void nativeOk(SomChannel channel) {
        if (closed || channel != nativeChannel) return;
        consecutiveNativeFailures = 0;
        status = Status.NATIVE;
        lastRoute = RouteUsed.NATIVE;
    }

    private synchronized void nativeFailed(SomChannel failedChannel) {
        // One physical worker failure counts once, even when close() fails many
        // requests or callbacks from an older worker arrive after replacement.
        if (closed || nativeChannel != failedChannel) return;
        nativeChannel = null;
        nativePort = -1;
        consecutiveNativeFailures++;
        if (consecutiveNativeFailures >= maxNativeFailures) circuitOpen = true;
        gate.invalidateWorker();
        failedChannel.close();
    }

    private void serveOnCpu(JsonObject body, CompletableFuture<RouteReply> out) {
        if (out.isDone() || isClosed()) return;
        CompletableFuture<JsonObject> call;
        try {
            call = cpu.request(body); // native 側と同じ stall 対策
        } catch (RuntimeException sync) {
            call = CompletableFuture.failedFuture(sync);
        }
        CompletableFuture<JsonObject> cpuCall = call;
        out.whenComplete((reply, failure) -> {
            if (out.isCancelled()) cpuCall.cancel(false);
        });
        call.whenComplete((res, err) -> {
            if (out.isDone() || isClosed()) return;
            if (err == null) {
                synchronized (this) {
                    status = Status.DEGRADED;
                    lastRoute = RouteUsed.CPU;
                }
                finish(out, new RouteReply(res, RouteUsed.CPU));
            } else {
                synchronized (this) { status = Status.DISABLED; }
                finish(out, new IllegalStateException("both routes failed", err));
            }
        });
    }

    /**
     * ちょうど1回だけ完了する — pending を抜いた側だけが配送権を持ち、遅れた
     * 完了(timeout済み native の遅配など)は設計上黙って負ける。実際の完了は
     * CompletionThreads 経由で配送し、channel の応答スレッドや close を呼んだ
     * server 停止スレッドで利用側 callback を走らせない(WorkerClient と同じ
     * 規律)。
     */
    private void finish(CompletableFuture<RouteReply> out, RouteReply reply) {
        boolean owned;
        synchronized (this) { owned = pending.remove(out); }
        if (owned) CompletionThreads.start(() -> out.complete(reply));
    }

    private void finish(CompletableFuture<RouteReply> out, Throwable t) {
        boolean owned;
        synchronized (this) { owned = pending.remove(out); }
        if (owned) CompletionThreads.start(() -> out.completeExceptionally(t));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<CompletableFuture<RouteReply>> unfinished = List.copyOf(pending);
        pending.clear();
        seq.shutdownNow();
        if (nativeChannel != null) nativeChannel.close();
        nativeChannel = null;
        cpu.close();
        gate.close();
        status = Status.DISABLED;
        // 配送は通知専用スレッドへ逃がす — close を呼んだスレッドを利用側
        // callback で占有させない。
        for (CompletableFuture<RouteReply> f : unfinished) {
            CompletionThreads.start(
                    () -> f.completeExceptionally(
                            new IllegalStateException("router closed")));
        }
    }
}
