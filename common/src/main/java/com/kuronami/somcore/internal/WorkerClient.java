package com.kuronami.somcore.internal;

import com.google.gson.*;
import com.kuronami.somcore.api.DecisionRequest;
import com.kuronami.somcore.api.DecisionResult;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 1 プロセス・1 推論の境界。異常終了後に自動再起動して失敗を繰り返さない。 */
public final class WorkerClient implements AutoCloseable {
    private static final int MAX_FRAME = 1_048_576;
    private static final Gson JSON = new Gson();
    private final Object lock = new Object();
    private final Set<CompletableFuture<DecisionResult>> pending = new HashSet<>();
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService watchdog;
    private final Launcher launcher;
    private final Duration startupTimeout;
    private final Duration inferenceTimeout;
    private Process process;
    private DataInputStream input;
    private DataOutputStream output;
    private Throwable terminalFailure;

    @FunctionalInterface interface Launcher { Process launch() throws IOException; }

    public WorkerClient(Path gameDirectory) {
        this(() -> launchBundled(gameDirectory), Duration.ofSeconds(180), Duration.ofSeconds(60));
    }

    WorkerClient(Launcher launcher, Duration startupTimeout, Duration inferenceTimeout) {
        this.launcher = Objects.requireNonNull(launcher);
        this.startupTimeout = startupTimeout;
        this.inferenceTimeout = inferenceTimeout;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                task -> daemon(task, "somcore-inference"), new ThreadPoolExecutor.AbortPolicy());
        watchdog = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "somcore-watchdog"));
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public CompletableFuture<DecisionResult> decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<DecisionResult> future = new CompletableFuture<>();
        synchronized (lock) {
            if (terminalFailure != null) return CompletableFuture.failedFuture(terminalFailure);
            pending.add(future);
            try {
                executor.execute(() -> execute(request, future));
            } catch (RejectedExecutionException full) {
                pending.remove(future);
                future.completeExceptionally(new RejectedExecutionException("SOM Core request queue is full"));
            }
        }
        return future;
    }

    private void execute(DecisionRequest request, CompletableFuture<DecisionResult> future) {
        try {
            if (future.isDone()) return;
            ensureStarted();
            if (future.isDone()) return;
            ScheduledFuture<?> deadline = deadline(inferenceTimeout, "SOM Core inference timed out");
            try {
                writeFrame(output, JSON.toJson(request));
                JsonObject response = readFrame(input);
                if (response.has("error")) {
                    // モデルの入力拒否はプロセス故障ではない。詳細ログは subprocess 側へ置く。
                    deadline.cancel(false);
                    future.completeExceptionally(new IllegalArgumentException("SOM Core rejected this request"));
                } else {
                    DecisionResult result = parseResult(response, request.choices().size());
                    deadline.cancel(false);
                    future.complete(result);
                }
            } finally {
                deadline.cancel(false);
            }
        } catch (Exception failure) {
            fail(new IllegalStateException("SOM Core worker failed; see the local worker log", failure));
        } finally {
            synchronized (lock) { pending.remove(future); }
        }
    }

    private void ensureStarted() throws IOException {
        synchronized (lock) {
            if (terminalFailure != null) throw new IOException("Worker is closed");
            if (process != null) return;
        }
        ScheduledFuture<?> deadline = deadline(startupTimeout, "SOM Core startup timed out");
        try {
            Process created = launcher.launch();
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
        } finally {
            deadline.cancel(false);
        }
    }

    private ScheduledFuture<?> deadline(Duration duration, String message) {
        return watchdog.schedule(() -> fail(new TimeoutException(message)), duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    static DecisionResult parseResult(JsonObject object, int expectedChoices) throws IOException {
        try {
            JsonElement selection = object.get("selected");
            if (selection == null) throw new IOException("Missing selected result");
            Integer selected = null;
            if (!selection.isJsonNull()) {
                if (!selection.isJsonPrimitive() || !selection.getAsJsonPrimitive().isNumber()) {
                    throw new IOException("Invalid selected type");
                }
                selected = selection.getAsBigDecimal().intValueExact();
            }
            List<Double> probabilities = numbers(object.getAsJsonArray("probabilities"), expectedChoices + 1);
            List<Double> logits = numbers(object.getAsJsonArray("logits"), expectedChoices + 1);
            return new DecisionResult(selected, probabilities, logits);
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid worker response", malformed);
        }
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
        Path cache = gameDirectory.resolve(".somcore/runtime");
        Path jar = RuntimeCache.extract(cache, () -> WorkerClient.class.getResourceAsStream("/som/runtime.jar"));
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
        List<CompletableFuture<DecisionResult>> unfinished;
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

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() { synchronized (lock) { return terminalFailure != null; } }

    @Override public void close() { fail(new CancellationException("SOM Core stopped")); }
}
