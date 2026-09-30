package com.kuronami.localinferenceapi.internal;

import com.google.gson.*;
import com.kuronami.localinferenceapi.api.*;
import com.kuronami.localinferenceapi.internal.som.TypedBackend;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 1 プロセス・1 推論の境界。初回 ready 前の早期終了だけ1回再試行する。
 *  opt-in SOM 経路では同じインスタンスが ChannelTypedAdapter の CPU backend になる。 */
public final class WorkerClient implements AutoCloseable, TypedBackend {
    private static final int MAX_FRAME = 1_048_576;
    private static final Gson JSON = new Gson();
    private final Object lock = new Object();
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService watchdog;
    private final Launcher launcher;
    private final Duration startupTimeout;
    private final Duration inferenceTimeout;
    private final Duration idleTimeout;
    private ScheduledFuture<?> idleDeadline;
    private boolean idleStopping;
    private Process process;
    private DataInputStream input;
    private DataOutputStream output;
    private Throwable terminalFailure;

    @FunctionalInterface interface Launcher { Process launch() throws IOException; }

    public WorkerClient(Path gameDirectory) {
        this(() -> launchBundled(gameDirectory), Duration.ofSeconds(180), Duration.ofSeconds(60), Duration.ofMinutes(5));
    }

    WorkerClient(Launcher launcher, Duration startupTimeout, Duration inferenceTimeout) {
        this(launcher, startupTimeout, inferenceTimeout, Duration.ofMinutes(5));
    }

    WorkerClient(Launcher launcher, Duration startupTimeout, Duration inferenceTimeout, Duration idleTimeout) {
        this.launcher = Objects.requireNonNull(launcher);
        this.startupTimeout = startupTimeout;
        this.inferenceTimeout = inferenceTimeout;
        if (idleTimeout.isNegative() || idleTimeout.isZero()) throw new IllegalArgumentException("Idle timeout must be positive");
        this.idleTimeout = idleTimeout;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                task -> daemon(task, "localinferenceapi-inference"), new ThreadPoolExecutor.AbortPolicy());
        watchdog = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "localinferenceapi-watchdog"));
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public CompletableFuture<DecisionResult> decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        return submit(request, response -> parseResult(response, request.choices().size()));
    }

    public CompletableFuture<ScoreResult> score(ScoreRequest request) {
        Objects.requireNonNull(request, "request");
        return submit(new TypedRequest<>("score", request), response -> parseScoreResult(response, request.levels().size()));
    }

    public CompletableFuture<NoulResult> noul(NoulRequest request) {
        Objects.requireNonNull(request, "request");
        return submit(new TypedRequest<>("noul", request), WorkerClient::parseNoulResult);
    }

    private record TypedRequest<T>(String kind, T body) {}
    @FunctionalInterface private interface Parser<T> { T parse(JsonObject response) throws IOException; }

    private <T> CompletableFuture<T> submit(Object request, Parser<T> parser) {
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (lock) {
            if (terminalFailure != null) return CompletableFuture.failedFuture(terminalFailure);
            if (idleDeadline != null) idleDeadline.cancel(false);
            pending.add(future);
            try {
                executor.execute(() -> execute(request, future, parser));
            } catch (RejectedExecutionException full) {
                pending.remove(future);
                future.completeExceptionally(new RejectedExecutionException("Local Decision API request queue is full"));
            }
        }
        return future;
    }

    private <T> void execute(Object request, CompletableFuture<T> future, Parser<T> parser) {
        try {
            if (future.isDone()) return;
            ensureStarted();
            if (future.isDone()) return;
            ScheduledFuture<?> deadline = deadline(inferenceTimeout, "Local Decision API inference timed out");
            try {
                String json = request instanceof TypedRequest<?> typed
                        ? typedJson(typed) : JSON.toJson(request);
                writeFrame(output, json);
                JsonObject response = readFrame(input);
                if (response.has("error")) {
                    // モデルの入力拒否はプロセス故障ではない。詳細ログは subprocess 側へ置く。
                    deadline.cancel(false);
                    completeResponse(future, null, new IllegalArgumentException("Local Decision API rejected this request"));
                } else {
                    T result = parser.parse(response);
                    deadline.cancel(false);
                    completeResponse(future, result, null);
                }
            } finally {
                deadline.cancel(false);
            }
        } catch (Exception failure) {
            fail(new IllegalStateException("Local Decision API worker failed; see the local worker log", failure));
        } finally {
            synchronized (lock) {
                pending.remove(future);
                if (terminalFailure == null && pending.isEmpty() && process != null) {
                    idleDeadline = watchdog.schedule(this::unloadWhenIdle, idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void unloadWhenIdle() {
        Process stopped;
        synchronized (lock) {
            if (terminalFailure != null || !pending.isEmpty() || process == null) return;
            idleDeadline = null;
            idleStopping = true;
            stopped = process;
        }
        // ゲーム側のsubmitは待たせず、推論executorだけが停止完了を待つ。
        boolean exited = terminate(stopped);
        if (!exited) fail(new IllegalStateException("Idle worker did not stop"));
        synchronized (lock) {
            if (exited && process == stopped) {
                process = null;
                input = null;
                output = null;
            }
            idleStopping = false;
            lock.notifyAll();
        }
    }

    private static String typedJson(TypedRequest<?> typed) {
        JsonObject object = JSON.toJsonTree(typed.body()).getAsJsonObject();
        object.addProperty("kind", typed.kind());
        return object.toString();
    }

    private <T> void completeResponse(CompletableFuture<T> future, T result, Throwable failure) {
        synchronized (lock) {
            // close / timeout と応答の受理を一箇所で決着させる。
            // pending を先に回収した側だけが通知を担当し、除去後も必ず完了を発行する。
            if (!pending.remove(future)) return;
        }
        // 利用側の同期 callback が停止しても、次の推論を止めない。
        // この thread は通知専用であり、native 推論は executor 上に留める。
        Thread.startVirtualThread(() -> {
            if (failure == null) future.complete(result);
            else future.completeExceptionally(failure);
        });
    }

    private void ensureStarted() throws IOException {
        synchronized (lock) {
            while (idleStopping && terminalFailure == null) {
                try { lock.wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for idle worker shutdown", interrupted);
                }
            }
            if (terminalFailure != null) throw new IOException("Worker is closed");
            if (process != null) return;
        }
        IOException firstExit = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ScheduledFuture<?> deadline = deadline(startupTimeout, "Local Decision API startup timed out");
            Process created = null;
            try {
                created = launcher.launch();
                synchronized (lock) {
                    if (terminalFailure != null) {
                        terminate(created);
                        throw new IOException("Worker closed during startup");
                    }
                    process = created;
                    input = new DataInputStream(new BufferedInputStream(created.getInputStream()));
                    output = new DataOutputStream(new BufferedOutputStream(created.getOutputStream()));
                }
                JsonObject ready = readFrame(input);
                if (!ready.has("ready") || !ready.get("ready").isJsonPrimitive()
                        || !ready.getAsJsonPrimitive("ready").isBoolean() || !ready.get("ready").getAsBoolean()) {
                    throw new IOException("Worker did not report readiness");
                }
                return;
            } catch (IOException failure) {
                // Only an EOF before the first ready frame can be a native
                // worker crash. Malformed frames, launch errors and model
                // rejection are deterministic failures, not retry signals.
                boolean exitedBeforeReady = failure instanceof EOFException
                        && created != null && exited(created);
                synchronized (lock) {
                    if (process == created) {
                        process = null;
                        input = null;
                        output = null;
                    }
                }
                if (created != null && created.isAlive()) terminate(created);
                if (attempt == 0 && exitedBeforeReady && !isClosed()) {
                    firstExit = failure;
                    System.err.println("[Local Decision API] worker exited before ready; retrying once");
                    continue;
                }
                if (firstExit != null) failure.addSuppressed(firstExit);
                throw failure;
            } finally {
                deadline.cancel(false);
            }
        }
    }

    private static boolean exited(Process process) {
        if (!process.isAlive()) return true;
        try {
            return process.waitFor(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ScheduledFuture<?> deadline(Duration duration, String message) {
        return watchdog.schedule(() -> fail(new TimeoutException(message)), duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    static DecisionResult parseResult(JsonObject object, int expectedChoices) throws IOException {
        try {
            JsonElement selection = object.get("selected");
            if (selection == null) throw new IOException("Missing selected result");
            if (!selection.isJsonPrimitive() || !selection.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Invalid selected type");
            }
            int selected = selection.getAsBigDecimal().intValueExact();
            List<Double> probabilities = numbers(object.getAsJsonArray("probabilities"), expectedChoices);
            List<Double> logits = numbers(object.getAsJsonArray("logits"), expectedChoices);
            return new DecisionResult(selected, probabilities, logits, truncated(object));
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid worker response", malformed);
        }
    }

    static ScoreResult parseScoreResult(JsonObject object, int expectedLevels) throws IOException {
        try {
            int selected = requiredIndex(object.get("selectedLevel"), expectedLevels);
            JsonElement value = object.get("score");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Missing score");
            return new ScoreResult(value.getAsDouble(), selected, numbers(object.getAsJsonArray("probabilities"), expectedLevels), truncated(object));
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid score result", malformed);
        }
    }

    static NoulResult parseNoulResult(JsonObject object) throws IOException {
        try {
            JsonElement probability = object.get("trueProbability");
            if (probability == null || !probability.isJsonPrimitive() || !probability.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Missing Noul fields");
            }
            return new NoulResult(probability.getAsDouble(), truncated(object));
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid Noul result", malformed);
        }
    }

    private static boolean truncated(JsonObject object) {
        JsonElement flag = object.get("truncated");
        return flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean();
    }

    private static int requiredIndex(JsonElement value, int maximum) throws IOException {
        if (value == null) throw new IOException("Missing selected index");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid index type");
        int index = value.getAsBigDecimal().intValueExact();
        if (index < 0 || index >= maximum) throw new IOException("Index outside request");
        return index;
    }

    private static List<Double> numbers(JsonArray array, int expected) throws IOException {
        if (array == null || array.size() != expected) throw new IOException("Result dimensions differ from request");
        List<Double> result = new ArrayList<>(expected);
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid score type");
            result.add(value.getAsDouble());
        }
        return result;
    }

    static JsonObject readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FRAME) throw new IOException("Invalid worker frame length");
        byte[] data = input.readNBytes(length);
        if (data.length != length) throw new EOFException("Incomplete worker frame");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IOException("Worker frame is not an object");
            return parsed.getAsJsonObject();
        } catch (JsonParseException invalid) {
            throw new IOException("Invalid worker JSON", invalid);
        }
    }

    static void writeFrame(DataOutputStream output, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > MAX_FRAME) throw new IOException("Request frame is too large");
        output.writeInt(data.length);
        output.write(data);
        output.flush();
    }

    private static Process launchBundled(Path gameDirectory) throws IOException {
        Path cache = gameDirectory.resolve(".localinferenceapi/runtime");
        Path jar = RuntimeCache.extract(cache, () -> WorkerClient.class.getResourceAsStream("/localinferenceapi/runtime.jar"));
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path log = cache.resolve("worker-" + ProcessHandle.current().pid() + ".log");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xmx2G", "-jar", jar.toString(), "--worker")
                .directory(gameDirectory.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("DJL_OFFLINE", "true");
        builder.environment().put("HF_HUB_OFFLINE", "1");
        builder.environment().put("TRANSFORMERS_OFFLINE", "1");
        return builder.start();
    }

    private void fail(Throwable failure) {
        Process stopped;
        List<CompletableFuture<?>> unfinished;
        synchronized (lock) {
            if (terminalFailure != null) return;
            terminalFailure = failure;
            stopped = process;
            unfinished = List.copyOf(pending);
            pending.clear();
            executor.shutdownNow();
            watchdog.shutdownNow();
        }
        // callback が止まっても native プロセスを残さないよう、future 完了より先に停止する。
        if (stopped != null) terminate(stopped);
        // ある利用側の同期 callback が止まっても、他の待機要求と停止元を巻き込まない。
        // native 推論は virtual thread 上では動かさない。
        unfinished.forEach(future -> Thread.startVirtualThread(() -> future.completeExceptionally(failure)));
    }

    private static boolean terminate(Process process) {
        process.destroy();
        try {
            if (process.waitFor(2, TimeUnit.SECONDS)) return true;
            process.destroyForcibly();
            return process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    public boolean isClosed() { synchronized (lock) { return terminalFailure != null; } }

    @Override public void close() { fail(new CancellationException("Local Decision API stopped")); }
}
